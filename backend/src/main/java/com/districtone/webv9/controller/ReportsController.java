package com.districtone.webv9.controller;

import com.districtone.webv9.repo.SalesWorkflowRepository;
import com.districtone.webv9.security.ProjectAccessService;
import com.districtone.webv9.service.PlotInventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportsController {
  private static final int MAX_ROWS = 1000;
  private static final String RECEIVABLE_OUTSTANDING =
      "GREATEST(COALESCE(vr.original_amount, 0) + COALESCE(vr.surcharge_amount, 0) "
          + "- COALESCE(vr.adjustment_amount, 0) - COALESCE(vr.paid_amount, 0), 0)";

  private final JdbcTemplate jdbc;
  private final ProjectAccessService projectAccess;
  private final PlotInventoryService plotInventory;
  private final SalesWorkflowRepository salesWorkflow;

  public ReportsController(JdbcTemplate jdbc, ProjectAccessService projectAccess,
                           PlotInventoryService plotInventory, SalesWorkflowRepository salesWorkflow) {
    this.jdbc = jdbc;
    this.projectAccess = projectAccess;
    this.plotInventory = plotInventory;
    this.salesWorkflow = salesWorkflow;
  }

  @GetMapping("/plots/inventory")
  public Map<String, Object> plotInventory(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String availability,
      @RequestParam(required = false) Long projectId,
      Authentication authentication) {
    List<Long> scope = projectAccess.accessibleProjectIds(authentication.getName());
    if (scope.isEmpty() || projectId != null && !scope.contains(projectId))
      return result(List.of(), Map.of(), false);
    List<Map<String, Object>> visible = new ArrayList<>();
    for (Long scopedProjectId : projectId == null ? scope : List.of(projectId)) {
      visible.addAll(plotInventory.list(q, status, availability, scopedProjectId));
    }
    boolean truncated = visible.size() > MAX_ROWS;
    List<Map<String, Object>> rows = visible.stream().limit(MAX_ROWS).toList();
    return result(rows, Map.of("plotCount", rows.size()), truncated);
  }

  @GetMapping("/sales/merges")
  public Map<String, Object> plotMerges(
      @RequestParam(required = false) String fromDate,
      @RequestParam(required = false) String toDate,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String location,
      @RequestParam(required = false) String customer,
      Authentication authentication) {
    List<Long> scope = scopedProjectFilter(authentication.getName(), projectId);
    if (scope.isEmpty()) return result(List.of(), Map.of(), false);
    List<Object> args = new ArrayList<>();
    StringBuilder sql = new StringBuilder("""
        SELECT pm.id AS mergeId, pm.status, pm.merge_date AS effectiveDate,
               pm.submitted_at AS submittedAt, pm.decision_at AS decisionAt,
               pm.finalized_at AS finalizedAt, pm.decision_remarks AS approvalRemarks,
               pm.notes, pm.source_plot_id AS sourcePlotId, pm.source_plot_no AS sourcePlotNo,
               pm.target_plot_id AS resultingPlotId, pm.target_plot_no AS resultingPlotNo,
               pm.source_booking_id AS sourceBookingId, pm.target_booking_id AS resultingBookingId,
               CONCAT('SB-', LPAD(pm.source_booking_id, 6, '0')) AS sourceBookingNo,
               CONCAT('SB-', LPAD(pm.target_booking_id, 6, '0')) AS resultingBookingNo,
               pm.source_member_id AS sourceMemberId, pm.target_member_id AS resultingMemberId,
               sourceMember.mbr_no AS sourceMemberNo, sourceMember.name AS sourceCustomer,
               targetMember.mbr_no AS resultingMemberNo, targetMember.name AS resultingCustomer,
               sourceProject.id AS sourceProjectId, sourceProject.project_name AS sourceProject,
               sourceProject.location AS sourceLocation, sourceProject.city AS sourceCity,
               targetProject.id AS resultingProjectId, targetProject.project_name AS resultingProject,
               targetProject.location AS resultingLocation, targetProject.city AS resultingCity,
               submitter.username AS submittedBy, approver.username AS approvedBy
        FROM webv9_plot_merges pm
        LEFT JOIN plots sourcePlot ON sourcePlot.plot_id = pm.source_plot_id
        LEFT JOIN blocks sourceBlock ON sourceBlock.block_id = sourcePlot.block_id
        LEFT JOIN webv9_projects sourceProject ON sourceProject.id = sourceBlock.project_id
        LEFT JOIN plots targetPlot ON targetPlot.plot_id = pm.target_plot_id
        LEFT JOIN blocks targetBlock ON targetBlock.block_id = targetPlot.block_id
        LEFT JOIN webv9_projects targetProject ON targetProject.id = targetBlock.project_id
        LEFT JOIN members sourceMember ON sourceMember.member_id = pm.source_member_id
        LEFT JOIN members targetMember ON targetMember.member_id = pm.target_member_id
        LEFT JOIN users submitter ON submitter.id = pm.submitted_by
        LEFT JOIN users approver ON approver.id = pm.approved_by
        WHERE sourceProject.id IN (
        """);
    appendPlaceholders(sql, args, scope);
    sql.append(") AND targetProject.id IN (");
    appendPlaceholders(sql, args, scope);
    sql.append(") ");
    appendDateFilters(sql, args, "pm.merge_date", fromDate, toDate);
    appendExactStatus(sql, args, "pm.status", status);
    if (projectId != null) {
      sql.append(" AND (sourceProject.id = ? OR targetProject.id = ?) ");
      args.add(projectId);
      args.add(projectId);
    }
    if (location != null && !location.isBlank()) {
      sql.append(" AND (sourceProject.location LIKE ? OR targetProject.location LIKE ?) ");
      args.add(like(location));
      args.add(like(location));
    }
    if (customer != null && !customer.isBlank()) {
      sql.append(" AND (sourceMember.name LIKE ? OR targetMember.name LIKE ? "
          + "OR sourceMember.mbr_no LIKE ? OR targetMember.mbr_no LIKE ?) ");
      args.add(like(customer));
      args.add(like(customer));
      args.add(like(customer));
      args.add(like(customer));
    }
    sql.append(" ORDER BY pm.merge_date DESC, pm.id DESC LIMIT ").append(MAX_ROWS + 1);
    List<Map<String, Object>> queried = jdbc.queryForList(sql.toString(), args.toArray());
    boolean truncated = queried.size() > MAX_ROWS;
    List<Map<String, Object>> rows = queried.stream().limit(MAX_ROWS).map(row -> {
      Map<String, Object> item = new LinkedHashMap<>(row);
      long id = ((Number) row.get("mergeId")).longValue();
      item.put("auditTrail", salesWorkflow.mergeEvents(id));
      item.put("financialTransfers", salesWorkflow.mergeTransfers(id));
      return item;
    }).toList();
    return result(rows, statusSummary(rows), truncated);
  }

  @GetMapping("/sales/cancellations")
  public Map<String, Object> cancellations(
      @RequestParam(required = false) String fromDate,
      @RequestParam(required = false) String toDate,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String refundStatus,
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String location,
      @RequestParam(required = false) String customer,
      Authentication authentication) {
    List<Long> scope = scopedProjectFilter(authentication.getName(), projectId);
    if (scope.isEmpty()) return result(List.of(), Map.of(), false);
    List<Object> args = new ArrayList<>();
    StringBuilder sql = new StringBuilder("""
        SELECT c.id AS cancellationId, c.booking_id AS bookingId,
               CONCAT('SB-', LPAD(c.booking_id, 6, '0')) AS bookingNo,
               c.source_plot_id AS cancelledPlotId, c.source_plot_no AS cancelledPlotNo,
               c.cancellation_date AS requestedEffectiveDate, c.status AS approvalStatus,
               c.reason, c.deduction_amount AS approvedDeductions,
               c.refund_amount AS approvedRefund, c.submitted_at AS requestedAt,
               c.decision_at AS decisionAt, c.decision_remarks AS decisionRemarks,
               requester.username AS requestedBy, approver.username AS approvedBy,
               m.member_id AS memberId, m.mbr_no AS memberNo, m.name AS customer,
               p.id AS projectId, p.project_name AS project, p.location, p.city,
               refund.status AS refundStatus, refund.posted_amount AS postedAmount,
               refund.refund_amount AS refundAmount, refund.payout_reference AS payoutReference,
               refund.approved_at AS refundApprovedAt, refund.completed_at AS refundCompletedAt
        FROM webv9_cancellations c
        JOIN sales_bookings sb ON sb.sale_booking_id = c.booking_id
        JOIN registrations r ON r.registration_id = sb.registration_id
        JOIN members m ON m.member_id = r.member_id
        JOIN plots pl ON pl.plot_id = COALESCE(c.source_plot_id, sb.plot_id)
        JOIN blocks b ON b.block_id = pl.block_id
        JOIN webv9_projects p ON p.id = b.project_id
        LEFT JOIN users requester ON requester.id = c.submitted_by
        LEFT JOIN users approver ON approver.id = c.approved_by
        LEFT JOIN webv9_sales_refund_disbursements refund ON refund.cancellation_id = c.id
        WHERE p.id IN (
        """);
    appendPlaceholders(sql, args, scope);
    sql.append(") ");
    appendDateFilters(sql, args, "c.cancellation_date", fromDate, toDate);
    appendExactStatus(sql, args, "c.status", status);
    appendExactStatus(sql, args, "refund.status", refundStatus);
    if (projectId != null) {
      sql.append(" AND p.id = ? ");
      args.add(projectId);
    }
    if (location != null && !location.isBlank()) {
      sql.append(" AND p.location LIKE ? ");
      args.add(like(location));
    }
    if (customer != null && !customer.isBlank()) {
      sql.append(" AND (m.name LIKE ? OR m.mbr_no LIKE ?) ");
      args.add(like(customer));
      args.add(like(customer));
    }
    sql.append(" ORDER BY c.cancellation_date DESC, c.id DESC LIMIT ").append(MAX_ROWS + 1);
    List<Map<String, Object>> queried = jdbc.queryForList(sql.toString(), args.toArray());
    boolean truncated = queried.size() > MAX_ROWS;
    List<Map<String, Object>> rows = queried.stream().limit(MAX_ROWS).map(row -> {
      Map<String, Object> item = new LinkedHashMap<>(row);
      long id = ((Number) row.get("cancellationId")).longValue();
      item.put("auditTrail", salesWorkflow.cancellationEvents(id));
      item.put("settlementLines", salesWorkflow.cancellationLines(id));
      return item;
    }).toList();
    return result(rows, statusSummary(rows), truncated);
  }

  @GetMapping("/finance/receivables-aging")
  public Map<String, Object> receivablesAging(
      @RequestParam(required = false) String asOfDate,
      @RequestParam(required = false) String fromDate,
      @RequestParam(required = false) String toDate,
      @RequestParam(required = false) String bucket,
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String location,
      @RequestParam(required = false) String customer,
      Authentication authentication) {
    LocalDate asOf = parseDate(asOfDate, "As-of date", LocalDate.now());
    List<Long> scope = scopedProjectFilter(authentication.getName(), projectId);
    if (scope.isEmpty()) return result(List.of(), agingSummary(List.of()), false);
    List<Object> args = new ArrayList<>();
    for (int i = 0; i < 4; i++) args.add(Date.valueOf(asOf));
    StringBuilder sql = new StringBuilder("""
        SELECT vr.id AS receivableId, vr.booking_id AS bookingId, vr.due_date AS dueDate,
               vr.description, vr.original_amount AS originalAmount,
               vr.surcharge_amount AS surchargeAmount, vr.adjustment_amount AS adjustmentAmount,
               vr.paid_amount AS paidAmount, """).append(RECEIVABLE_OUTSTANDING).append("""
                 AS outstanding, DATEDIFF(?, vr.due_date) AS daysOverdue,
               CASE WHEN DATEDIFF(?, vr.due_date) <= 30 THEN '1-30'
                    WHEN DATEDIFF(?, vr.due_date) <= 60 THEN '31-60'
                    WHEN DATEDIFF(?, vr.due_date) <= 90 THEN '61-90'
                    ELSE '90+' END AS agingBucket,
               m.member_id AS memberId, m.mbr_no AS memberNo, m.name AS customer,
               r.reg_no AS registrationNo,
               pl.plot_id AS plotId, pl.plot_no AS plotNo,
               pr.id AS projectId, pr.project_name AS project, pr.location, pr.city
        FROM webv9_receivables vr
        JOIN sales_bookings sb ON sb.sale_booking_id = vr.booking_id
        JOIN registrations r ON r.registration_id = sb.registration_id
        JOIN members m ON m.member_id = r.member_id
        JOIN plots pl ON pl.plot_id = sb.plot_id
        JOIN blocks b ON b.block_id = pl.block_id
        JOIN webv9_projects pr ON pr.id = b.project_id
        WHERE pr.id IN (
        """);
    appendPlaceholders(sql, args, scope);
    sql.append(") AND vr.due_date < ? AND ").append(RECEIVABLE_OUTSTANDING).append(" > 0 ");
    args.add(Date.valueOf(asOf));
    appendDateFilters(sql, args, "vr.due_date", fromDate, toDate);
    if (projectId != null) {
      sql.append(" AND pr.id = ? ");
      args.add(projectId);
    }
    if (location != null && !location.isBlank()) {
      sql.append(" AND pr.location LIKE ? ");
      args.add(like(location));
    }
    if (customer != null && !customer.isBlank()) {
      sql.append(" AND (m.name LIKE ? OR m.mbr_no LIKE ? OR r.reg_no LIKE ?) ");
      args.add(like(customer));
      args.add(like(customer));
      args.add(like(customer));
    }
    String normalizedBucket = normalizeBucket(bucket);
    if (normalizedBucket != null) {
      sql.append(" AND CASE WHEN DATEDIFF(?, vr.due_date) <= 30 THEN '1-30' "
          + "WHEN DATEDIFF(?, vr.due_date) <= 60 THEN '31-60' "
          + "WHEN DATEDIFF(?, vr.due_date) <= 90 THEN '61-90' ELSE '90+' END = ? ");
      args.add(Date.valueOf(asOf));
      args.add(Date.valueOf(asOf));
      args.add(Date.valueOf(asOf));
      args.add(normalizedBucket);
    }
    sql.append(" ORDER BY vr.due_date, vr.id LIMIT ").append(MAX_ROWS + 1);
    List<Map<String, Object>> queried = jdbc.queryForList(sql.toString(), args.toArray());
    boolean truncated = queried.size() > MAX_ROWS;
    List<Map<String, Object>> rows = queried.stream().limit(MAX_ROWS).toList();
    Map<String, Object> summary = new LinkedHashMap<>(agingSummary(rows));
    summary.put("asOfDate", asOf);
    summary.put("agingBasis", "Days past receivable due_date as of the selected date; only positive outstanding balances are included.");
    return result(rows, summary, truncated);
  }

  @GetMapping("/finance/receipts")
  public Map<String, Object> financeReceipts(
      @RequestParam(required = false) String fromDate,
      @RequestParam(required = false) String toDate,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String paymentMethod,
      @RequestParam(required = false) String location,
      @RequestParam(required = false) String customer,
      Authentication authentication) {
    List<Long> scope = projectAccess.accessibleProjectIds(authentication.getName());
    if (scope.isEmpty()) return result(List.of(), receiptSummary(List.of()), false);
    List<Object> args = new ArrayList<>();
    StringBuilder sql = new StringBuilder("""
        SELECT p.id AS paymentId, p.receipt_no AS receiptNo, p.payment_date AS paymentDate,
               p.amount, p.payment_method AS paymentMethod, p.status,
               p.deposit_reference AS depositReference, p.payment_reference AS paymentReference,
               p.verified_at AS verifiedAt, p.posted_at AS postedAt,
               m.member_id AS memberId, m.mbr_no AS memberNo, m.name AS customer,
               (SELECT GROUP_CONCAT(DISTINCT project.location ORDER BY project.location SEPARATOR ', ')
                  FROM webv9_payment_allocations a
                  JOIN sales_bookings booking ON booking.sale_booking_id = a.booking_id
                  JOIN plots plot ON plot.plot_id = booking.plot_id
                  JOIN blocks block ON block.block_id = plot.block_id
                  JOIN webv9_projects project ON project.id = block.project_id
                 WHERE a.payment_id = p.id) AS locations
        FROM payments_receipts p JOIN members m ON m.member_id = p.customer_id
        WHERE EXISTS (
          SELECT 1 FROM webv9_payment_allocations a
          JOIN sales_bookings booking ON booking.sale_booking_id = a.booking_id
          JOIN plots plot ON plot.plot_id = booking.plot_id
          JOIN blocks block ON block.block_id = plot.block_id
          WHERE a.payment_id = p.id AND block.project_id IN (
        """);
    appendPlaceholders(sql, args, scope);
    sql.append(")) AND NOT EXISTS (SELECT 1 FROM webv9_payment_allocations a ")
        .append("JOIN sales_bookings booking ON booking.sale_booking_id = a.booking_id ")
        .append("JOIN plots plot ON plot.plot_id = booking.plot_id ")
        .append("JOIN blocks block ON block.block_id = plot.block_id ")
        .append("WHERE a.payment_id = p.id AND (block.project_id IS NULL OR block.project_id NOT IN (");
    appendPlaceholders(sql, args, scope);
    sql.append("))) ");
    appendDateFilters(sql, args, "p.payment_date", fromDate, toDate);
    appendExactStatus(sql, args, "p.status", status);
    if (paymentMethod != null && !paymentMethod.isBlank()) {
      sql.append(" AND UPPER(p.payment_method) = ? ");
      args.add(paymentMethod.trim().toUpperCase(Locale.ROOT));
    }
    if (location != null && !location.isBlank()) {
      sql.append("""
           AND EXISTS (SELECT 1 FROM webv9_payment_allocations a
             JOIN sales_bookings booking ON booking.sale_booking_id = a.booking_id
             JOIN plots plot ON plot.plot_id = booking.plot_id
             JOIN blocks block ON block.block_id = plot.block_id
             JOIN webv9_projects project ON project.id = block.project_id
             WHERE a.payment_id = p.id AND project.location LIKE ?)
          """);
      args.add(like(location));
    }
    if (customer != null && !customer.isBlank()) {
      sql.append(" AND (m.name LIKE ? OR m.mbr_no LIKE ?) ");
      args.add(like(customer));
      args.add(like(customer));
    }
    sql.append(" ORDER BY p.payment_date DESC, p.id DESC LIMIT ").append(MAX_ROWS + 1);
    List<Map<String, Object>> queried = jdbc.queryForList(sql.toString(), args.toArray());
    boolean truncated = queried.size() > MAX_ROWS;
    List<Map<String, Object>> rows = queried.stream().limit(MAX_ROWS).toList();
    return result(rows, receiptSummary(rows), truncated);
  }

  private List<Long> scopedProjectFilter(String username, Long projectId) {
    List<Long> scope = projectAccess.accessibleProjectIds(username);
    if (projectId != null && !scope.contains(projectId)) return List.of();
    return scope;
  }

  private void appendDateFilters(StringBuilder sql, List<Object> args, String column,
                                 String fromDate, String toDate) {
    Date from = fromDate == null || fromDate.isBlank() ? null : Date.valueOf(parseDate(fromDate, "From date", null));
    Date to = toDate == null || toDate.isBlank() ? null : Date.valueOf(parseDate(toDate, "To date", null));
    if (from != null && to != null && from.after(to))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "From date must be on or before To date.");
    if (from != null) {
      sql.append(" AND ").append(column).append(" >= ? ");
      args.add(from);
    }
    if (to != null) {
      sql.append(" AND ").append(column).append(" <= ? ");
      args.add(to);
    }
  }

  private LocalDate parseDate(String value, String label, LocalDate fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must be a valid ISO date.");
    }
  }

  private void appendExactStatus(StringBuilder sql, List<Object> args, String column, String value) {
    if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) return;
    sql.append(" AND UPPER(").append(column).append(") = ? ");
    args.add(value.trim().toUpperCase(Locale.ROOT));
  }

  private void appendPlaceholders(StringBuilder sql, List<Object> args, List<Long> ids) {
    sql.append(String.join(",", java.util.Collections.nCopies(ids.size(), "?")));
    args.addAll(ids);
  }

  private String like(String value) {
    return "%" + value.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  private String normalizeBucket(String bucket) {
    if (bucket == null || bucket.isBlank() || "ALL".equalsIgnoreCase(bucket)) return null;
    String normalized = bucket.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("1-30", "31-60", "61-90", "90+").contains(normalized))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported aging bucket.");
    return normalized;
  }

  private Map<String, Object> agingSummary(List<Map<String, Object>> rows) {
    Map<String, BigDecimal> totals = new LinkedHashMap<>();
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String bucket : List.of("1-30", "31-60", "61-90", "90+")) {
      totals.put(bucket, BigDecimal.ZERO);
      counts.put(bucket, 0L);
    }
    BigDecimal outstanding = BigDecimal.ZERO;
    for (Map<String, Object> row : rows) {
      String key = String.valueOf(row.get("agingBucket"));
      BigDecimal value = row.get("outstanding") instanceof BigDecimal amount ? amount : BigDecimal.ZERO;
      totals.computeIfPresent(key, (ignored, previous) -> previous.add(value));
      counts.computeIfPresent(key, (ignored, previous) -> previous + 1);
      outstanding = outstanding.add(value);
    }
    return Map.of("outstanding", outstanding, "bucketTotals", totals, "bucketCounts", counts,
        "rowCount", rows.size());
  }

  private Map<String, Object> receiptSummary(List<Map<String, Object>> rows) {
    Map<String, BigDecimal> totals = new LinkedHashMap<>();
    Map<String, Long> counts = new LinkedHashMap<>();
    BigDecimal amount = BigDecimal.ZERO;
    for (Map<String, Object> row : rows) {
      String status = String.valueOf(row.get("status"));
      BigDecimal value = row.get("amount") instanceof BigDecimal money ? money : BigDecimal.ZERO;
      totals.merge(status, value, BigDecimal::add);
      counts.merge(status, 1L, Long::sum);
      amount = amount.add(value);
    }
    return Map.of("receiptCount", rows.size(), "amount", amount, "amountByStatus", totals,
        "countByStatus", counts);
  }

  private Map<String, Object> statusSummary(List<Map<String, Object>> rows) {
    Map<String, Long> counts = new LinkedHashMap<>();
    Map<String, Long> refundCounts = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      Object status = row.getOrDefault("status", row.getOrDefault("approvalStatus", "UNKNOWN"));
      counts.merge(String.valueOf(status).toUpperCase(Locale.ROOT), 1L, Long::sum);
      if (row.containsKey("refundStatus")) {
        refundCounts.merge(String.valueOf(row.get("refundStatus")).toUpperCase(Locale.ROOT), 1L, Long::sum);
      }
    }
    return Map.of("rowCount", rows.size(), "countByStatus", counts,
        "countByRefundStatus", refundCounts);
  }

  private Map<String, Object> result(List<Map<String, Object>> rows, Map<String, Object> summary,
                                    boolean truncated) {
    return Map.of("rows", rows, "summary", summary, "truncated", truncated,
        "summaryTruncated", truncated, "maxRows", MAX_ROWS);
  }
}
