package com.districtone.webv9.service;

import com.districtone.webv9.security.UserPermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/** Posts only a Finance-verified receipt's persisted allocation lines. */
@Service
public class AllocationBasedPaymentPostingService {
  private final JdbcTemplate jdbc;
  private final PaymentAllocationReservationService reservations;
  private final UserPermissionService permissions;

  public AllocationBasedPaymentPostingService(JdbcTemplate jdbc, PaymentAllocationReservationService reservations,
                                                UserPermissionService permissions) {
    this.jdbc = jdbc;
    this.reservations = reservations;
    this.permissions = permissions;
  }

  private String authorizedFinanceUsername(Long userId) {
    if (userId == null) return null;
    try {
      String username = jdbc.queryForObject(
          "SELECT username FROM users WHERE id = ? AND is_active = TRUE", String.class, userId);
      return permissions.isGranted(username, "Finance", "RECEIPT_POST") ? username : null;
    } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
      return null;
    }
  }

  @Transactional
  public Map<String, Object> post(Long paymentId, Long userId) {
    PaymentReservationSnapshot payment = reservations.validateExistingReservations(paymentId);
    if (!"VERIFIED".equals(payment.status())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Only Finance-verified payments can be posted. Current status: " + payment.status());
    }
    String username = authorizedFinanceUsername(userId);
    if (username == null) {
      throw new AccessDeniedException("An active designation with receipt-posting permission is required.");
    }
    if (userId.equals(payment.enteredBy()) || userId.equals(payment.verifiedBy())) {
      throw new AccessDeniedException("Payment entry, verification, and posting must be performed by different users.");
    }
    Map<String, Object> header = jdbc.queryForMap(
        "SELECT verified_source_amount FROM payments_receipts WHERE id = ?", paymentId);
    BigDecimal verifiedAmount = (BigDecimal) header.get("verified_source_amount");
    if (verifiedAmount == null || verifiedAmount.compareTo(payment.amount()) != 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Verified source amount must equal the payment header amount before posting.");
    }

    List<Map<String, Object>> lines = jdbc.queryForList("""
        SELECT id, booking_id, receivable_id, allocated_amount
        FROM webv9_payment_allocations WHERE payment_id = ? ORDER BY line_no
        """, paymentId);
    if (lines.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment has no allocation lines.");
    Integer priorApplications = jdbc.queryForObject("""
        SELECT COUNT(*) FROM webv9_payment_receivable_applications pra
        JOIN webv9_payment_allocations a ON a.id = pra.allocation_id WHERE a.payment_id = ?
        """, Integer.class, paymentId);
    if (priorApplications != null && priorApplications != 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment has existing receivable applications while not posted.");
    }

    BigDecimal allocatedTotal = BigDecimal.ZERO.setScale(2);
    Map<Long, BigDecimal> byReceivable = new TreeMap<>();
    Map<Long, BigDecimal> byBooking = new TreeMap<>();
    for (Map<String, Object> line : lines) {
      Long allocationId = ((Number) line.get("id")).longValue();
      Long receivableId = ((Number) line.get("receivable_id")).longValue();
      Long bookingId = ((Number) line.get("booking_id")).longValue();
      BigDecimal amount = (BigDecimal) line.get("allocated_amount");
      allocatedTotal = allocatedTotal.add(amount);
      byReceivable.merge(receivableId, amount, BigDecimal::add);
      byBooking.merge(bookingId, amount, BigDecimal::add);
      jdbc.update("""
          INSERT INTO webv9_payment_receivable_applications
            (allocation_id, receivable_id, applied_amount, created_by)
          VALUES (?, ?, ?, ?)
          """, allocationId, receivableId, amount, userId);
    }
    if (allocatedTotal.compareTo(payment.amount()) != 0 || allocatedTotal.compareTo(verifiedAmount) != 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Allocation total must equal both the payment and verified source amounts.");
    }

    for (Map.Entry<Long, BigDecimal> entry : byReceivable.entrySet()) {
      Map<String, Object> rec = jdbc.queryForMap("""
          SELECT original_amount, surcharge_amount, adjustment_amount, paid_amount
          FROM webv9_receivables WHERE id = ? FOR UPDATE
          """, entry.getKey());
      BigDecimal due = ((BigDecimal) rec.get("original_amount"))
          .add((BigDecimal) rec.get("surcharge_amount"))
          .subtract((BigDecimal) rec.get("adjustment_amount"));
      BigDecimal newPaid = ((BigDecimal) rec.get("paid_amount")).add(entry.getValue());
      if (newPaid.compareTo(due) > 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allocation exceeds receivable due at posting.");
      }
      jdbc.update("UPDATE webv9_receivables SET paid_amount = ?, status = ? WHERE id = ?",
          newPaid, newPaid.compareTo(due) >= 0 ? "PAID" : "PARTIAL", entry.getKey());
    }

    String receiptNo = payment.receiptNo();
    for (Map.Entry<Long, BigDecimal> entry : byBooking.entrySet()) {
      jdbc.update("""
          INSERT INTO webv9_customer_ledger
            (member_id, booking_id, payment_id, transaction_date, transaction_type,
             reference_no, debit, credit, description, created_by)
          VALUES (?, ?, ?, NOW(), 'PAYMENT', ?, 0, ?, ?, ?)
          """, payment.memberId(), entry.getKey(), paymentId, receiptNo, entry.getValue(),
          "Payment Received (" + receiptNo + ")", userId);
    }

    int changed = jdbc.update("""
        UPDATE payments_receipts SET status = 'POSTED', posted_at = CURRENT_TIMESTAMP
        WHERE id = ? AND status = 'VERIFIED'
        """, paymentId);
    if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment status changed before posting.");
    jdbc.update("INSERT INTO system_logs(username, action, module, details) VALUES (?, 'POST', 'FINANCE', ?)",
        username, "Posted receipt " + receiptNo + " (paymentId=" + paymentId + ")"
            + "; paymentReference=" + (payment.paymentReference() == null ? "" : payment.paymentReference().replace(';', ',').replace('\r', ' ').replace('\n', ' ').trim())
            + "; salesClassification=" + (payment.salesClassification() == null ? "" : payment.salesClassification())
            + "; bankInstrumentType=" + (payment.bankInstrumentType() == null ? "" : payment.bankInstrumentType()));
    return Map.of("id", paymentId, "receiptNo", receiptNo, "status", "POSTED");
  }
}
