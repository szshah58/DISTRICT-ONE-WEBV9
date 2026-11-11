package com.districtone.webv9.service;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import com.districtone.webv9.security.UserPermissionService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

@Service
public class FinanceVerificationService {
  private static final Set<String> VERIFIABLE_STATES = Set.of("RECOVERY_ENTRY", "PENDING", "PENDING_VERIFICATION");
  private static final Set<String> SALES_CLASSIFICATIONS = Set.of("Sales", "Charges", "Variation");
  private static final Set<String> BANK_INSTRUMENT_TYPES = Set.of("Online Transfer", "Cash", "Cheque", "DD", "PO");
  private final JdbcTemplate jdbc;
  private final PaymentAllocationReservationService reservations;
  private final FinanceVerificationAuditService failedAudit;
  private final UserPermissionService permissions;

  public FinanceVerificationService(JdbcTemplate jdbc, PaymentAllocationReservationService reservations,
                                   FinanceVerificationAuditService failedAudit,
                                   UserPermissionService permissions) {
    this.jdbc = jdbc;
    this.reservations = reservations;
    this.failedAudit = failedAudit;
    this.permissions = permissions;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> verify(Long paymentId, FinanceVerificationRequest request, String username) {
    try {
      if (request == null || request.verifiedSourceAmount() == null) bad("Verified source amount is required.");
      if (request.remarks() == null || request.remarks().isBlank()) bad("Finance verification remarks are required.");
      PaymentReservationSnapshot payment = reservations.validateExistingReservations(paymentId);
      requireVerifiable(payment);
      verifySourceDetails(payment, request);
      Long userId = requireFinanceUser(username);
      if (userId.equals(payment.enteredBy())) {
        throw new AccessDeniedException("The recovery-entry creator cannot verify or reject the same payment.");
      }

      BigDecimal sourceAmount = PaymentAllocationValidationService.currency(request.verifiedSourceAmount(), "Verified source amount");
      if (sourceAmount.compareTo(payment.amount()) != 0) bad("Verified source amount must equal the submitted payment amount.");
      int updated = jdbc.update("""
          UPDATE payments_receipts
          SET status = 'VERIFIED', verified_by = ?, verified_at = CURRENT_TIMESTAMP,
              verified_source_amount = ?, verification_reference = ?, verification_remarks = ?
          WHERE id = ? AND status IN ('RECOVERY_ENTRY','PENDING','PENDING_VERIFICATION')
          """, userId, sourceAmount, blankToNull(request.sourceReference()), blankToNull(request.remarks()), paymentId);
      if (updated != 1) bad("Payment status changed during verification. Reload and retry.");
      audit(username, "VERIFY", payment, "VERIFIED", "sourceAmount=" + sourceAmount
          + "; sourceReference=" + safe(request.sourceReference()) + "; remarks=" + safe(request.remarks()));
      return Map.of("id", paymentId, "receiptNo", payment.receiptNo(), "status", "VERIFIED",
          "verifiedSourceAmount", sourceAmount);
    } catch (ResponseStatusException ex) {
      failedAudit.recordFailedVerification(paymentId, username, ex.getReason() == null ? ex.getMessage() : ex.getReason());
      throw ex;
    } catch (AccessDeniedException ex) {
      failedAudit.recordFailedVerification(paymentId, username, ex.getMessage());
      throw ex;
    }
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> reject(Long paymentId, String reason, String username) {
    if (reason == null || reason.isBlank()) bad("Rejection reason is required.");
    PaymentReservationSnapshot payment = lockPayment(paymentId);
    requireVerifiable(payment);
    Long userId;
    try {
      userId = requireFinanceUser(username);
      if (userId.equals(payment.enteredBy())) {
        throw new AccessDeniedException("The recovery-entry creator cannot verify or reject the same payment.");
      }
    } catch (AccessDeniedException ex) {
      failedAudit.recordFailedVerification(paymentId, username, ex.getMessage());
      throw ex;
    }
    String cleanReason = reason.trim();
    if (cleanReason.length() > 500) bad("Rejection reason must be 500 characters or fewer.");
    int updated = jdbc.update("""
        UPDATE payments_receipts
        SET status = 'REJECTED', rejection_reason = ?, verified_by = ?, verified_at = CURRENT_TIMESTAMP,
            verification_remarks = ?
        WHERE id = ? AND status IN ('RECOVERY_ENTRY','PENDING','PENDING_VERIFICATION')
        """, cleanReason, userId, cleanReason, paymentId);
    if (updated != 1) bad("Payment status changed during rejection. Reload and retry.");
    audit(username, "REJECT", payment, "REJECTED", "reason=" + cleanReason);
    return Map.of("id", paymentId, "receiptNo", payment.receiptNo(), "status", "REJECTED");
  }

  private PaymentReservationSnapshot lockPayment(Long paymentId) {
    try {
      return jdbc.queryForObject("""
          SELECT id, customer_id, amount, status, receipt_no, payment_method,
                 payment_reference, sales_classification, bank_instrument_type, bank_id,
                 deposit_date, deposit_reference, cash_site_id, entered_by, verified_by
          FROM payments_receipts WHERE id = ? FOR UPDATE
          """, (rs, rowNum) -> new PaymentReservationSnapshot(rs.getLong("id"), rs.getLong("customer_id"),
          rs.getBigDecimal("amount"), rs.getString("status"), rs.getString("receipt_no"),
          rs.getString("payment_method"), rs.getString("payment_reference"), rs.getString("sales_classification"),
          rs.getString("bank_instrument_type"), rs.getObject("bank_id", Long.class),
          rs.getDate("deposit_date") == null ? null : rs.getDate("deposit_date").toLocalDate(),
          rs.getString("deposit_reference"), rs.getObject("cash_site_id", Long.class),
          rs.getObject("entered_by", Long.class), rs.getObject("verified_by", Long.class)), paymentId);
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment receipt not found");
    }
  }

  private void verifySourceDetails(PaymentReservationSnapshot payment, FinanceVerificationRequest request) {
    boolean hasStructuredMetadata = payment.salesClassification() != null || payment.bankInstrumentType() != null;
    if (hasStructuredMetadata && !SALES_CLASSIFICATIONS.contains(payment.salesClassification())) {
      bad("Receipt has an invalid or missing Sales Classification.");
    }
    if ("CASH".equalsIgnoreCase(payment.method())) {
      if (payment.cashSiteId() == null) bad("Cash payment must have a recorded cash collection site before verification.");
      if (hasStructuredMetadata && payment.bankInstrumentType() != null) {
        bad("Cash collection receipts cannot have a bank instrument type.");
      }
      return;
    }
    if (hasStructuredMetadata && !BANK_INSTRUMENT_TYPES.contains(payment.bankInstrumentType())) {
      bad("Receipt has an invalid or missing Bank Instrument Type.");
    }
    if (payment.bankId() == null) bad("Bank or online payment must have a recorded receiving bank before verification.");
    if (payment.depositDate() == null) bad("Bank or online payment must have a recorded deposit or instrument date before verification.");
    if (request.sourceReference() == null || request.sourceReference().isBlank()) {
      bad("Verified bank or payment source reference is required.");
    }
  }

  private Long requireFinanceUser(String username) {
    if (username == null || username.isBlank()) throw new AccessDeniedException("Authenticated Finance user is required.");
    if (!permissions.isGranted(username, "Finance", "RECEIPT_VERIFY")) {
      throw new AccessDeniedException("An active designation with receipt-verification permission is required.");
    }
    try {
      return jdbc.queryForObject("SELECT id FROM users WHERE username = ? AND is_active = 1", Long.class, username.trim());
    } catch (EmptyResultDataAccessException ex) {
      throw new AccessDeniedException("Active Finance user was not found.");
    }
  }

  private void requireVerifiable(PaymentReservationSnapshot payment) {
    if (!VERIFIABLE_STATES.contains(payment.status())) {
      bad("Only pending recovery entries can be verified or rejected. Current status: " + payment.status());
    }
  }

  private void audit(String username, String action, PaymentReservationSnapshot payment, String nextStatus, String extra) {
    String source = "method=" + safe(payment.method()) + "; bankId=" + payment.bankId()
        + "; paymentReference=" + safe(payment.paymentReference())
        + "; salesClassification=" + safe(payment.salesClassification())
        + "; bankInstrumentType=" + safe(payment.bankInstrumentType())
        + "; depositDate=" + payment.depositDate() + "; enteredReference=" + safe(payment.depositReference())
        + "; cashSiteId=" + payment.cashSiteId();
    String details = "paymentId=" + payment.paymentId() + "; receiptNo=" + payment.receiptNo()
        + "; previousStatus=" + payment.status() + "; newStatus=" + nextStatus
        + "; enteredBy preserved; " + source + "; " + extra;
    jdbc.update("INSERT INTO system_logs(username, action, module, details) VALUES (?, ?, ?, ?)",
        username, action, "FINANCE", details.length() > 500 ? details.substring(0, 500) : details);
  }

  private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private String safe(String value) { return value == null ? "" : value.replace(';', ',').replace('\r', ' ').replace('\n', ' ').trim(); }
  private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
