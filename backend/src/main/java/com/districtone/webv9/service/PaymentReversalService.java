package com.districtone.webv9.service;

import com.districtone.webv9.security.UserPermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/** Appends a compensating reversal while preserving the original posting and applications. */
@Service
public class PaymentReversalService {
  private final JdbcTemplate jdbc;
  private final UserPermissionService permissions;

  public PaymentReversalService(JdbcTemplate jdbc, UserPermissionService permissions) {
    this.jdbc = jdbc;
    this.permissions = permissions;
  }

  @Transactional
  public Map<String, Object> reverse(Long paymentId, String reversalNo, String reason, Long userId, String username) {
    if (reason == null || reason.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reversal reason is required.");
    }
    String cleanReason = reason.trim();
    if (cleanReason.length() > 500) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reversal reason must be 500 characters or fewer.");
    }
    if (userId == null || username == null || username.isBlank()
        || !permissions.isGranted(username, "Finance", "RECEIPT_REVERSE")) {
      throw new AccessDeniedException("An active designation with receipt-reversal permission is required.");
    }

    Map<String, Object> payment;
    try {
      payment = jdbc.queryForMap("""
          SELECT id, customer_id, receipt_no, amount, status, entered_by, verified_by
          FROM payments_receipts WHERE id = ? FOR UPDATE
          """, paymentId);
    } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment receipt not found.");
    }
    if (!"POSTED".equals(payment.get("status"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a posted payment can be reversed once.");
    }
    if (userId.equals(asLong(payment.get("entered_by"))) || userId.equals(asLong(payment.get("verified_by")))) {
      throw new AccessDeniedException("Payment entry, verification, and reversal must be performed by different users.");
    }

    var applications = jdbc.queryForList("""
        SELECT pra.receivable_id, a.booking_id, SUM(pra.applied_amount) AS amount
        FROM webv9_payment_receivable_applications pra
        JOIN webv9_payment_allocations a ON a.id = pra.allocation_id
        WHERE a.payment_id = ?
        GROUP BY pra.receivable_id, a.booking_id
        ORDER BY pra.receivable_id, a.booking_id
        """, paymentId);
    if (applications.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Posted payment has no applications to reverse.");
    }

    BigDecimal total = BigDecimal.ZERO;
    Map<Long, BigDecimal> byBooking = new TreeMap<>();
    for (Map<String, Object> application : applications) {
      Long receivableId = asLong(application.get("receivable_id"));
      Long bookingId = asLong(application.get("booking_id"));
      BigDecimal amount = (BigDecimal) application.get("amount");
      Map<String, Object> receivable = jdbc.queryForMap("""
          SELECT original_amount, surcharge_amount, adjustment_amount, paid_amount
          FROM webv9_receivables WHERE id = ? FOR UPDATE
          """, receivableId);
      BigDecimal paid = (BigDecimal) receivable.get("paid_amount");
      if (amount == null || paid == null || paid.compareTo(amount) < 0) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Receivable balance no longer supports this reversal.");
      }
      BigDecimal restoredPaid = paid.subtract(amount);
      BigDecimal due = ((BigDecimal) receivable.get("original_amount"))
          .add((BigDecimal) receivable.get("surcharge_amount"))
          .subtract((BigDecimal) receivable.get("adjustment_amount"));
      String status = restoredPaid.signum() == 0 ? "UNPAID" : restoredPaid.compareTo(due) >= 0 ? "PAID" : "PARTIAL";
      jdbc.update("UPDATE webv9_receivables SET paid_amount = ?, status = ? WHERE id = ?", restoredPaid, status, receivableId);
      total = total.add(amount);
      byBooking.merge(bookingId, amount, BigDecimal::add);
    }
    BigDecimal headerAmount = (BigDecimal) payment.get("amount");
    if (total.compareTo(headerAmount) != 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Applied total differs from posted payment amount; Finance review is required.");
    }

    jdbc.update("INSERT INTO webv9_finance_reversals(reversal_no,payment_id,reason,created_by) VALUES(?,?,?,?)",
        reversalNo, paymentId, cleanReason, userId);
    String receiptNo = String.valueOf(payment.get("receipt_no"));
    Long memberId = asLong(payment.get("customer_id"));
    for (Map.Entry<Long, BigDecimal> entry : byBooking.entrySet()) {
      jdbc.update("""
          INSERT INTO webv9_customer_ledger
            (member_id, booking_id, payment_id, transaction_date, transaction_type,
             reference_no, debit, credit, description, created_by)
          VALUES (?, ?, ?, NOW(), 'REVERSAL', ?, ?, 0, ?, ?)
          """, memberId, entry.getKey(), paymentId, reversalNo, entry.getValue(),
          "Reversal of payment " + receiptNo + " (" + reversalNo + ")", userId);
    }
    int changed = jdbc.update("UPDATE payments_receipts SET status = 'REVERSED' WHERE id = ? AND status = 'POSTED'", paymentId);
    if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment status changed before reversal.");
    jdbc.update("INSERT INTO system_logs(username,action,module,details) VALUES(?, 'REVERSE', 'FINANCE', ?)",
        username, reversalNo + " reversed receipt " + receiptNo + " (paymentId=" + paymentId + ")");
    return Map.of("reversalNo", reversalNo, "receiptNo", receiptNo, "status", "REVERSED");
  }

  private Long asLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
}
