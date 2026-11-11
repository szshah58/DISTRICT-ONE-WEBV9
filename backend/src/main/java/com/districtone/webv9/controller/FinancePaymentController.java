package com.districtone.webv9.controller;

import com.districtone.webv9.service.RecoveryEntryCreationService;
import com.districtone.webv9.service.RecoveryEntryRequest;
import com.districtone.webv9.service.FinanceVerificationRequest;
import com.districtone.webv9.service.FinanceVerificationService;
import com.districtone.webv9.service.AllocationBasedPaymentPostingService;
import com.districtone.webv9.service.CustomerLedgerReportRequest;
import com.districtone.webv9.service.CustomerLedgerReportService;
import com.districtone.webv9.security.ProjectAccessService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/finance")
public class FinancePaymentController {

  private static final String UPLOAD_DIR = "uploads/documents/";
  private final JdbcTemplate jdbc;
  private final RecoveryEntryCreationService recoveryEntries;
  private final FinanceVerificationService financeVerification;
  private final AllocationBasedPaymentPostingService paymentPosting;
  private final CustomerLedgerReportService ledgerReports;
  private final ProjectAccessService projectAccess;

  public FinancePaymentController(JdbcTemplate jdbc, RecoveryEntryCreationService recoveryEntries,
                                  FinanceVerificationService financeVerification,
                                  AllocationBasedPaymentPostingService paymentPosting,
                                  CustomerLedgerReportService ledgerReports,
                                  ProjectAccessService projectAccess) {
    this.jdbc = jdbc;
    this.recoveryEntries = recoveryEntries;
    this.financeVerification = financeVerification;
    this.paymentPosting = paymentPosting;
    this.ledgerReports = ledgerReports;
    this.projectAccess = projectAccess;
  }

  @GetMapping("/summary")
  public Map<String, Object> summary() {
    String username = currentUsername();
    if (username != null && !projectAccess.isSuperAdmin(username)) {
      List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, amount, status, payment_date FROM payments_receipts");
      List<Map<String, Object>> visible = rows.stream()
          .filter(row -> projectAccess.canAccessPayment(username, ((Number) row.get("id")).longValue())).toList();
      BigDecimal posted = visible.stream().filter(r -> "POSTED".equals(r.get("status")))
          .map(r -> (BigDecimal) r.get("amount")).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
      long pending = visible.stream().filter(r -> Set.of("RECOVERY_ENTRY", "PENDING", "PENDING_VERIFICATION").contains(r.get("status"))).count();
      BigDecimal today = visible.stream().filter(r -> "POSTED".equals(r.get("status")))
          .filter(r -> java.time.LocalDate.now().equals(r.get("payment_date") instanceof java.sql.Date d ? d.toLocalDate() : r.get("payment_date")))
          .map(r -> (BigDecimal) r.get("amount")).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
      return Map.of("postedCollection", posted, "pendingReview", pending, "receipts", visible.size(), "todayCollection", today);
    }
    return Map.of(
        "postedCollection", valueOrZero("SELECT COALESCE(SUM(amount), 0) FROM payments_receipts WHERE status = 'POSTED'"),
        "pendingReview", jdbc.queryForObject("SELECT COUNT(*) FROM payments_receipts WHERE status IN ('RECOVERY_ENTRY', 'PENDING', 'PENDING_VERIFICATION')", Long.class),
        "receipts", jdbc.queryForObject("SELECT COUNT(*) FROM payments_receipts", Long.class),
        "todayCollection", valueOrZero("SELECT COALESCE(SUM(amount), 0) FROM payments_receipts WHERE payment_date = CURDATE() AND status = 'POSTED'")
    );
  }

  @GetMapping("/payments")
  public List<Map<String, Object>> listPayments(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Long memberId,
      @RequestParam(required = false) String fromDate,
      @RequestParam(required = false) String toDate,
      @RequestParam(required = false) String receiptNo,
      @RequestParam(required = false) String registrationNo,
      @RequestParam(required = false) String partyName,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String location,
      @RequestParam(required = false) String paymentMethod,
      @RequestParam(required = false) String bankDocument,
      @RequestParam(required = false) String postedToGl,
      @RequestParam(required = false) String approvalStatus,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "500") int pageSize
  ) {
    if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be zero or greater.");
    if (pageSize < 1 || pageSize > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must be between 1 and 500.");
    if (fromDate != null && !fromDate.isBlank() && toDate != null && !toDate.isBlank()
        && parseDate(fromDate, "From Date").after(parseDate(toDate, "To Date"))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "From Date must be on or before To Date.");
    }
    StringBuilder sql = new StringBuilder("""
        SELECT p.id, p.receipt_no AS receiptNo, p.payment_date AS paymentDate, p.amount,
               p.payment_method AS method,
               CASE WHEN UPPER(TRIM(p.payment_method)) = 'CASH' THEN 'CASH'
                    WHEN UPPER(TRIM(p.payment_method)) IN ('BANK','BANK_TRANSFER','ONLINE','ONLINE_TRANSFER','CHEQUE') THEN 'BANK'
                    ELSE 'UNKNOWN' END AS paymentChannel,
               p.sales_classification AS salesClassification,
               p.bank_instrument_type AS bankInstrumentType,
               p.status, p.payment_reference AS paymentReference, p.deposit_reference AS referenceNo,
               p.receiving_account AS receivingAccount, p.deposit_date AS depositDate,
               p.remarks, p.rejection_reason AS rejectionReason,
               CASE WHEN p.posted_at IS NULL THEN 'No' ELSE 'Yes' END AS postedToGl,
               CASE WHEN p.status IN ('RECOVERY_ENTRY','PENDING','PENDING_VERIFICATION') THEN 'Pending Finance Verification'
                    WHEN p.status IN ('VERIFIED','POSTED','REVERSED') THEN 'Approved'
                    WHEN p.status = 'REJECTED' THEN 'Rejected' ELSE p.status END AS approvalStatus,
               (SELECT GROUP_CONCAT(DISTINCT r.reg_no ORDER BY r.reg_no SEPARATOR ', ')
                  FROM webv9_payment_allocations pa JOIN registrations r ON r.registration_id = pa.registration_id
                 WHERE pa.payment_id = p.id) AS registrationNo,
               (SELECT GROUP_CONCAT(DISTINCT pr.location ORDER BY pr.location SEPARATOR ', ')
                  FROM webv9_payment_allocations pa JOIN sales_bookings sb ON sb.sale_booking_id = pa.booking_id
                  JOIN plots pl ON pl.plot_id = sb.plot_id JOIN blocks bl ON bl.block_id = pl.block_id
                  JOIN webv9_projects pr ON pr.id = bl.project_id WHERE pa.payment_id = p.id) AS location,
               p.verified_source_amount AS verifiedSourceAmount,
               p.verification_reference AS verificationReference,
               p.verification_remarks AS verificationRemarks, p.verified_at AS verifiedAt,
               (SELECT COUNT(*) FROM webv9_payment_allocations pa WHERE pa.payment_id = p.id) AS allocationCount,
               m.member_id AS memberId, m.mbr_no AS mbrNo, m.name AS memberName,
               c.id AS chargeTypeId, c.name AS chargeName, c.code AS chargeCode,
               b.id AS bankId, b.bank_name AS bankName,
               cs.id AS cashSiteId, cs.site_name AS cashSiteName,
               u.username AS enteredBy, uv.username AS verifiedBy,
               doc.id AS evidenceId, doc.original_name AS evidenceName
        FROM payments_receipts p
        JOIN members m ON m.member_id = p.customer_id
        LEFT JOIN webv9_charge_types c ON c.id = p.charge_type_id
        LEFT JOIN webv9_banks b ON b.id = p.bank_id
        LEFT JOIN webv9_cash_sites cs ON cs.id = p.cash_site_id
        LEFT JOIN users u ON u.id = p.entered_by
        LEFT JOIN users uv ON uv.id = p.verified_by
        LEFT JOIN webv9_documents doc ON doc.payment_id = p.id AND doc.document_status = 'ACTIVE'
        WHERE 1=1
        """);

    List<Object> params = new ArrayList<>();

    if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
      sql.append(" AND p.status = ? ");
      params.add(status.trim().toUpperCase());
    }

    if (memberId != null) {
      sql.append(" AND p.customer_id = ? ");
      params.add(memberId);
    }

    if (fromDate != null && !fromDate.isBlank()) { sql.append(" AND p.payment_date >= ? "); params.add(parseDate(fromDate, "From Date")); }
    if (toDate != null && !toDate.isBlank()) { sql.append(" AND p.payment_date <= ? "); params.add(parseDate(toDate, "To Date")); }
    if (receiptNo != null && !receiptNo.isBlank()) { sql.append(" AND p.receipt_no LIKE ? "); params.add("%" + receiptNo.trim() + "%"); }
    if (registrationNo != null && !registrationNo.isBlank()) {
      sql.append(" AND EXISTS (SELECT 1 FROM webv9_payment_allocations pa JOIN registrations r ON r.registration_id=pa.registration_id WHERE pa.payment_id=p.id AND r.reg_no LIKE ?) ");
      params.add("%" + registrationNo.trim() + "%");
    }
    if (partyName != null && !partyName.isBlank()) { sql.append(" AND m.name LIKE ? "); params.add("%" + partyName.trim() + "%"); }
    if (projectName != null && !projectName.isBlank()) {
      sql.append(" AND EXISTS (SELECT 1 FROM webv9_payment_allocations pa JOIN sales_bookings sb ON sb.sale_booking_id=pa.booking_id JOIN plots pl ON pl.plot_id=sb.plot_id JOIN blocks bl ON bl.block_id=pl.block_id JOIN webv9_projects pr ON pr.id=bl.project_id WHERE pa.payment_id=p.id AND pr.project_name LIKE ?) ");
      params.add("%" + projectName.trim() + "%");
    }
    if (location != null && !location.isBlank()) {
      sql.append(" AND EXISTS (SELECT 1 FROM webv9_payment_allocations pa JOIN sales_bookings sb ON sb.sale_booking_id=pa.booking_id JOIN plots pl ON pl.plot_id=sb.plot_id JOIN blocks bl ON bl.block_id=pl.block_id JOIN webv9_projects pr ON pr.id=bl.project_id WHERE pa.payment_id=p.id AND pr.location LIKE ?) ");
      params.add("%" + location.trim() + "%");
    }
    if (paymentMethod != null && !paymentMethod.isBlank() && !"ALL".equalsIgnoreCase(paymentMethod)) {
      if ("BANK".equalsIgnoreCase(paymentMethod)) sql.append(" AND UPPER(TRIM(p.payment_method)) IN ('BANK','BANK_TRANSFER','ONLINE','ONLINE_TRANSFER','CHEQUE') ");
      else if ("CASH".equalsIgnoreCase(paymentMethod)) sql.append(" AND UPPER(TRIM(p.payment_method)) = 'CASH' ");
      else { sql.append(" AND UPPER(TRIM(p.payment_method)) = ? "); params.add(paymentMethod.trim().toUpperCase(Locale.ROOT)); }
    }
    if (bankDocument != null && !bankDocument.isBlank()) { sql.append(" AND p.deposit_reference LIKE ? "); params.add("%" + bankDocument.trim() + "%"); }
    if (postedToGl != null && !postedToGl.isBlank() && !"ALL".equalsIgnoreCase(postedToGl)) {
      if (!"YES".equalsIgnoreCase(postedToGl) && !"NO".equalsIgnoreCase(postedToGl)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Posted to GL filter must be Yes, No, or All.");
      }
      sql.append(" AND p.posted_at IS ").append("YES".equalsIgnoreCase(postedToGl) ? "NOT NULL" : "NULL");
    }
    if (approvalStatus != null && !approvalStatus.isBlank() && !"ALL".equalsIgnoreCase(approvalStatus)) {
      switch (approvalStatus.trim().toUpperCase(Locale.ROOT)) {
        case "PENDING", "PENDING FINANCE VERIFICATION" -> sql.append(" AND p.status IN ('RECOVERY_ENTRY','PENDING','PENDING_VERIFICATION') ");
        case "APPROVED", "VERIFIED" -> sql.append(" AND p.status IN ('VERIFIED','POSTED','REVERSED') ");
        case "REJECTED" -> sql.append(" AND p.status = 'REJECTED' ");
        default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported Finance approval status.");
      }
    }

    if (q != null && !q.isBlank()) {
      String search = "%" + q.trim() + "%";
      sql.append(" AND (m.mbr_no LIKE ? OR m.name LIKE ? OR p.receipt_no LIKE ? OR p.payment_reference LIKE ? OR p.deposit_reference LIKE ?) ");
      params.add(search); params.add(search); params.add(search); params.add(search); params.add(search);
    }

    sql.append(" ORDER BY p.created_at DESC LIMIT ? OFFSET ?");
    params.add(pageSize);
    params.add((long) page * pageSize);
    List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
    String username = currentUsername();
    if (username == null) return rows;
    return rows.stream().filter(row -> projectAccess.canAccessPayment(username, ((Number) row.get("id")).longValue())).toList();
  }

  /** Retains the original Java call signature for internal tests and source callers. */
  public List<Map<String, Object>> listPayments(String status, String q, Long memberId) {
    return listPayments(status, q, memberId, null, null, null, null, null, null, null, null,
        null, null, null, 0, 500);
  }

  private java.sql.Date parseDate(String value, String label) {
    try { return java.sql.Date.valueOf(java.time.LocalDate.parse(value.trim())); }
    catch (java.time.DateTimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must be a valid date."); }
  }

  private String currentUsername() {
    Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    return auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()) ? null : auth.getName();
  }

  private void requirePaymentFor(Authentication auth, long paymentId) {
    if (auth != null) projectAccess.requirePayment(auth.getName(), paymentId);
  }

  private boolean hasScopedBooking(String username, long memberId) {
    List<Long> bookingIds = jdbc.query("""
        SELECT DISTINCT sb.sale_booking_id FROM sales_bookings sb
        JOIN registrations r ON r.registration_id=sb.registration_id WHERE r.member_id=?
        """, (rs, row) -> rs.getLong(1), memberId);
    return bookingIds.stream().anyMatch(id -> projectAccess.canAccessBooking(username, id));
  }

  @GetMapping("/payments/bank-reference-conflicts")
  public List<Map<String, Object>> bankReferenceConflicts(Authentication authentication) {
    String username = authentication == null ? currentUsername() : authentication.getName();
    if (username == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT p.id, p.bank_id AS bankId,
               COALESCE(NULLIF(UPPER(TRIM(p.receiving_account)), ''), '[UNSPECIFIED]') AS receivingAccountKey,
               UPPER(TRIM(p.deposit_reference)) AS referenceKey, p.receipt_no AS receiptNo, p.status
        FROM payments_receipts p
        WHERE p.bank_id IS NOT NULL AND p.deposit_reference IS NOT NULL
          AND TRIM(p.deposit_reference) <> ''
          AND UPPER(TRIM(p.payment_method)) IN ('BANK','BANK_TRANSFER','ONLINE','ONLINE_TRANSFER','CHEQUE')
          AND EXISTS (
            SELECT 1 FROM payments_receipts duplicate
            WHERE duplicate.id <> p.id AND duplicate.bank_id = p.bank_id
              AND duplicate.deposit_reference IS NOT NULL AND TRIM(duplicate.deposit_reference) <> ''
              AND UPPER(TRIM(duplicate.payment_method)) IN ('BANK','BANK_TRANSFER','ONLINE','ONLINE_TRANSFER','CHEQUE')
              AND COALESCE(NULLIF(UPPER(TRIM(duplicate.receiving_account)), ''), '[UNSPECIFIED]') =
                  COALESCE(NULLIF(UPPER(TRIM(p.receiving_account)), ''), '[UNSPECIFIED]')
              AND UPPER(TRIM(duplicate.deposit_reference)) = UPPER(TRIM(p.deposit_reference))
          )
        ORDER BY p.bank_id, receivingAccountKey, referenceKey, p.id
        """).stream().filter(row -> projectAccess.canAccessPayment(username, ((Number) row.get("id")).longValue()))
        .toList();
    Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String key = row.get("bankId") + "|" + row.get("receivingAccountKey") + "|" + row.get("referenceKey");
      grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
    }
    return grouped.values().stream().filter(candidates -> candidates.size() > 1).map(candidates -> {
      Map<String, Object> first = candidates.get(0);
      String candidateDetails = candidates.stream().map(candidate ->
          candidate.get("id") + ":" + candidate.get("receiptNo") + ":" + candidate.get("status"))
          .collect(java.util.stream.Collectors.joining(","));
      return Map.<String, Object>of(
          "bankId", first.get("bankId"),
          "receivingAccountKey", first.get("receivingAccountKey"),
          "referenceKey", first.get("referenceKey"),
          "candidateCount", (long) candidates.size(),
          "candidates", candidateDetails);
    }).toList();
  }

  @GetMapping("/payments/{id}")
  public Map<String, Object> getPayment(@PathVariable Long id) {
    try {
      Map<String, Object> result = jdbc.queryForMap("""
          SELECT p.id, p.receipt_no AS receiptNo, p.payment_date AS paymentDate, p.amount,
                 p.payment_method AS method,
                 CASE WHEN UPPER(TRIM(p.payment_method)) = 'CASH' THEN 'CASH'
                      WHEN UPPER(TRIM(p.payment_method)) IN ('BANK','BANK_TRANSFER','ONLINE','ONLINE_TRANSFER','CHEQUE') THEN 'BANK'
                      ELSE 'UNKNOWN' END AS paymentChannel,
                 p.sales_classification AS salesClassification,
                 p.bank_instrument_type AS bankInstrumentType,
                 p.status, p.payment_reference AS paymentReference, p.deposit_reference AS referenceNo,
                 p.receiving_account AS receivingAccount, p.deposit_date AS depositDate,
                 (SELECT GROUP_CONCAT(DISTINCT r.reg_no ORDER BY r.reg_no SEPARATOR ', ')
                    FROM webv9_payment_allocations pa JOIN registrations r ON r.registration_id=pa.registration_id
                   WHERE pa.payment_id=p.id) AS registrationNo,
                 (SELECT GROUP_CONCAT(DISTINCT pr.location ORDER BY pr.location SEPARATOR ', ')
                    FROM webv9_payment_allocations pa JOIN sales_bookings sb ON sb.sale_booking_id=pa.booking_id
                    JOIN plots pl ON pl.plot_id=sb.plot_id JOIN blocks bl ON bl.block_id=pl.block_id
                    JOIN webv9_projects pr ON pr.id=bl.project_id WHERE pa.payment_id=p.id) AS location,
                 p.remarks, p.rejection_reason AS rejectionReason,
                 p.verified_source_amount AS verifiedSourceAmount,
                 p.verification_reference AS verificationReference,
                 p.verification_remarks AS verificationRemarks, p.verified_at AS verifiedAt,
                 NULL AS bookingId,
                 m.member_id AS memberId, m.mbr_no AS mbrNo, m.name AS memberName,
                 c.id AS chargeTypeId, c.name AS chargeName, c.code AS chargeCode,
                 b.id AS bankId, b.bank_name AS bankName,
                 cs.id AS cashSiteId, cs.site_name AS cashSiteName,
                 u.username AS enteredBy, uv.username AS verifiedBy,
                 doc.id AS evidenceId, doc.original_name AS evidenceName, doc.content_type AS evidenceType
          FROM payments_receipts p
          JOIN members m ON m.member_id = p.customer_id
          LEFT JOIN webv9_charge_types c ON c.id = p.charge_type_id
          LEFT JOIN webv9_banks b ON b.id = p.bank_id
          LEFT JOIN webv9_cash_sites cs ON cs.id = p.cash_site_id
          LEFT JOIN users u ON u.id = p.entered_by
          LEFT JOIN users uv ON uv.id = p.verified_by
          LEFT JOIN webv9_documents doc ON doc.payment_id = p.id AND doc.document_status = 'ACTIVE'
          WHERE p.id = ?
          """, id);
      String username = currentUsername();
      if (username != null) projectAccess.requirePayment(username, id);
      return result;
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment receipt not found");
    }
  }

  @GetMapping("/payments/{id}/allocations")
  public List<Map<String, Object>> getPaymentAllocations(@PathVariable Long id) {
    getPayment(id);
    return jdbc.queryForList("""
        SELECT a.id, a.line_no AS lineNo, a.registration_id AS registrationId,
               r.reg_no AS registrationNo, a.booking_id AS bookingId,
               CONCAT('SB-', LPAD(a.booking_id, 6, '0')) AS bookingNo,
               a.plot_id AS plotId, p.plot_no AS plotNo, b.block_name AS blockName,
               a.charge_type_id AS chargeTypeId, ct.code AS chargeCode, ct.name AS chargeName,
               a.receivable_id AS receivableId, vr.description AS receivableDescription,
               vr.due_date AS dueDate, a.installment_schedule_id AS installmentScheduleId,
               ws.instalment_no AS installmentNo, a.allocated_amount AS allocatedAmount,
               a.line_remarks AS remarks
        FROM webv9_payment_allocations a
        JOIN registrations r ON r.registration_id = a.registration_id
        JOIN sales_bookings sb ON sb.sale_booking_id = a.booking_id
        JOIN plots p ON p.plot_id = a.plot_id
        JOIN blocks b ON b.block_id = p.block_id
        JOIN webv9_charge_types ct ON ct.id = a.charge_type_id
        LEFT JOIN webv9_receivables vr ON vr.id = a.receivable_id
        LEFT JOIN webv9_instalment_schedules ws ON ws.id = a.installment_schedule_id
        WHERE a.payment_id = ?
        ORDER BY a.line_no, a.id
        """, id);
  }

  @PostMapping({"/payments/recovery", "/recovery"})
  public Map<String, Object> createRecovery(@Valid @RequestBody RecoveryEntryRequest req, Authentication auth) {
    if (auth != null && req.allocationLines() != null && !req.allocationLines().isEmpty()) {
      for (var line : req.allocationLines()) projectAccess.requireBooking(auth.getName(), line.bookingId());
    } else if (auth != null && req.allocationScopeBookingId() != null) {
      projectAccess.requireBooking(auth.getName(), req.allocationScopeBookingId());
    } else if (auth != null && !projectAccess.isSuperAdmin(auth.getName())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Receipt entry must be tied to a booking in your project/location scope");
    }
    return recoveryEntries.create(req, auth != null ? auth.getName() : null);
  }

  @PostMapping("/payments/{id}/evidence")
  @Transactional
  public Map<String, Object> uploadEvidence(
      @PathVariable Long id,
      @RequestParam("file") MultipartFile file,
      Authentication auth
  ) {
    requirePaymentFor(auth, id);
    Map<String, Object> payment = getPayment(id);
    if (file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
    }

    File dir = new File(UPLOAD_DIR);
    if (!dir.exists()) {
      dir.mkdirs();
    }

    String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "evidence.png";
    String storageKey = UPLOAD_DIR + "PAYMENT_" + id + "_" + UUID.randomUUID().toString().substring(0, 8) + "_" + originalName;

    try {
      file.transferTo(new File(storageKey));
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store uploaded evidence file");
    }

    Long userId = findUserId(auth != null ? auth.getName() : null);
    Long memberId = ((Number) payment.get("memberId")).longValue();

    jdbc.update("UPDATE webv9_documents SET document_status = 'SUPERSEDE' WHERE payment_id = ?", id);

    jdbc.update("""
        INSERT INTO webv9_documents
          (member_id, payment_id, document_type, original_name, storage_key, content_type, document_status, uploaded_by)
        VALUES (?, ?, 'PAYMENT_EVIDENCE', ?, ?, ?, 'ACTIVE', ?)
        """, memberId, id, originalName, storageKey, file.getContentType(), userId);

    Long docId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    audit(auth, "UPLOAD", "FINANCE_EVIDENCE", "Uploaded evidence for payment " + payment.get("receiptNo"));

    return Map.of("documentId", docId, "originalName", originalName, "paymentId", id);
  }

  @GetMapping("/documents/{id}/download")
  public ResponseEntity<Resource> downloadEvidence(@PathVariable Long id) {
    try {
      Map<String, Object> doc = jdbc.queryForMap(
          "SELECT payment_id, storage_key, original_name, content_type FROM webv9_documents WHERE id = ?", id);
      String username = currentUsername();
      if (username != null) projectAccess.requirePayment(username, ((Number) doc.get("payment_id")).longValue());
      String storageKey = (String) doc.get("storage_key");
      String originalName = (String) doc.get("original_name");
      String contentType = (String) doc.get("content_type");

      File file = new File(storageKey);
      if (!file.exists()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence file not found on disk");
      }

      Resource resource = new FileSystemResource(file);
      return ResponseEntity.ok()
          .contentType(MediaType.parseMediaType(contentType != null ? contentType : "application/octet-stream"))
          .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + originalName + "\"")
          .body(resource);

    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document record not found");
    }
  }

  @RequestMapping(value = {"/payments/{id}/verify", "/payments/{id}/approve"},
      method = {RequestMethod.POST, RequestMethod.PATCH})
  public Map<String, Object> verifyPayment(@PathVariable Long id, @Valid @RequestBody FinanceVerificationRequest request,
                                           Authentication auth) {
    requirePaymentFor(auth, id);
    return financeVerification.verify(id, request, auth != null ? auth.getName() : null);
  }

  @RequestMapping(value = "/payments/{id}/reject", method = {RequestMethod.POST, RequestMethod.PATCH})
  public Map<String, Object> rejectPayment(
      @PathVariable Long id,
      @RequestBody Map<String, Object> body,
      Authentication auth
  ) {
    requirePaymentFor(auth, id);
    String reason = body.get("reason") != null ? String.valueOf(body.get("reason")) : "";
    return financeVerification.reject(id, reason, auth != null ? auth.getName() : null);
  }

  @RequestMapping(value = "/payments/{id}/post", method = {RequestMethod.POST, RequestMethod.PATCH})
  public Map<String, Object> postPayment(@PathVariable Long id, Authentication auth) {
    requirePaymentFor(auth, id);
    Long userId = findUserId(auth != null ? auth.getName() : null);
    return paymentPosting.post(id, userId);
  }

  @GetMapping("/ledger/{memberId}")
  public List<Map<String, Object>> getLedger(@PathVariable Long memberId) {
    ensureMemberExists(memberId);

    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT l.id, l.transaction_date AS date, l.reference_no AS referenceNo,
               l.transaction_type AS type, l.debit, l.credit, l.description,
               l.booking_id AS bookingId, p.receipt_no AS paymentReceiptNo,
               p.payment_method AS paymentMethod, p.payment_reference AS paymentReference,
               p.deposit_reference AS depositReference,
               p.sales_classification AS salesClassification,
               p.bank_instrument_type AS bankInstrumentType,
               ct.name AS chargeName
        FROM webv9_customer_ledger l
        LEFT JOIN payments_receipts p ON p.id = l.payment_id
        LEFT JOIN webv9_charge_types ct ON ct.id = p.charge_type_id
        WHERE l.member_id = ?
        ORDER BY l.transaction_date ASC, l.id ASC
        """, memberId);

    String username = currentUsername();
    if (username != null && !projectAccess.isSuperAdmin(username)) rows = rows.stream()
        .filter(row -> row.get("bookingId") != null && projectAccess.canAccessBooking(username, ((Number) row.get("bookingId")).longValue()))
        .toList();

    BigDecimal runningBalance = BigDecimal.ZERO;
    List<Map<String, Object>> result = new ArrayList<>();

    for (Map<String, Object> row : rows) {
      BigDecimal debit = (BigDecimal) row.get("debit");
      BigDecimal credit = (BigDecimal) row.get("credit");

      if (debit == null) debit = BigDecimal.ZERO;
      if (credit == null) credit = BigDecimal.ZERO;

      runningBalance = runningBalance.add(debit).subtract(credit);

      Map<String, Object> map = new LinkedHashMap<>(row);
      map.put("runningBalance", runningBalance);
      result.add(map);
    }

    return result;
  }

  @GetMapping("/members/{memberId}/bookings")
  public List<Map<String, Object>> getMemberBookings(@PathVariable Long memberId) {
    ensureMemberExists(memberId);
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT sb.sale_booking_id AS id, CONCAT('SB-', LPAD(sb.sale_booking_id, 6, '0')) AS bookingNo,
               r.reg_no AS registrationNo, r.form_no AS bookingFormNo,
               p.plot_no AS plotNo, b.block_name AS blockName,
               pr.project_name AS projectName, pr.location AS location,
               p.plot_size_marla AS sizeMarla, p.plot_type AS type,
               COALESCE(sb.category_snapshot, p.category, r.category) AS category,
               sb.sale_status AS status
        FROM sales_bookings sb
        JOIN registrations r ON r.registration_id = sb.registration_id
        JOIN plots p ON p.plot_id = sb.plot_id
        JOIN blocks b ON b.block_id = p.block_id
        LEFT JOIN webv9_projects pr ON pr.id = b.project_id
        WHERE r.member_id = ?
        ORDER BY sb.sale_booking_id DESC
        """, memberId);
    String username = currentUsername();
    return username == null ? rows : rows.stream().filter(row -> projectAccess.canAccessBooking(username, ((Number) row.get("id")).longValue())).toList();
  }

  @GetMapping("/members/{memberId}/allocation-dues")
  public List<Map<String, Object>> getMemberAllocationDues(@PathVariable Long memberId,
                                                           @RequestParam Long bookingId) {
    ensureMemberExists(memberId);
    String username = currentUsername();
    if (username != null) projectAccess.requireBooking(username, bookingId);
    return jdbc.queryForList("""
        SELECT vr.id AS receivableId, vr.member_id AS memberId,
               vr.registration_id AS registrationId, r.reg_no AS registrationNo,
               vr.booking_id AS bookingId, CONCAT('SB-', LPAD(sb.sale_booking_id, 6, '0')) AS bookingNo,
               vr.plot_id AS plotId, p.plot_no AS plotNo, b.block_name AS blockName,
               vr.charge_type_id AS chargeTypeId, ct.code AS chargeCode, ct.name AS chargeName,
               vr.due_date AS dueDate, vr.original_amount AS originalAmount,
               vr.surcharge_amount AS surcharge, vr.adjustment_amount AS adjustments,
               vr.paid_amount AS paidAmount, vr.description,
               COALESCE(res.reservedAmount, 0) AS reservedAmount,
               GREATEST(0, vr.original_amount + vr.surcharge_amount - vr.adjustment_amount
                           - vr.paid_amount - COALESCE(res.reservedAmount, 0)) AS availableDue
        FROM webv9_receivables vr
        JOIN registrations r ON r.registration_id = vr.registration_id AND r.member_id = vr.member_id
        JOIN sales_bookings sb ON sb.sale_booking_id = vr.booking_id
                              AND sb.registration_id = vr.registration_id
                              AND sb.plot_id = vr.plot_id
        JOIN plots p ON p.plot_id = vr.plot_id
        JOIN blocks b ON b.block_id = p.block_id
        JOIN webv9_charge_types ct ON ct.id = vr.charge_type_id AND ct.is_active = TRUE
        LEFT JOIN (
          SELECT a.receivable_id, SUM(a.allocated_amount) AS reservedAmount
          FROM webv9_payment_allocations a
          JOIN payments_receipts pr ON pr.id = a.payment_id
          WHERE pr.status IN ('RECOVERY_ENTRY','PENDING','PENDING_VERIFICATION','VERIFIED')
          GROUP BY a.receivable_id
        ) res ON res.receivable_id = vr.id
        WHERE vr.member_id = ? AND vr.booking_id = ?
          AND vr.registration_id IS NOT NULL AND vr.plot_id IS NOT NULL AND vr.charge_type_id IS NOT NULL
        ORDER BY vr.due_date, ct.name, vr.id
        """, memberId, bookingId);
  }

  @GetMapping("/members/search")
  public List<Map<String, Object>> searchLedgerMembers(@RequestParam String field, @RequestParam String q) {
    if (q == null || q.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a customer search value.");
    String predicate = switch (field == null ? "" : field.trim().toUpperCase(Locale.ROOT)) {
      case "MBR_NO" -> "m.mbr_no LIKE ?";
      case "REGISTRATION_NO" -> "EXISTS (SELECT 1 FROM registrations rx WHERE rx.member_id = m.member_id AND rx.reg_no LIKE ?)";
      case "FORM_NO" -> "EXISTS (SELECT 1 FROM registrations rx WHERE rx.member_id = m.member_id AND rx.form_no LIKE ?)";
      case "NAME" -> "m.name LIKE ?";
      case "CNIC_NO" -> "m.cnic_no LIKE ?";
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported customer search field.");
    };
    String term = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT m.member_id AS memberId, m.mbr_no AS mbrNo,
               GROUP_CONCAT(DISTINCT r.reg_no ORDER BY r.reg_no SEPARATOR ', ') AS registrationNos,
               GROUP_CONCAT(DISTINCT r.form_no ORDER BY r.form_no SEPARATOR ', ') AS formNos,
               m.name, m.cnic_no AS cnicNo
        FROM members m LEFT JOIN registrations r ON r.member_id = m.member_id
        WHERE %s
        GROUP BY m.member_id, m.mbr_no, m.name, m.cnic_no
        ORDER BY m.mbr_no LIMIT 50
        """.formatted(predicate), term);
    String username = currentUsername();
    return username == null ? rows : rows.stream().filter(row -> hasScopedBooking(username, ((Number) row.get("memberId")).longValue())).toList();
  }

  @PostMapping("/ledger/report")
  public Map<String, Object> generateCustomerLedger(@Valid @RequestBody CustomerLedgerReportRequest request) {
    String username = currentUsername();
    if (username != null && !projectAccess.isSuperAdmin(username)) {
      if (request.bookingId() == null)
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A booking within your project/location scope is required for this report");
      projectAccess.requireBooking(username, request.bookingId());
    }
    return ledgerReports.generate(request);
  }

  @GetMapping("/members/{memberId}/dues")
  public List<Map<String, Object>> getMemberDues(@PathVariable Long memberId) {
    ensureMemberExists(memberId);
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT r.id, r.booking_id AS bookingId, r.charge_type_id AS chargeTypeId, ct.name AS chargeName,
               r.due_date AS dueDate, r.original_amount AS originalAmount,
               r.paid_amount AS paidAmount, r.surcharge_amount AS surcharge,
               r.adjustment_amount AS adjustments,
               (r.original_amount + r.surcharge_amount - r.paid_amount - r.adjustment_amount) AS outstanding,
               r.status, r.description
        FROM webv9_receivables r
        LEFT JOIN webv9_charge_types ct ON ct.id = r.charge_type_id
        WHERE r.member_id = ?
        ORDER BY r.due_date ASC
        """, memberId);
    String username = currentUsername();
    return username == null ? rows : rows.stream().filter(row -> {
      Long bookingId = row.get("bookingId") instanceof Number n ? n.longValue() : null;
      return bookingId != null && projectAccess.canAccessBooking(username, bookingId);
    }).toList();
  }

  @GetMapping("/charge-types")
  public List<Map<String, Object>> getChargeTypes() {
    return jdbc.queryForList("""
        SELECT id, code, name, description, amount, calculation_method AS calculationMethod, is_active AS active
        FROM webv9_charge_types
        WHERE is_active = true
        ORDER BY name
        """);
  }

  @GetMapping("/banks")
  public List<Map<String, Object>> getBanks() {
    return jdbc.queryForList("""
        SELECT id, bank_name AS bankName, account_title AS accountTitle, account_no AS accountNo, branch_name AS branch, bank_code AS bankCode, is_active AS active
        FROM webv9_banks
        WHERE is_active = true
        ORDER BY bank_name
        """);
  }

  @GetMapping("/cash-sites")
  public List<Map<String, Object>> getCashSites() {
    return jdbc.queryForList("""
        SELECT id, site_name AS siteName, location, contact_details AS contactDetails, is_active AS active
        FROM webv9_cash_sites
        WHERE is_active = true
        ORDER BY site_name
        """);
  }

  private void ensureMemberExists(Long memberId) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE member_id = ?", Integer.class, memberId);
    if (count == null || count == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
    }
  }

  private Long findUserId(String username) {
    if (username == null || username.isBlank()) return null;
    try {
      return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username.trim());
    } catch (EmptyResultDataAccessException e) {
      return null;
    }
  }

  private BigDecimal valueOrZero(String sql, Object... args) {
    try {
      BigDecimal total = jdbc.queryForObject(sql, BigDecimal.class, args);
      return total == null ? BigDecimal.ZERO : total;
    } catch (EmptyResultDataAccessException e) {
      return BigDecimal.ZERO;
    }
  }

  private String blankToNull(String str) {
    return str == null || str.isBlank() ? null : str.trim();
  }

  private void audit(Authentication auth, String action, String module, String details) {
    String username = auth != null ? auth.getName() : "SYSTEM";
    jdbc.update("INSERT INTO system_logs(username, action, module, details) VALUES (?, ?, ?, ?)",
        username, action, module, details);
  }
}
