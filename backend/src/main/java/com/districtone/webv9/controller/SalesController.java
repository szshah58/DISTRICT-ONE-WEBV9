package com.districtone.webv9.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.districtone.webv9.service.SalesWorkflowService;
import com.districtone.webv9.security.ProjectAccessService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/sales")
public class SalesController {
  private final JdbcTemplate jdbc;
  private final SalesWorkflowService workflow;
  private final ProjectAccessService projectAccess;
  public SalesController(JdbcTemplate jdbc, SalesWorkflowService workflow, ProjectAccessService projectAccess) {
    this.jdbc = jdbc;
    this.workflow = workflow;
    this.projectAccess = projectAccess;
  }

  public record BookingRequest(@NotNull Long registrationId, @NotNull Long plotId) {}
  public record TransferRequest(@NotNull Long bookingId, @NotNull Long fromMemberId, @NotNull Long toMemberId,
                                @NotNull LocalDate transferDate, String notes) {}
  public record CancellationRequest(@NotNull Long bookingId, @NotNull LocalDate cancellationDate,
                                    @Size(max = 500) String reason, @Size(max = 500) String remarks) {}
  public record MergeRequest(@NotNull Long sourcePlotId, @NotNull Long targetPlotId,
                             @NotNull LocalDate mergeDate, @Size(max = 500) String notes) {}
  public record DecisionRequest(@Size(max = 1000) String remarks) {}
  public record MergeApprovalRequest(List<SalesWorkflowService.MergeTransferMapping> transferMappings,
                                     @Size(max = 1000) String remarks) {}
  public record CancellationApprovalRequest(List<Long> deductionAdjustmentIds,
                                            @Size(max = 1000) String remarks) {}
  public record RefundCompletionRequest(@NotNull @Size(max = 120) String payoutReference,
                                        @Size(max = 1000) String remarks) {}

  @GetMapping("/registrations")
  public List<Map<String, Object>> listRegistrations(Authentication auth) {
    return jdbc.queryForList("""
        SELECT r.registration_id AS registrationId, r.reg_no AS regNo, r.form_no AS formNo,
               r.category, r.status AS registrationStatus, m.member_id AS memberId,
               m.mbr_no AS mbrNo, m.name AS memberName
        FROM registrations r JOIN members m ON m.member_id = r.member_id
        WHERE EXISTS (SELECT 1 FROM sales_bookings sb JOIN plots pl ON pl.plot_id = sb.plot_id
          JOIN blocks bl ON bl.block_id = pl.block_id WHERE sb.registration_id = r.registration_id
          AND bl.project_id IS NOT NULL)
        ORDER BY r.registration_id DESC LIMIT 1000
        """).stream().filter(row -> jdbc.queryForObject("""
          SELECT COUNT(*) FROM sales_bookings sb JOIN plots pl ON pl.plot_id=sb.plot_id
          JOIN blocks bl ON bl.block_id=pl.block_id WHERE sb.registration_id=?
          """, Integer.class, row.get("registrationId")) > 0
          && hasAnyBookingAccess(auth.getName(), ((Number) row.get("registrationId")).longValue())).toList();
  }

  @GetMapping("/bookings")
  public List<Map<String, Object>> listBookings(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) String status,
                                                Authentication auth) {
    StringBuilder sql = new StringBuilder(bookingSelect()).append(" WHERE 1=1 ");
    List<Object> params = new ArrayList<>();
    if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
      sql.append(" AND sb.sale_status = ? "); params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (q != null && !q.isBlank()) {
      String term = "%" + q.trim() + "%";
      sql.append(" AND (CAST(sb.sale_booking_id AS CHAR) LIKE ? OR r.reg_no LIKE ? OR m.mbr_no LIKE ? OR m.name LIKE ? OR p.plot_no LIKE ?) ");
      params.add(term); params.add(term); params.add(term); params.add(term); params.add(term);
    }
    sql.append(" ORDER BY sb.sale_booking_id DESC LIMIT 500");
    return jdbc.queryForList(sql.toString(), params.toArray()).stream()
        .filter(row -> row.get("projectId") != null
            && projectAccess.canAccessProject(auth.getName(), ((Number) row.get("projectId")).longValue()))
        .toList();
  }

  @GetMapping("/bookings/{id}")
  public Map<String, Object> getBooking(@PathVariable Long id) {
    // Project scope is checked after the booking has been resolved below.
    try {
      Map<String, Object> booking = jdbc.queryForMap(bookingSelect() + " WHERE sb.sale_booking_id = ?", id);
      Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
      projectAccess.requireBooking(auth.getName(), id);
      booking.put("schedule", jdbc.queryForList("""
          SELECT id, instalment_no AS instalmentNo, due_date AS dueDate, amount, status
          FROM webv9_instalment_schedules WHERE booking_id = ? ORDER BY instalment_no
          """, id));
      return booking;
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sales booking not found");
    }
  }

  @GetMapping("/available-plots")
  public List<Map<String, Object>> listAvailablePlots(Authentication auth) {
    return jdbc.queryForList("""
        SELECT p.plot_id AS plotId, p.plot_no AS plotNo, p.plot_size_marla AS sizeMarla,
               p.plot_type AS usageType, p.category, p.status, b.block_id AS blockId,
               b.block_name AS blockName, pr.id AS projectId, pr.project_code AS projectCode,
               pr.project_name AS projectName
        FROM plots p JOIN blocks b ON b.block_id = p.block_id
        LEFT JOIN webv9_projects pr ON pr.id = b.project_id
        WHERE UPPER(p.status) IN ('AVAILABLE','UNALLOCATED','OPEN')
          AND NOT EXISTS (
            SELECT 1 FROM sales_bookings current_booking
            WHERE current_booking.plot_id = p.plot_id
              AND current_booking.sale_booking_id = (
                SELECT MAX(latest_booking.sale_booking_id)
                FROM sales_bookings latest_booking WHERE latest_booking.plot_id = p.plot_id
              )
              AND UPPER(COALESCE(current_booking.sale_status, '')) NOT IN ('CANCELLED','MERGED')
          )
          AND NOT EXISTS (
            SELECT 1 FROM webv9_plot_merges pm
            WHERE UPPER(pm.status) = 'PENDING'
              AND (pm.source_plot_id = p.plot_id OR pm.target_plot_id = p.plot_id)
          )
        ORDER BY pr.project_name, b.block_name, p.plot_no
        """).stream().filter(row -> row.get("projectId") != null
          && projectAccess.canAccessProject(auth.getName(), ((Number) row.get("projectId")).longValue())).toList();
  }

  @PostMapping("/bookings")
  @Transactional
  public Map<String, Object> createBooking(@Valid @RequestBody BookingRequest req, Authentication auth) {
    registration(req.registrationId());
    projectAccess.requirePlot(auth.getName(), req.plotId());
    ensurePlotAvailable(req.plotId());
    try {
      jdbc.update("""
          INSERT INTO sales_bookings
            (registration_id, plot_id, sale_status, customer_name_snapshot,
             plot_size_snapshot, plot_type_snapshot, category_snapshot)
          SELECT r.registration_id, p.plot_id, 'ALLOCATED', m.name, CAST(p.plot_size_marla AS CHAR),
                 p.plot_type, COALESCE(r.category, p.category)
          FROM plots p JOIN registrations r ON r.registration_id = ?
          JOIN members m ON m.member_id = r.member_id
          WHERE p.plot_id = ?
          """, req.registrationId(), req.plotId());
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This registration is already linked to the selected plot");
    }
    Long bookingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    jdbc.update("UPDATE plots SET status = 'ALLOCATED' WHERE plot_id = ?", req.plotId());
    audit(auth, "CREATE_BOOKING", "SALES", "Allocated plot " + req.plotId() + " to registration " + req.registrationId());
    return getBooking(bookingId);
  }

  @PostMapping("/transfers")
  @Transactional
  public Map<String, Object> createTransfer(@Valid @RequestBody TransferRequest req, Authentication auth) {
    Map<String, Object> booking = getBooking(req.bookingId());
    Long memberId = ((Number) booking.get("memberId")).longValue();
    if (!memberId.equals(req.fromMemberId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The specified current member does not own this booking");
    if (req.fromMemberId().equals(req.toMemberId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transfer source and destination members cannot be identical");
    Long targetRegistration = soleRegistrationForMember(req.toMemberId());
    Long userId = findUserId(auth == null ? null : auth.getName());
    jdbc.update("""
        INSERT INTO webv9_transfers (booking_id, from_member_id, to_member_id, transfer_date, status, notes, created_by)
        VALUES (?, ?, ?, ?, 'APPROVED', ?, ?)
        """, req.bookingId(), req.fromMemberId(), req.toMemberId(), req.transferDate(), blankToNull(req.notes()), userId);
    Long transferId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    jdbc.update("UPDATE sales_bookings SET registration_id = ? WHERE sale_booking_id = ?", targetRegistration, req.bookingId());
    audit(auth, "TRANSFER_PLOT", "SALES", "Transferred booking " + booking.get("bookingNo") + " to member " + req.toMemberId());
    return Map.of("transferId", transferId, "bookingId", req.bookingId(), "newMemberId", req.toMemberId(), "status", "APPROVED");
  }

  @GetMapping("/transfers")
  public List<Map<String, Object>> listTransfers(Authentication auth) {
    return jdbc.queryForList("""
        SELECT t.id, t.booking_id AS bookingId, t.transfer_date AS transferDate, t.status, t.notes,
               CONCAT('SB-', LPAD(sb.sale_booking_id, 6, '0')) AS bookingNo,
               p.plot_no AS plotNo, b.block_name AS blockName,
               fm.mbr_no AS fromMbrNo, fm.name AS fromMemberName,
               tm.mbr_no AS toMbrNo, tm.name AS toMemberName, u.username AS createdBy
        FROM webv9_transfers t
        JOIN sales_bookings sb ON sb.sale_booking_id = t.booking_id
        JOIN plots p ON p.plot_id = sb.plot_id JOIN blocks b ON b.block_id = p.block_id
        JOIN members fm ON fm.member_id = t.from_member_id JOIN members tm ON tm.member_id = t.to_member_id
        LEFT JOIN users u ON u.id = t.created_by
        ORDER BY t.transfer_date DESC, t.id DESC
        """).stream().filter(row -> projectAccess.canAccessBooking(auth.getName(), ((Number) row.get("bookingId")).longValue())).toList();
  }

  @PostMapping("/cancellations")
  @Transactional
  public Map<String, Object> createCancellation(@Valid @RequestBody CancellationRequest req, Authentication auth) {
    projectAccess.requireBooking(auth.getName(), req.bookingId());
    return workflow.submitCancellation(req.bookingId(), req.cancellationDate(), req.reason(), req.remarks(),
        auth == null ? null : auth.getName());
  }

  @GetMapping("/cancellations")
  public List<Map<String, Object>> listCancellations(Authentication auth) {
    return jdbc.queryForList("""
        SELECT c.id, c.booking_id AS bookingId, c.cancellation_date AS cancellationDate,
               CASE WHEN UPPER(c.status) = 'APPROVED' THEN c.deduction_amount ELSE NULL END AS deductionAmount,
               CASE WHEN UPPER(c.status) = 'APPROVED' THEN c.refund_amount ELSE NULL END AS refundAmount, c.status, c.reason,
               CONCAT('SB-', LPAD(sb.sale_booking_id, 6, '0')) AS bookingNo,
               m.mbr_no AS mbrNo, m.name AS memberName, p.plot_no AS plotNo, b.block_name AS blockName,
               u.username AS approvedBy, c.decision_remarks AS decisionRemarks
        FROM webv9_cancellations c JOIN sales_bookings sb ON sb.sale_booking_id = c.booking_id
        JOIN registrations r ON r.registration_id = sb.registration_id JOIN members m ON m.member_id = r.member_id
        JOIN plots p ON p.plot_id = sb.plot_id JOIN blocks b ON b.block_id = p.block_id
        LEFT JOIN users u ON u.id = c.approved_by
        ORDER BY c.cancellation_date DESC, c.id DESC
        """).stream().filter(row -> projectAccess.canAccessBooking(auth.getName(), ((Number) row.get("bookingId")).longValue())).toList();
  }

  @PostMapping("/merges")
  @Transactional
  public Map<String, Object> createMerge(@Valid @RequestBody MergeRequest req, Authentication auth) {
    projectAccess.requirePlot(auth.getName(), req.sourcePlotId());
    projectAccess.requirePlot(auth.getName(), req.targetPlotId());
    return workflow.submitMerge(req.sourcePlotId(), req.targetPlotId(), req.mergeDate(), req.notes(),
        auth == null ? null : auth.getName());
  }

  @GetMapping("/merge-candidates")
  public List<Map<String, Object>> listMergeCandidates(Authentication auth) {
    return workflow.listMergeCandidates().stream().filter(row -> projectAccess.canAccessProject(auth.getName(),
        ((Number) row.get("projectId")).longValue())).toList();
  }

  @GetMapping("/merges")
  public List<Map<String, Object>> listMerges(Authentication auth) {
    return jdbc.queryForList("""
        SELECT pm.id, pm.merge_date AS mergeDate, pm.status, pm.notes,
               pm.source_plot_id AS sourcePlotId, pm.target_plot_id AS targetPlotId,
               COALESCE(pm.target_plot_no, tp.plot_no) AS targetPlotNo, tb.block_name AS targetBlockName,
               COALESCE(pm.source_plot_no, sp.plot_no) AS sourcePlotNo, sb.block_name AS sourceBlockName,
               u.username AS approvedBy, pm.decision_remarks AS decisionRemarks,
               pm.source_booking_id AS sourceBookingId, pm.target_booking_id AS targetBookingId
        FROM webv9_plot_merges pm
        LEFT JOIN plots tp ON tp.plot_id = pm.target_plot_id LEFT JOIN blocks tb ON tb.block_id = tp.block_id
        LEFT JOIN plots sp ON sp.plot_id = pm.source_plot_id LEFT JOIN blocks sb ON sb.block_id = sp.block_id
        LEFT JOIN users u ON u.id = pm.approved_by
        ORDER BY pm.merge_date DESC, pm.id DESC
        """).stream().filter(row -> projectAccess.canAccessProject(auth.getName(), projectAccess.projectForPlot(((Number) row.get("sourcePlotId")).longValue()))
            && projectAccess.canAccessProject(auth.getName(), projectAccess.projectForPlot(((Number) row.get("targetPlotId")).longValue()))).toList();
  }

  @GetMapping("/merges/{id}")
  public Map<String, Object> mergeDetail(@PathVariable Long id) {
    Map<String, Object> result = workflow.mergeDetail(id);
    var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    if (result.get("sourcePlotId") != null) projectAccess.requirePlot(auth.getName(), ((Number) result.get("sourcePlotId")).longValue());
    if (result.get("targetPlotId") != null) projectAccess.requirePlot(auth.getName(), ((Number) result.get("targetPlotId")).longValue());
    return result;
  }

  @PostMapping("/merges/{id}/approve")
  public Map<String, Object> approveMerge(@PathVariable Long id,
      @Valid @RequestBody MergeApprovalRequest request, Authentication auth) {
    mergeDetail(id);
    return workflow.approveMerge(id, request.transferMappings(), request.remarks(), auth.getName());
  }

  @PostMapping("/merges/{id}/reject")
  public Map<String, Object> rejectMerge(@PathVariable Long id,
      @Valid @RequestBody DecisionRequest request, Authentication auth) {
    mergeDetail(id);
    return workflow.rejectMerge(id, request.remarks(), auth.getName());
  }

  @GetMapping("/cancellations/{id}")
  public Map<String, Object> cancellationDetail(@PathVariable Long id) {
    Map<String, Object> result = workflow.cancellationDetail(id);
    var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    Object bookingId = result.get("booking_id");
    if (bookingId instanceof Number n) projectAccess.requireBooking(auth.getName(), n.longValue());
    return result;
  }

  @GetMapping("/bookings/{bookingId}/financial-position")
  public Map<String, Object> bookingFinancialPosition(@PathVariable Long bookingId) {
    var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    projectAccess.requireBooking(auth.getName(), bookingId);
    return workflow.financialPosition(bookingId);
  }

  @PostMapping("/cancellations/{id}/approve")
  public Map<String, Object> approveCancellation(@PathVariable Long id,
      @Valid @RequestBody CancellationApprovalRequest request, Authentication auth) {
    requireCancellationScope(auth.getName(), id);
    return workflow.approveCancellation(id, request.deductionAdjustmentIds(), request.remarks(), auth.getName());
  }

  @PostMapping("/cancellations/{id}/reject")
  public Map<String, Object> rejectCancellation(@PathVariable Long id,
      @Valid @RequestBody DecisionRequest request, Authentication auth) {
    requireCancellationScope(auth.getName(), id);
    return workflow.rejectCancellation(id, request.remarks(), auth.getName());
  }

  @PostMapping("/cancellations/{id}/refund/disburse")
  public Map<String, Object> completeRefund(@PathVariable Long id,
      @Valid @RequestBody RefundCompletionRequest request, Authentication auth) {
    requireCancellationScope(auth.getName(), id);
    return workflow.completeRefund(id, request.payoutReference(), request.remarks(), auth.getName());
  }

  @GetMapping("/members/{memberId}/bookings")
  public List<Map<String, Object>> getMemberBookings(@PathVariable Long memberId, Authentication auth) {
    ensureMemberExists(memberId);
    return jdbc.queryForList("""
        SELECT sb.sale_booking_id AS id, CONCAT('SB-', LPAD(sb.sale_booking_id, 6, '0')) AS bookingNo,
               p.plot_no AS plotNo, b.block_name AS blockName, p.plot_size_marla AS sizeMarla,
               sb.sale_status AS status
        FROM sales_bookings sb JOIN registrations r ON r.registration_id = sb.registration_id
        JOIN plots p ON p.plot_id = sb.plot_id JOIN blocks b ON b.block_id = p.block_id
        WHERE r.member_id = ? ORDER BY sb.sale_booking_id DESC
        """, memberId).stream().filter(row -> projectAccess.canAccessBooking(auth.getName(), ((Number) row.get("id")).longValue())).toList();
  }

  private String bookingSelect() {
    return """
        SELECT sb.sale_booking_id AS id, CONCAT('SB-', LPAD(sb.sale_booking_id, 6, '0')) AS bookingNo,
               DATE(sb.created_at) AS bookingDate, NULL AS basePrice, NULL AS devCharges,
               NULL AS possessionCharges, NULL AS otherCharges, NULL AS totalPrice,
               NULL AS advancePercentage, NULL AS advanceRequired, NULL AS advancePaid,
               sb.sale_status AS status, NULL AS remarks, r.registration_id AS registrationId,
               r.reg_no AS regNo, r.form_no AS formNo, m.member_id AS memberId,
               m.mbr_no AS mbrNo, m.name AS memberName, m.contact1 AS memberContact,
               p.plot_id AS plotId, p.plot_no AS plotNo, p.plot_size_marla AS sizeMarla,
               p.plot_type AS usageType, b.block_id AS blockId, b.block_name AS blockName,
               pr.id AS projectId, pr.project_code AS projectCode, pr.project_name AS projectName,
               NULL AS planId, NULL AS planName
        FROM sales_bookings sb
        JOIN registrations r ON r.registration_id = sb.registration_id
        JOIN members m ON m.member_id = r.member_id
        JOIN plots p ON p.plot_id = sb.plot_id
        JOIN blocks b ON b.block_id = p.block_id
        LEFT JOIN webv9_projects pr ON pr.id = b.project_id
        """;
  }

  private Map<String, Object> registration(Long id) {
    try { return jdbc.queryForMap("SELECT registration_id, member_id FROM registrations WHERE registration_id = ?", id); }
    catch (EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found"); }
  }

  private Long soleRegistrationForMember(Long memberId) {
    List<Long> ids = jdbc.query("SELECT registration_id FROM registrations WHERE member_id = ? ORDER BY registration_id", (rs, row) -> rs.getLong(1), memberId);
    if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The new member has no registration record");
    if (ids.size() > 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "The new member has multiple registrations; choose a registration before transferring");
    return ids.get(0);
  }

  private void ensurePlotAvailable(Long plotId) {
    String status;
    try { status = jdbc.queryForObject("SELECT status FROM plots WHERE plot_id = ? FOR UPDATE", String.class, plotId); }
    catch (EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plot not found"); }
    if (status == null || !Set.of("AVAILABLE", "UNALLOCATED", "OPEN").contains(status.toUpperCase(Locale.ROOT)))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected plot is not available");
    Integer committed = jdbc.queryForObject("""
        SELECT COUNT(*) FROM sales_bookings sb
        WHERE sb.plot_id = ?
          AND sb.sale_booking_id = (SELECT MAX(latest.sale_booking_id) FROM sales_bookings latest WHERE latest.plot_id = ?)
          AND UPPER(COALESCE(sb.sale_status, '')) NOT IN ('CANCELLED','MERGED')
        """, Integer.class, plotId, plotId);
    if (committed != null && committed > 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected plot is already committed or merged");
  }

  private void lockPlots(List<Long> plotIds) {
    List<Long> ordered = plotIds.stream().distinct().sorted().toList();
    String placeholders = String.join(",", Collections.nCopies(ordered.size(), "?"));
    List<Long> locked = jdbc.query("SELECT plot_id FROM plots WHERE plot_id IN (" + placeholders + ") ORDER BY plot_id FOR UPDATE",
        (rs, row) -> rs.getLong(1), ordered.toArray());
    if (locked.size() != ordered.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "One or more plots were not found");
  }

  private void lockBookings(List<Long> bookingIds) {
    List<Long> ordered = bookingIds.stream().distinct().sorted().toList();
    String placeholders = String.join(",", Collections.nCopies(ordered.size(), "?"));
    List<Long> locked = jdbc.query("SELECT sale_booking_id FROM sales_bookings WHERE sale_booking_id IN (" + placeholders + ") ORDER BY sale_booking_id FOR UPDATE",
        (rs, row) -> rs.getLong(1), ordered.toArray());
    if (locked.size() != ordered.size()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A source or target booking changed during validation");
  }

  private Map<String, Object> lockBooking(Long bookingId) {
    try {
      jdbc.queryForObject("SELECT sale_booking_id FROM sales_bookings WHERE sale_booking_id = ? FOR UPDATE",
          Long.class, bookingId);
      return getBooking(bookingId);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sales booking not found");
    }
  }

  private Map<String, Object> activeBookingForPlot(Long plotId) {
    List<Map<String, Object>> bookings = jdbc.queryForList(bookingSelect()
        + " WHERE sb.plot_id = ? ORDER BY sb.sale_booking_id DESC LIMIT 1", plotId);
    if (bookings.isEmpty() || !"ALLOCATED".equalsIgnoreCase(String.valueOf(bookings.get(0).get("status"))))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Source and target plots must both have current allocated bookings");
    return bookings.get(0);
  }

  private void ensureNoActiveCancellation(Long bookingId) {
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*) FROM webv9_cancellations
        WHERE booking_id = ? AND UPPER(status) IN ('PENDING','APPROVED')
        """, Integer.class, bookingId);
    if (count != null && count > 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking already has a pending or completed cancellation request");
  }

  private void ensureNoActiveMerge(Long plotId) {
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*) FROM webv9_plot_merges
        WHERE (UPPER(status) = 'PENDING' AND (source_plot_id = ? OR target_plot_id = ?))
           OR (source_plot_id = ? AND UPPER(status) = 'APPROVED')
        """, Integer.class, plotId, plotId, plotId);
    if (count != null && count > 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This plot already has a pending or completed merge request");
  }

  private void ensureMemberExists(Long memberId) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE member_id = ?", Integer.class, memberId);
    if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
  }

  private boolean hasAnyBookingAccess(String username, long registrationId) {
    List<Long> projectIds = jdbc.query("""
        SELECT DISTINCT b.project_id FROM sales_bookings sb JOIN plots p ON p.plot_id=sb.plot_id
        JOIN blocks b ON b.block_id=p.block_id WHERE sb.registration_id=? AND b.project_id IS NOT NULL
        """, (rs, row) -> rs.getLong(1), registrationId);
    return projectIds.stream().anyMatch(projectId -> projectAccess.canAccessProject(username, projectId));
  }

  private void requireCancellationScope(String username, long cancellationId) {
    Long bookingId = jdbc.queryForObject("SELECT booking_id FROM webv9_cancellations WHERE id=?", Long.class, cancellationId);
    projectAccess.requireBooking(username, bookingId);
  }

  private Long findUserId(String username) {
    if (username == null || username.isBlank()) return null;
    try { return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username.trim()); }
    catch (EmptyResultDataAccessException e) { return null; }
  }

  private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

  private String cancellationReason(String reason, String remarks) {
    String combined = Arrays.asList(blankToNull(reason), blankToNull(remarks)).stream()
        .filter(Objects::nonNull)
        .reduce((left, right) -> left + "\nRemarks: " + right)
        .orElse(null);
    if (combined != null && combined.length() > 500)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reason and remarks together must not exceed 500 characters");
    return combined;
  }

  private void audit(Authentication auth, String action, String module, String details) {
    jdbc.update("INSERT INTO system_logs(username, action, module, details) VALUES (?, ?, ?, ?)",
        auth == null ? "SYSTEM" : auth.getName(), action, module, details);
  }
}
