package com.districtone.webv9.service;

import com.districtone.webv9.repo.SalesWorkflowRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
public class SalesWorkflowService {
  private static final Set<String> ACTIVE_RESERVATIONS =
      Set.of("RECOVERY_ENTRY", "PENDING", "PENDING_VERIFICATION", "VERIFIED");

  public record MergeTransferMapping(Long applicationId, Long targetReceivableId) {}

  private final JdbcTemplate jdbc;
  private final SalesWorkflowRepository repository;
  private final ObjectMapper objectMapper;

  public SalesWorkflowService(JdbcTemplate jdbc, SalesWorkflowRepository repository, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public Map<String, Object> submitMerge(Long sourcePlotId, Long targetPlotId, LocalDate date,
                                         String notes, String username) {
    if (sourcePlotId == null || targetPlotId == null || date == null)
      bad("Source plot, target plot and merge date are required.");
    if (sourcePlotId.equals(targetPlotId)) bad("Source and target plot cannot be identical.");
    lockPlots(List.of(sourcePlotId, targetPlotId));
    Map<String, Object> source = activeBookingForPlot(sourcePlotId);
    Map<String, Object> target = activeBookingForPlot(targetPlotId);
    lockBookings(List.of(id(source, "bookingId"), id(target, "bookingId")));
    source = booking(id(source, "bookingId"));
    target = booking(id(target, "bookingId"));
    requireAllocated(source, "Source");
    requireAllocated(target, "Target");
    ensureNoPriorMergeTransferIn(id(source, "bookingId"));
    ensureNoActiveCancellation(id(source, "bookingId"));
    ensureNoActiveCancellation(id(target, "bookingId"));
    ensureNoMergeConflict(sourcePlotId);
    ensureNoMergeConflict(targetPlotId);

    Long actor = requireUser(username);
    Map<String, Object> snapshot = mergeSnapshot(source, target, false);
    jdbc.update("""
        INSERT INTO webv9_plot_merges
          (target_plot_id, source_plot_id, source_booking_id, target_booking_id,
           source_member_id, target_member_id, source_plot_no, target_plot_no,
           merge_date, status, notes, submitted_by, submitted_at, request_version, request_payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, CURRENT_TIMESTAMP, 1, ?)
        """, targetPlotId, sourcePlotId, id(source, "bookingId"), id(target, "bookingId"),
        id(source, "memberId"), id(target, "memberId"), source.get("plotNo"), target.get("plotNo"),
        date, clean(notes, 500), actor, json(snapshot));
    Long mergeId = lastId();
    event(mergeId, null, 1L, "SUBMITTED", null, "PENDING", actor, null, notes, snapshot);
    audit(username, "SUBMIT_MERGE", "mergeId=" + mergeId + "; source=" + source.get("plotNo")
        + "; target=" + target.get("plotNo"));
    return Map.of("mergeId", mergeId, "status", "PENDING", "financialPosition", snapshot.get("financialPosition"));
  }

  @Transactional
  public Map<String, Object> approveMerge(Long mergeId, List<MergeTransferMapping> mappings,
                                          String remarks, String username) {
    requireDecisionRemarks(remarks);
    Map<String, Object> request = lockMerge(mergeId);
    requirePending(request);
    Long sourcePlotId = nullableId(request.get("source_plot_id"));
    Long targetPlotId = nullableId(request.get("target_plot_id"));
    if (sourcePlotId == null || targetPlotId == null) conflict("Merge request is missing its source or target plot.");
    lockPlots(List.of(sourcePlotId, targetPlotId));
    Map<String, Object> source = booking(nullableId(request.get("source_booking_id")));
    Map<String, Object> target = booking(nullableId(request.get("target_booking_id")));
    lockBookings(List.of(id(source, "bookingId"), id(target, "bookingId")));
    source = booking(id(source, "bookingId"));
    target = booking(id(target, "bookingId"));
    requireAllocated(source, "Source");
    requireAllocated(target, "Target");
    requireIdentity(request.get("source_plot_no"), source.get("plotNo"), "Source");
    requireIdentity(request.get("target_plot_no"), target.get("plotNo"), "Target");
    if (!Objects.equals(id(source, "memberId"), id(target, "memberId")))
      conflict("Cross-member merge financial transfers require a separately approved ownership transfer.");
    ensureNoOtherPendingMerge(mergeId, sourcePlotId, targetPlotId);
    ensureNoActiveCancellation(id(source, "bookingId"));
    ensureNoActiveCancellation(id(target, "bookingId"));
    ensureNoPriorMergeTransferIn(id(source, "bookingId"));

    List<Map<String, Object>> posted = postedApplications(id(source, "bookingId"));
    ensureNoUnpostedReservations(id(source, "bookingId"), id(target, "bookingId"));
    ensurePostedAmountsReconciled(id(source, "bookingId"));
    ensurePostedAmountsReconciled(id(target, "bookingId"));
    Map<Long, Long> targetReceivables = validateTransferMappings(posted, mappings, target);
    BigDecimal total = BigDecimal.ZERO.setScale(2);
    Long actor = requireUser(username);

    jdbc.update("""
        INSERT INTO webv9_sales_merge_transfers
          (merge_id, source_booking_id, target_booking_id, source_plot_id, target_plot_id,
           source_plot_no, target_plot_no, total_amount, approved_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)
        """, mergeId, id(source, "bookingId"), id(target, "bookingId"), sourcePlotId, targetPlotId,
        source.get("plotNo"), target.get("plotNo"), actor);
    Long transferId = lastId();
    for (Map<String, Object> application : posted) {
      Long applicationId = id(application, "applicationId");
      Long sourceReceivableId = id(application, "receivableId");
      Long targetReceivableId = targetReceivables.get(applicationId);
      BigDecimal amount = money(application.get("appliedAmount"));
      Map<String, Object> sourceReceivable = receivableForUpdate(sourceReceivableId);
      Map<String, Object> targetReceivable = receivableForUpdate(targetReceivableId);
      if (!Objects.equals(id(sourceReceivable, "bookingId"), id(source, "bookingId"))
          || !Objects.equals(id(targetReceivable, "bookingId"), id(target, "bookingId")))
        conflict("A transfer receivable no longer belongs to the reviewed booking.");
      if (!Objects.equals(id(sourceReceivable, "chargeTypeId"), id(targetReceivable, "chargeTypeId")))
        conflict("Source and target receivables must have the same charge type.");
      updateReceivablePaid(sourceReceivable, amount.negate());
      updateReceivablePaid(targetReceivable, amount);
      jdbc.update("""
          INSERT INTO webv9_sales_merge_transfer_lines
            (transfer_id, payment_id, allocation_id, application_id,
             source_receivable_id, target_receivable_id, amount)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          """, transferId, id(application, "paymentId"), id(application, "allocationId"),
          applicationId, sourceReceivableId, targetReceivableId, amount);
      jdbc.update("""
          INSERT INTO webv9_customer_ledger
            (member_id, booking_id, transaction_type, reference_no, debit, credit, description, created_by)
          VALUES (?, ?, 'SALES_MERGE_TRANSFER', ?, ?, 0, ?, ?),
                 (?, ?, 'SALES_MERGE_TRANSFER', ?, 0, ?, ?, ?)
          """, id(source, "memberId"), id(source, "bookingId"), "SM-" + mergeId, amount,
          "Merge " + mergeId + " transfer out from " + source.get("plotNo"), actor,
          id(target, "memberId"), id(target, "bookingId"), "SM-" + mergeId, amount,
          "Merge " + mergeId + " transfer in to " + target.get("plotNo"), actor);
      total = total.add(amount);
    }
    jdbc.update("UPDATE webv9_sales_merge_transfers SET total_amount = ? WHERE id = ?", total, transferId);
    Long oldVersion = requestVersion(request);
    int updated = jdbc.update("""
        UPDATE webv9_plot_merges
        SET status = 'APPROVED', approved_by = ?, decision_by = ?, decision_at = CURRENT_TIMESTAMP,
            decision_remarks = ?, finalized_by = ?, finalized_at = CURRENT_TIMESTAMP,
            request_version = COALESCE(request_version, 1) + 1
        WHERE id = ? AND status = 'PENDING' AND COALESCE(request_version, 1) = ?
        """, actor, actor, clean(remarks, 1000), actor, mergeId, oldVersion);
    if (updated != 1) conflict("Merge request changed during approval.");
    int bookingUpdated = jdbc.update("""
        UPDATE sales_bookings SET sale_status = 'MERGED'
        WHERE sale_booking_id = ? AND sale_status = 'ALLOCATED'
        """, id(source, "bookingId"));
    if (bookingUpdated != 1) conflict("Source booking changed during merge finalization.");
    jdbc.update("UPDATE plots SET status = 'AVAILABLE' WHERE plot_id = ?", sourcePlotId);
    Map<String, Object> finalizedSource = new LinkedHashMap<>(source);
    finalizedSource.put("status", "MERGED");
    Map<String, Object> snapshot = mergeSnapshot(finalizedSource, target, true);
    snapshot.put("transferId", transferId);
    snapshot.put("transferAmount", total);
    event(mergeId, null, oldVersion + 1, "APPROVED", "PENDING", "APPROVED", actor, null, remarks, snapshot);
    event(mergeId, null, oldVersion + 1, "FINALIZED", "APPROVED", "APPROVED", actor, null, remarks, snapshot);
    audit(username, "APPROVE_MERGE", "mergeId=" + mergeId + "; transferId=" + transferId
        + "; sourcePlot=" + source.get("plotNo") + "; targetPlot=" + target.get("plotNo")
        + "; amount=" + total);
    return Map.of("mergeId", mergeId, "transferId", transferId, "transferredAmount", total, "status", "APPROVED");
  }

  @Transactional
  public Map<String, Object> rejectMerge(Long id, String remarks, String username) {
    return decideReject(true, id, remarks, username);
  }

  @Transactional
  public Map<String, Object> submitCancellation(Long bookingId, LocalDate date, String reason,
                                                String notes, String username) {
    if (bookingId == null || date == null) bad("Booking and cancellation date are required.");
    Map<String, Object> current = booking(bookingId);
    Long plotId = id(current, "plotId");
    lockPlots(List.of(plotId));
    lockBookings(List.of(bookingId));
    Map<String, Object> source = booking(bookingId);
    requireAllocated(source, "Booking");
    ensureNoActiveCancellation(bookingId);
    ensureNoMergeConflict(plotId);
    Long actor = requireUser(username);
    Map<String, Object> snapshot = cancellationSnapshot(source, false);
    String combinedReason = combine(reason, notes);
    jdbc.update("""
        INSERT INTO webv9_cancellations
          (booking_id, source_plot_id, source_plot_no, cancellation_date, deduction_rate,
           deduction_amount, refund_amount, status, reason, submitted_by, submitted_at,
           request_version, request_payload)
        VALUES (?, ?, ?, ?, 0, 0, 0, 'PENDING', ?, ?, CURRENT_TIMESTAMP, 1, ?)
        """, bookingId, plotId, source.get("plotNo"), date, combinedReason, actor, json(snapshot));
    Long cancellationId = lastId();
    event(null, cancellationId, 1L, "SUBMITTED", null, "PENDING", actor, null, reason, snapshot);
    audit(username, "SUBMIT_CANCELLATION", "cancellationId=" + cancellationId + "; booking="
        + source.get("bookingNo") + "; plot=" + source.get("plotNo"));
    return Map.of("cancellationId", cancellationId, "status", "PENDING",
        "financialPosition", snapshot.get("financialPosition"));
  }

  @Transactional
  public Map<String, Object> approveCancellation(Long cancellationId, List<Long> deductionIds,
                                                 String remarks, String username) {
    requireDecisionRemarks(remarks);
    Map<String, Object> request = lockCancellation(cancellationId);
    requirePending(request);
    Long bookingId = id(request, "booking_id");
    Map<String, Object> source = booking(bookingId);
    Long plotId = id(source, "plotId");
    lockPlots(List.of(plotId));
    lockBookings(List.of(bookingId));
    source = booking(bookingId);
    requireAllocated(source, "Booking");
    requireIdentity(request.get("source_plot_no"), source.get("plotNo"), "Cancellation");
    ensureNoUnpostedReservations(bookingId);
    ensurePostedAmountsReconciled(bookingId);

    Long actor = requireUser(username);
    BigDecimal postedTotal = settledAmount(bookingId);
    List<Map<String, Object>> deductions = validDeductions(deductionIds, source);
    BigDecimal deductionTotal = deductions.stream().map(row -> money(row.get("amount")))
        .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    if (deductionTotal.compareTo(postedTotal) > 0)
      bad("Approved deductions cannot exceed posted payment applications.");
    BigDecimal refund = postedTotal.subtract(deductionTotal).setScale(2, RoundingMode.UNNECESSARY);
    for (Map<String, Object> line : deductions) {
      jdbc.update("""
          INSERT INTO webv9_sales_cancellation_settlement_lines
            (cancellation_id, finance_adjustment_id, receivable_id, amount, description, created_by)
          VALUES (?, ?, ?, ?, ?, ?)
          """, cancellationId, id(line, "adjustmentId"), id(line, "receivableId"),
          line.get("amount"), line.get("description"), actor);
    }
    Long oldVersion = requestVersion(request);
    int updated = jdbc.update("""
        UPDATE webv9_cancellations
        SET status = 'APPROVED', approved_by = ?, decision_by = ?, decision_at = CURRENT_TIMESTAMP,
            decision_remarks = ?, finalized_by = ?, finalized_at = CURRENT_TIMESTAMP,
            deduction_amount = ?, refund_amount = ?, request_version = COALESCE(request_version, 1) + 1
        WHERE id = ? AND status = 'PENDING' AND COALESCE(request_version, 1) = ?
        """, actor, actor, clean(remarks, 1000), actor, deductionTotal, refund, cancellationId, oldVersion);
    if (updated != 1) conflict("Cancellation request changed during approval.");
    int bookingUpdated = jdbc.update("""
        UPDATE sales_bookings SET sale_status = 'CANCELLED'
        WHERE sale_booking_id = ? AND sale_status = 'ALLOCATED'
        """, bookingId);
    if (bookingUpdated != 1) conflict("Booking changed during cancellation finalization.");
    jdbc.update("UPDATE plots SET status = 'AVAILABLE' WHERE plot_id = ?", plotId);
    String refundStatus = refund.signum() == 0 ? "NOT_REQUIRED" : "PENDING_DISBURSEMENT";
    jdbc.update("""
        INSERT INTO webv9_sales_refund_disbursements
          (cancellation_id, member_id, posted_amount, deduction_amount, refund_amount,
           status, approved_by, remarks)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, cancellationId, id(source, "memberId"), postedTotal, deductionTotal, refund,
        refundStatus, actor, clean(remarks, 1000));
    Map<String, Object> finalizedSource = new LinkedHashMap<>(source);
    finalizedSource.put("status", "CANCELLED");
    Map<String, Object> snapshot = cancellationSnapshot(finalizedSource, true);
    snapshot.put("postedAmount", postedTotal);
    snapshot.put("deductionAmount", deductionTotal);
    snapshot.put("refundAmount", refund);
    event(null, cancellationId, oldVersion + 1, "APPROVED", "PENDING", "APPROVED", actor, null, remarks, snapshot);
    event(null, cancellationId, oldVersion + 1, "FINALIZED", "APPROVED", "APPROVED", actor, null, remarks, snapshot);
    audit(username, "APPROVE_CANCELLATION", "cancellationId=" + cancellationId + "; posted="
        + postedTotal + "; deductions=" + deductionTotal + "; refund=" + refund);
    return Map.of("cancellationId", cancellationId, "status", "APPROVED",
        "postedAmount", postedTotal, "deductionAmount", deductionTotal,
        "refundAmount", refund, "refundStatus", refundStatus);
  }

  @Transactional
  public Map<String, Object> rejectCancellation(Long id, String remarks, String username) {
    return decideReject(false, id, remarks, username);
  }

  @Transactional
  public Map<String, Object> completeRefund(Long cancellationId, String payoutReference,
                                            String remarks, String username) {
    if (payoutReference == null || payoutReference.isBlank()) bad("Refund payout reference is required.");
    Long actor = requireUser(username);
    Map<String, Object> request = lockCancellation(cancellationId);
    if (!"APPROVED".equalsIgnoreCase(String.valueOf(request.get("status"))))
      conflict("Only an approved cancellation can be disbursed.");
    List<Map<String, Object>> cases = jdbc.queryForList("""
        SELECT id, status FROM webv9_sales_refund_disbursements
        WHERE cancellation_id = ? FOR UPDATE
        """, cancellationId);
    if (cases.isEmpty()) notFound("Refund case not found.");
    if (!"PENDING_DISBURSEMENT".equalsIgnoreCase(String.valueOf(cases.get(0).get("status"))))
      conflict("Refund case is not pending disbursement.");
    int count = jdbc.update("""
        UPDATE webv9_sales_refund_disbursements
        SET status = 'DISBURSED', payout_reference = ?, completed_by = ?,
            completed_at = CURRENT_TIMESTAMP, remarks = ?
        WHERE cancellation_id = ? AND status = 'PENDING_DISBURSEMENT'
        """, clean(payoutReference, 120), actor, clean(remarks, 1000), cancellationId);
    if (count != 1) conflict("Refund status changed during disbursement.");
    Map<String, Object> snapshot = new LinkedHashMap<>(request);
    snapshot.put("payoutReference", clean(payoutReference, 120));
    event(null, cancellationId, requestVersion(request), "REFUND_DISBURSED",
        "APPROVED", "APPROVED", actor, null, remarks, snapshot);
    audit(username, "DISBURSE_SALES_REFUND", "cancellationId=" + cancellationId
        + "; payoutReference=" + clean(payoutReference, 120));
    return Map.of("cancellationId", cancellationId, "status", "DISBURSED",
        "payoutReference", clean(payoutReference, 120));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> mergeDetail(Long mergeId) {
    Map<String, Object> request = readMerge(mergeId);
    Map<String, Object> result = new LinkedHashMap<>(request);
    result.put("events", repository.mergeEvents(mergeId));
    result.put("transfers", repository.mergeTransfers(mergeId));
    result.put("financialPosition", mergeSnapshotByRequest(request));
    result.put("inventoryOutcome", inventoryOutcome(
        nullableId(request.get("source_plot_id")), nullableId(request.get("target_plot_id"))));
    return result;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> cancellationDetail(Long id) {
    Map<String, Object> request = readCancellation(id);
    Map<String, Object> result = new LinkedHashMap<>(request);
    result.put("events", repository.cancellationEvents(id));
    result.put("settlementLines", repository.cancellationLines(id));
    result.put("refund", repository.refund(id));
    result.put("financialPosition", financialPosition(id(request, "booking_id")));
    result.put("inventoryOutcome", inventoryOutcome(nullableId(request.get("source_plot_id")), null));
    return result;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> financialPosition(Long bookingId) {
    Map<String, Object> booking = booking(bookingId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("booking", booking);
    result.put("postedApplications", postedApplications(bookingId));
    result.put("salesTransfersIn", jdbc.queryForList("""
        SELECT h.id AS transferId, h.merge_id AS mergeId, h.source_booking_id AS sourceBookingId,
               h.source_plot_no AS sourcePlotNo, l.application_id AS applicationId,
               l.payment_id AS paymentId, l.source_receivable_id AS sourceReceivableId,
               l.target_receivable_id AS receivableId, l.amount
        FROM webv9_sales_merge_transfers h
        JOIN webv9_sales_merge_transfer_lines l ON l.transfer_id = h.id
        WHERE h.target_booking_id = ? ORDER BY h.id, l.id
        """, bookingId));
    result.put("salesTransfersOut", jdbc.queryForList("""
        SELECT h.id AS transferId, h.merge_id AS mergeId, h.target_booking_id AS targetBookingId,
               h.target_plot_no AS targetPlotNo, l.application_id AS applicationId,
               l.payment_id AS paymentId, l.source_receivable_id AS receivableId,
               l.target_receivable_id AS targetReceivableId, l.amount
        FROM webv9_sales_merge_transfers h
        JOIN webv9_sales_merge_transfer_lines l ON l.transfer_id = h.id
        WHERE h.source_booking_id = ? ORDER BY h.id, l.id
        """, bookingId));
    result.put("receivables", jdbc.queryForList("""
        SELECT vr.id AS receivableId, vr.booking_id AS bookingId, vr.plot_id AS plotId,
               vr.charge_type_id AS chargeTypeId, ct.name AS chargeName,
               vr.due_date AS dueDate, vr.original_amount AS originalAmount,
               vr.paid_amount AS paidAmount, vr.surcharge_amount AS surchargeAmount,
               vr.adjustment_amount AS adjustmentAmount,
               (vr.original_amount + vr.surcharge_amount - vr.adjustment_amount - vr.paid_amount) AS outstanding
        FROM webv9_receivables vr LEFT JOIN webv9_charge_types ct ON ct.id = vr.charge_type_id
        WHERE vr.booking_id = ? ORDER BY vr.due_date, vr.id
        """, bookingId));
    result.put("activeReservations", jdbc.queryForList("""
        SELECT a.id AS allocationId, a.payment_id AS paymentId, p.receipt_no AS receiptNo,
               p.status AS paymentStatus, a.receivable_id AS receivableId,
               a.allocated_amount AS reservedAmount
        FROM webv9_payment_allocations a JOIN payments_receipts p ON p.id = a.payment_id
        WHERE a.booking_id = ? AND p.status IN ('RECOVERY_ENTRY','PENDING','PENDING_VERIFICATION','VERIFIED')
        ORDER BY p.id, a.line_no
        """, bookingId));
    result.put("eligibleDeductions", jdbc.queryForList("""
        SELECT fa.id AS adjustmentId, fa.receivable_id AS receivableId,
               fa.amount, fa.reason AS description
        FROM webv9_finance_adjustments fa
        JOIN webv9_receivables vr ON vr.id = fa.receivable_id AND vr.booking_id = ?
        WHERE fa.status = 'APPROVED' AND UPPER(fa.direction) = 'DEBIT'
          AND NOT EXISTS (
            SELECT 1 FROM webv9_sales_cancellation_settlement_lines sl
            WHERE sl.finance_adjustment_id = fa.id
          )
        ORDER BY fa.id
        """, bookingId));
    result.put("postedAmount", settledAmount(bookingId));
    return result;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> listMergeCandidates() {
    return jdbc.queryForList("""
        SELECT p.plot_id AS plotId, b.project_id AS projectId, p.plot_no AS plotNo, b.block_name AS blockName,
               sb.sale_booking_id AS bookingId, CONCAT('SB-', LPAD(sb.sale_booking_id, 6, '0')) AS bookingNo,
               r.registration_id AS registrationId, r.reg_no AS registrationNo,
               m.member_id AS memberId, m.mbr_no AS memberNo, m.name AS memberName
        FROM plots p JOIN blocks b ON b.block_id = p.block_id
        JOIN sales_bookings sb ON sb.plot_id = p.plot_id
          AND UPPER(COALESCE(sb.sale_status, '')) = 'ALLOCATED'
        JOIN registrations r ON r.registration_id = sb.registration_id
        JOIN members m ON m.member_id = r.member_id
        WHERE sb.sale_booking_id = (
          SELECT MAX(latest.sale_booking_id) FROM sales_bookings latest WHERE latest.plot_id = p.plot_id
        )
        ORDER BY p.plot_no
        """);
  }

  private Map<String, Object> decideReject(boolean merge, Long requestId, String remarks, String username) {
    if (remarks == null || remarks.isBlank()) bad("Decision remarks are required for rejection.");
    Map<String, Object> request = merge ? lockMerge(requestId) : lockCancellation(requestId);
    requirePending(request);
    Long actor = requireUser(username);
    Long version = requestVersion(request);
    String table = merge ? "webv9_plot_merges" : "webv9_cancellations";
    int changed = jdbc.update("UPDATE " + table + """
        SET status = 'REJECTED', decision_by = ?, decision_at = CURRENT_TIMESTAMP,
            decision_remarks = ?, request_version = COALESCE(request_version, 1) + 1
        WHERE id = ? AND status = 'PENDING' AND COALESCE(request_version, 1) = ?
        """, actor, clean(remarks, 1000), requestId, version);
    if (changed != 1) conflict("Request changed before rejection.");
    Map<String, Object> snapshot = new LinkedHashMap<>(request);
    event(merge ? requestId : null, merge ? null : requestId, version + 1,
        "REJECTED", "PENDING", "REJECTED", actor, null, remarks, snapshot);
    audit(username, merge ? "REJECT_MERGE" : "REJECT_CANCELLATION",
        "requestId=" + requestId + "; remarks=" + clean(remarks, 1000));
    return Map.of("requestId", requestId, "status", "REJECTED");
  }

  private Map<String, Object> mergeSnapshot(Map<String, Object> source, Map<String, Object> target, boolean includePosition) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("source", source);
    snapshot.put("target", target);
    snapshot.put("sourcePlotId", source.get("plotId"));
    snapshot.put("sourcePlotNo", source.get("plotNo"));
    snapshot.put("targetPlotId", target.get("plotId"));
    snapshot.put("targetPlotNo", target.get("plotNo"));
    snapshot.put("financialPosition", includePosition
        ? Map.of("source", financialPosition(id(source, "bookingId")),
            "target", financialPosition(id(target, "bookingId")))
        : Map.of("source", financialPosition(id(source, "bookingId")),
            "target", financialPosition(id(target, "bookingId"))));
    return snapshot;
  }

  private Object mergeSnapshotByRequest(Map<String, Object> request) {
    Long sourceBooking = nullableId(request.get("source_booking_id"));
    Long targetBooking = nullableId(request.get("target_booking_id"));
    if (sourceBooking == null || targetBooking == null) return Map.of();
    return Map.of("source", financialPosition(sourceBooking), "target", financialPosition(targetBooking));
  }

  private Map<String, Object> cancellationSnapshot(Map<String, Object> source, boolean finalPosition) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("bookingId", source.get("bookingId"));
    snapshot.put("plotId", source.get("plotId"));
    snapshot.put("plotNo", source.get("plotNo"));
    snapshot.put("memberId", source.get("memberId"));
    snapshot.put("financialPosition", financialPosition(id(source, "bookingId")));
    snapshot.put("finalPosition", finalPosition);
    return snapshot;
  }

  private List<Map<String, Object>> postedApplications(Long bookingId) {
    return jdbc.queryForList("""
        SELECT pra.id AS applicationId, a.id AS allocationId, p.id AS paymentId,
               p.receipt_no AS receiptNo, p.status AS paymentStatus,
               a.booking_id AS bookingId, a.plot_id AS plotId, a.registration_id AS registrationId,
               a.charge_type_id AS chargeTypeId, a.installment_schedule_id AS scheduleId,
               pra.receivable_id AS receivableId, pra.applied_amount AS appliedAmount,
               p.payment_date AS paymentDate, vr.due_date AS dueDate
        FROM webv9_payment_receivable_applications pra
        JOIN webv9_payment_allocations a ON a.id = pra.allocation_id
        JOIN payments_receipts p ON p.id = a.payment_id AND p.status = 'POSTED'
        JOIN webv9_receivables vr ON vr.id = pra.receivable_id
        WHERE a.booking_id = ? ORDER BY p.id, a.line_no, pra.id
        """, bookingId);
  }

  private Map<Long, Long> validateTransferMappings(List<Map<String, Object>> applications,
                                                    List<MergeTransferMapping> mappings,
                                                    Map<String, Object> target) {
    if (applications.isEmpty()) return Map.of();
    if (mappings == null || mappings.size() != applications.size())
      bad("A target receivable mapping is required for every posted payment application.");
    Map<Long, Long> result = new HashMap<>();
    for (MergeTransferMapping mapping : mappings) {
      if (mapping == null || mapping.applicationId() == null || mapping.targetReceivableId() == null
          || result.putIfAbsent(mapping.applicationId(), mapping.targetReceivableId()) != null)
        bad("Payment application mappings must be complete and unique.");
    }
    for (Map<String, Object> application : applications) {
      Long applicationId = id(application, "applicationId");
      Long targetReceivableId = result.get(applicationId);
      if (targetReceivableId == null) bad("A posted payment application is missing a target receivable.");
      Map<String, Object> destination = receivableForUpdate(targetReceivableId);
      if (!Objects.equals(id(destination, "bookingId"), id(target, "bookingId"))
          || !Objects.equals(id(destination, "plotId"), id(target, "plotId"))
          || !Objects.equals(id(destination, "memberId"), id(target, "memberId"))
          || !Objects.equals(id(destination, "chargeTypeId"), id(application, "chargeTypeId")))
        bad("Target receivable does not match the target booking, plot, member and charge type.");
    }
    return result;
  }

  private List<Map<String, Object>> validDeductions(List<Long> deductionIds, Map<String, Object> booking) {
    if (deductionIds == null || deductionIds.isEmpty()) return List.of();
    List<Long> ids = deductionIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
    if (ids.size() != deductionIds.size()) bad("Deduction references must be unique.");
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    List<Object> params = new ArrayList<>(ids);
    params.add(id(booking, "bookingId"));
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT fa.id AS adjustmentId, fa.receivable_id AS receivableId,
               fa.amount, fa.reason AS description
        FROM webv9_finance_adjustments fa
        JOIN webv9_receivables vr ON vr.id = fa.receivable_id
        WHERE fa.id IN (%s) AND fa.status = 'APPROVED'
          AND UPPER(fa.direction) = 'DEBIT' AND vr.booking_id = ?
          AND NOT EXISTS (
            SELECT 1 FROM webv9_sales_cancellation_settlement_lines sl
            WHERE sl.finance_adjustment_id = fa.id
          )
        ORDER BY fa.id
        """.formatted(placeholders), params.toArray());
    if (rows.size() != ids.size()) bad("Each deduction must reference an unused approved debit adjustment for this booking.");
    return rows;
  }

  private void ensureNoUnpostedReservations(Long... bookingIds) {
    String placeholders = String.join(",", Collections.nCopies(bookingIds.length, "?"));
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*) FROM webv9_payment_allocations a
        JOIN payments_receipts p ON p.id = a.payment_id
        WHERE a.booking_id IN (%s)
          AND p.status IN ('RECOVERY_ENTRY','PENDING','PENDING_VERIFICATION','VERIFIED')
        """.formatted(placeholders), Integer.class, (Object[]) bookingIds);
    if (count != null && count > 0)
      conflict("Unposted Finance reservations exist. Resolve them through Finance before finalizing this Sales request.");
  }

  private void ensureNoPriorMergeTransferIn(Long bookingId) {
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*) FROM webv9_sales_merge_transfers
        WHERE target_booking_id = ?
        """, Integer.class, bookingId);
    if (count != null && count > 0)
      conflict("A booking with prior Sales merge receipts cannot be reused as a merge source.");
  }

  private BigDecimal settledAmount(Long bookingId) {
    BigDecimal amount = jdbc.queryForObject("""
        SELECT COALESCE((
          SELECT SUM(pra.applied_amount)
          FROM webv9_payment_receivable_applications pra
          JOIN webv9_payment_allocations a ON a.id = pra.allocation_id
          JOIN payments_receipts p ON p.id = a.payment_id AND p.status = 'POSTED'
          WHERE a.booking_id = ?
        ), 0)
        + COALESCE((
          SELECT SUM(l.amount)
          FROM webv9_sales_merge_transfers h
          JOIN webv9_sales_merge_transfer_lines l ON l.transfer_id = h.id
          WHERE h.target_booking_id = ?
        ), 0)
        - COALESCE((
          SELECT SUM(l.amount)
          FROM webv9_sales_merge_transfers h
          JOIN webv9_sales_merge_transfer_lines l ON l.transfer_id = h.id
          WHERE h.source_booking_id = ?
        ), 0)
        """, BigDecimal.class, bookingId, bookingId, bookingId);
    return amount == null ? BigDecimal.ZERO.setScale(2) : amount.setScale(2, RoundingMode.UNNECESSARY);
  }

  private void ensurePostedAmountsReconciled(Long bookingId) {
    Integer mismatchedReceivables = jdbc.queryForObject("""
        SELECT COUNT(*)
        FROM webv9_receivables vr
        LEFT JOIN (
          SELECT pra.receivable_id, SUM(pra.applied_amount) AS posted_amount
          FROM webv9_payment_receivable_applications pra
          JOIN webv9_payment_allocations a ON a.id = pra.allocation_id
          JOIN payments_receipts p ON p.id = a.payment_id AND p.status = 'POSTED'
          WHERE a.booking_id = ?
          GROUP BY pra.receivable_id
        ) applied ON applied.receivable_id = vr.id
        LEFT JOIN (
          SELECT receivable_id, SUM(amount) AS transfer_delta
          FROM (
            SELECT l.target_receivable_id AS receivable_id, l.amount
            FROM webv9_sales_merge_transfer_lines l
            UNION ALL
            SELECT l.source_receivable_id AS receivable_id, -l.amount AS amount
            FROM webv9_sales_merge_transfer_lines l
          ) movement
          GROUP BY receivable_id
        ) transferred ON transferred.receivable_id = vr.id
        WHERE vr.booking_id = ?
          AND vr.paid_amount <> COALESCE(applied.posted_amount, 0) + COALESCE(transferred.transfer_delta, 0)
        """, Integer.class, bookingId, bookingId);
    Integer unappliedPostedAllocations = jdbc.queryForObject("""
        SELECT COUNT(*)
        FROM webv9_payment_allocations a
        JOIN payments_receipts p ON p.id = a.payment_id AND p.status = 'POSTED'
        LEFT JOIN webv9_payment_receivable_applications pra ON pra.allocation_id = a.id
        WHERE a.booking_id = ? AND pra.id IS NULL AND a.allocated_amount <> 0
        """, Integer.class, bookingId);
    if ((mismatchedReceivables != null && mismatchedReceivables > 0)
        || (unappliedPostedAllocations != null && unappliedPostedAllocations > 0))
      conflict("Posted allocations and receivable balances do not reconcile; resolve the financial discrepancy before finalization.");
  }

  private Map<String, Object> receivableForUpdate(Long receivableId) {
    try {
      return jdbc.queryForMap("""
          SELECT id, member_id AS memberId, booking_id AS bookingId, plot_id AS plotId,
                 charge_type_id AS chargeTypeId, original_amount AS originalAmount,
                 paid_amount AS paidAmount, surcharge_amount AS surchargeAmount,
                 adjustment_amount AS adjustmentAmount
          FROM webv9_receivables WHERE id = ? FOR UPDATE
          """, receivableId);
    } catch (EmptyResultDataAccessException ex) {
      notFound("Receivable not found.");
      throw new IllegalStateException("unreachable");
    }
  }

  private void updateReceivablePaid(Map<String, Object> receivable, BigDecimal delta) {
    BigDecimal current = money(receivable.get("paidAmount"));
    BigDecimal next = current.add(delta);
    if (next.signum() < 0) conflict("Source receivable no longer has the amount approved for transfer.");
    BigDecimal due = money(receivable.get("originalAmount"))
        .add(money(receivable.get("surchargeAmount")))
        .subtract(money(receivable.get("adjustmentAmount")));
    if (next.compareTo(due) > 0) conflict("Target receivable would be overpaid by the proposed transfer.");
    String status = next.signum() == 0 ? "DUE" : next.compareTo(due) >= 0 ? "PAID" : "PARTIAL";
    int changed = jdbc.update("""
        UPDATE webv9_receivables SET paid_amount = ?, status = ? WHERE id = ? AND paid_amount = ?
        """, next, status, id(receivable, "id"), current);
    if (changed != 1) conflict("Receivable changed during merge finalization.");
  }

  private Map<String, Object> activeBookingForPlot(Long plotId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT sb.sale_booking_id AS bookingId, sb.sale_status AS status,
               sb.registration_id AS registrationId, r.member_id AS memberId,
               p.plot_id AS plotId, p.plot_no AS plotNo
        FROM sales_bookings sb
        JOIN registrations r ON r.registration_id = sb.registration_id
        JOIN plots p ON p.plot_id = sb.plot_id
        WHERE sb.plot_id = ? ORDER BY sb.sale_booking_id DESC LIMIT 1
        """, plotId);
    if (rows.isEmpty()) conflict("Source and target plots must both have current allocated bookings.");
    requireAllocated(rows.get(0), "Plot");
    return rows.get(0);
  }

  private Map<String, Object> booking(Long bookingId) {
    try {
      return jdbc.queryForMap("""
          SELECT sb.sale_booking_id AS bookingId, sb.sale_status AS status,
                 sb.registration_id AS registrationId, r.member_id AS memberId,
                 p.plot_id AS plotId, p.plot_no AS plotNo
          FROM sales_bookings sb
          JOIN registrations r ON r.registration_id = sb.registration_id
          JOIN plots p ON p.plot_id = sb.plot_id
          WHERE sb.sale_booking_id = ?
          """, bookingId);
    } catch (EmptyResultDataAccessException ex) {
      notFound("Sales booking or its historical plot identity was not found.");
      throw new IllegalStateException("unreachable");
    }
  }

  private Map<String, Object> inventoryOutcome(Long sourcePlot, Long targetPlot) {
    Map<String, Object> outcome = new LinkedHashMap<>();
    if (sourcePlot != null) outcome.put("source", inventoryPlot(sourcePlot));
    if (targetPlot != null) outcome.put("target", inventoryPlot(targetPlot));
    return outcome;
  }

  private Map<String, Object> inventoryPlot(Long plotId) {
    return jdbc.queryForMap("""
        SELECT p.plot_id AS plotId, p.plot_no AS plotNo, p.status AS plotStatus,
               sb.sale_booking_id AS bookingId, sb.sale_status AS bookingStatus
        FROM plots p LEFT JOIN sales_bookings sb
          ON sb.sale_booking_id = (SELECT MAX(x.sale_booking_id) FROM sales_bookings x WHERE x.plot_id = p.plot_id)
        WHERE p.plot_id = ?
        """, plotId);
  }

  private void ensureNoMergeConflict(Long plotId) {
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*) FROM webv9_plot_merges
        WHERE UPPER(status) = 'PENDING' AND (source_plot_id = ? OR target_plot_id = ?)
        """, Integer.class, plotId, plotId);
    Integer approvedSource = jdbc.queryForObject("""
        SELECT COUNT(*) FROM webv9_plot_merges
        WHERE source_plot_id = ? AND UPPER(status) = 'APPROVED'
        """, Integer.class, plotId);
    if ((count != null && count > 0) || (approvedSource != null && approvedSource > 0))
      conflict("This plot already has a pending or finalized merge request.");
  }

  private void ensureNoOtherPendingMerge(Long requestId, Long sourcePlot, Long targetPlot) {
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*) FROM webv9_plot_merges
        WHERE id <> ? AND UPPER(status) = 'PENDING'
          AND (source_plot_id IN (?, ?) OR target_plot_id IN (?, ?))
        """, Integer.class, requestId, sourcePlot, targetPlot, sourcePlot, targetPlot);
    if (count != null && count > 0) conflict("A conflicting merge request is pending for one of these plots.");
  }

  private void ensureNoActiveCancellation(Long bookingId) {
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*) FROM webv9_cancellations
        WHERE booking_id = ? AND UPPER(status) IN ('PENDING','APPROVED')
        """, Integer.class, bookingId);
    if (count != null && count > 0) conflict("This booking has a pending or finalized cancellation.");
  }

  private Map<String, Object> readMerge(Long id) {
    try {
      return jdbc.queryForMap("""
          SELECT pm.*, sp.plot_no AS sourcePlotIdentity, tp.plot_no AS targetPlotIdentity
          FROM webv9_plot_merges pm
          LEFT JOIN plots sp ON sp.plot_id = pm.source_plot_id
          LEFT JOIN plots tp ON tp.plot_id = pm.target_plot_id
          WHERE pm.id = ?
          """, id);
    } catch (EmptyResultDataAccessException ex) {
      notFound("Merge request not found.");
      throw new IllegalStateException("unreachable");
    }
  }

  private Map<String, Object> readCancellation(Long id) {
    try {
      return jdbc.queryForMap("""
          SELECT c.*, p.plot_no AS sourcePlotIdentity
          FROM webv9_cancellations c
          LEFT JOIN plots p ON p.plot_id = COALESCE(c.source_plot_id,
              (SELECT sb.plot_id FROM sales_bookings sb WHERE sb.sale_booking_id = c.booking_id))
          WHERE c.id = ?
          """, id);
    } catch (EmptyResultDataAccessException ex) {
      notFound("Cancellation request not found.");
      throw new IllegalStateException("unreachable");
    }
  }

  private Map<String, Object> lockMerge(Long id) {
    try { return repository.lockMerge(id); }
    catch (EmptyResultDataAccessException ex) { notFound("Merge request not found."); throw new IllegalStateException("unreachable"); }
  }

  private Map<String, Object> lockCancellation(Long id) {
    try { return repository.lockCancellation(id); }
    catch (EmptyResultDataAccessException ex) { notFound("Cancellation request not found."); throw new IllegalStateException("unreachable"); }
  }

  private void requirePending(Map<String, Object> request) {
    if (!"PENDING".equalsIgnoreCase(String.valueOf(request.get("status"))))
      conflict("Only a pending request can be approved or rejected.");
  }

  private void requireAllocated(Map<String, Object> booking, String role) {
    if (!"ALLOCATED".equalsIgnoreCase(String.valueOf(booking.get("status"))))
      conflict(role + " booking must be current and allocated.");
  }

  private void requireIdentity(Object snapshot, Object current, String role) {
    if (snapshot != null && current != null && !snapshot.toString().equals(current.toString()))
      conflict(role + " DOL plot identity changed since submission.");
  }

  private void lockPlots(List<Long> ids) {
    List<Long> ordered = ids.stream().distinct().sorted().toList();
    if (ordered.size() != ids.stream().distinct().count()) bad("Plot references must be distinct.");
    String placeholders = String.join(",", Collections.nCopies(ordered.size(), "?"));
    List<Long> locked = jdbc.query("SELECT plot_id FROM plots WHERE plot_id IN (" + placeholders
            + ") ORDER BY plot_id FOR UPDATE",
        (rs, row) -> rs.getLong(1), ordered.toArray());
    if (locked.size() != ordered.size()) notFound("One or more plots were not found.");
  }

  private void lockBookings(List<Long> ids) {
    List<Long> ordered = ids.stream().distinct().sorted().toList();
    String placeholders = String.join(",", Collections.nCopies(ordered.size(), "?"));
    List<Long> locked = jdbc.query("SELECT sale_booking_id FROM sales_bookings WHERE sale_booking_id IN ("
            + placeholders + ") ORDER BY sale_booking_id FOR UPDATE",
        (rs, row) -> rs.getLong(1), ordered.toArray());
    if (locked.size() != ordered.size()) conflict("A booking changed during validation.");
  }

  private void event(Long mergeId, Long cancellationId, Long version, String type, String from, String to,
                     Long actor, Long target, String remarks, Object snapshot) {
    String key = (mergeId == null ? "C" + cancellationId : "M" + mergeId) + ":" + version + ":" + type;
    jdbc.update("""
        INSERT INTO webv9_sales_request_events
          (merge_id, cancellation_id, request_version, event_type, from_status, to_status,
           actor_user_id, target_user_id, remarks, request_snapshot, event_key)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, mergeId, cancellationId, version, type, from, to, actor, target,
        clean(remarks, 1000), json(snapshot), key);
  }

  private Long requireUser(String username) {
    if (username == null || username.isBlank()) bad("Authenticated WEBV9 user is required.");
    try {
      return jdbc.queryForObject("SELECT id FROM users WHERE username = ? AND is_active = 1", Long.class, username);
    } catch (EmptyResultDataAccessException ex) {
      bad("Authenticated WEBV9 user is not active.");
      throw new IllegalStateException("unreachable");
    }
  }

  private Long lastId() { return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class); }

  private String json(Object value) {
    try { return objectMapper.writeValueAsString(value); }
    catch (JsonProcessingException ex) { throw new IllegalStateException("Could not encode Sales workflow snapshot.", ex); }
  }

  private void audit(String username, String action, String details) {
    jdbc.update("INSERT INTO system_logs(username, action, module, details) VALUES (?, ?, 'SALES', ?)",
        username, action, details.length() > 500 ? details.substring(0, 500) : details);
  }

  private static Long id(Map<String, Object> row, String key) {
    Long id = nullableId(row.get(key));
    if (id == null) throw new IllegalStateException("Missing required workflow identity: " + key);
    return id;
  }

  private static Long nullableId(Object value) { return value instanceof Number number ? number.longValue() : null; }
  private static Long requestVersion(Map<String, Object> request) {
    Long version = nullableId(request.get("request_version"));
    return version == null ? 1L : version;
  }
  private static BigDecimal money(Object value) {
    if (value instanceof BigDecimal amount) return amount;
    if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue()).setScale(2, RoundingMode.UNNECESSARY);
    throw new IllegalStateException("A required Sales financial amount is missing or invalid.");
  }
  private static void requireDecisionRemarks(String remarks) {
    if (remarks == null || remarks.isBlank()) bad("Decision remarks are required.");
    clean(remarks, 1000);
  }
  private static String clean(String value, int max) {
    if (value == null || value.isBlank()) return null;
    String result = value.trim();
    if (result.length() > max) bad("Text must be " + max + " characters or fewer.");
    return result;
  }
  private static String combine(String reason, String notes) {
    String left = clean(reason, 500), right = clean(notes, 500);
    String result = left == null ? right : right == null ? left : left + "\nRemarks: " + right;
    if (result != null && result.length() > 500) bad("Reason and remarks together must not exceed 500 characters.");
    return result;
  }
  private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
  private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
  private static void notFound(String message) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
}
