package com.districtone.webv9.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Atomically replaces pending allocation rows, which act as due reservations until posting/rejection. */
@Service
public class PaymentAllocationReservationService {
  private static final Set<String> RESERVING_STATES = Set.of(
      "RECOVERY_ENTRY", "PENDING", "PENDING_VERIFICATION", "VERIFIED");

  private final JdbcTemplate jdbc;
  private final PaymentAllocationValidationService validation;

  public PaymentAllocationReservationService(JdbcTemplate jdbc, PaymentAllocationValidationService validation) {
    this.jdbc = jdbc;
    this.validation = validation;
  }

  /**
   * Locks the payment then all affected receivables in ascending id order, validates, and replaces
   * its reservation rows in one transaction. A rollback restores any prior reservation rows.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void replaceReservations(Long paymentId, List<AllocationDraft> lines, Long userId) {
    PaymentHeader payment;
    try {
      payment = jdbc.queryForObject("""
          SELECT customer_id, amount, status FROM payments_receipts WHERE id = ? FOR UPDATE
          """, (rs, rowNum) -> new PaymentHeader(rs.getLong("customer_id"),
          rs.getBigDecimal("amount"), rs.getString("status")), paymentId);
    } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment does not exist");
    }
    if (!RESERVING_STATES.contains(payment.status())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment is not in an active, unposted state");
    }

    if (lines == null || lines.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one payment allocation is required");
    }
    Set<Long> receivableIds = new TreeSet<>();
    lines.forEach(line -> {
      if (line.receivableId() != null) receivableIds.add(line.receivableId());
    });
    if (receivableIds.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receivable is required");

    String placeholders = String.join(",", java.util.Collections.nCopies(receivableIds.size(), "?"));
    List<Object> args = new ArrayList<>(receivableIds);
    List<Long> locked = jdbc.query("SELECT id FROM webv9_receivables WHERE id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
        (rs, rowNum) -> rs.getLong(1), args.toArray());
    if (locked.size() != receivableIds.size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more receivables do not exist");
    }

    validation.validate(paymentId, payment.memberId(), payment.amount(), lines);
    jdbc.update("DELETE FROM webv9_payment_allocations WHERE payment_id = ?", paymentId);

    for (AllocationDraft line : lines) {
      jdbc.update("""
          INSERT INTO webv9_payment_allocations
            (payment_id, line_no, registration_id, booking_id, plot_id, charge_type_id,
             receivable_id, installment_schedule_id, allocated_amount, line_remarks, created_by)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, paymentId, line.lineNo(), line.registrationId(), line.bookingId(), line.plotId(),
          line.chargeTypeId(), line.receivableId(), line.installmentScheduleId(),
          PaymentAllocationValidationService.currency(line.allocatedAmount(), "Allocation amount"),
          line.lineRemarks(), userId);
    }
  }

  /** Locks and revalidates the persisted reservation without changing allocation history. */
  public PaymentReservationSnapshot validateExistingReservations(Long paymentId) {
    PaymentReservationSnapshot payment = lockPayment(paymentId);
    if (!RESERVING_STATES.contains(payment.status())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment is not in an active, unposted state");
    }

    List<AllocationDraft> lines = jdbc.query("""
        SELECT line_no, registration_id, booking_id, plot_id, charge_type_id, receivable_id,
               installment_schedule_id, allocated_amount, line_remarks
        FROM webv9_payment_allocations WHERE payment_id = ? ORDER BY line_no
        """, (rs, rowNum) -> new AllocationDraft(rs.getInt("line_no"), rs.getLong("registration_id"),
        rs.getLong("booking_id"), rs.getLong("plot_id"), rs.getLong("charge_type_id"),
        rs.getObject("receivable_id", Long.class), rs.getObject("installment_schedule_id", Long.class),
        rs.getBigDecimal("allocated_amount"), rs.getString("line_remarks")), paymentId);
    if (lines.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment has no persisted allocation reservation.");
    }

    Set<Long> receivableIds = new TreeSet<>();
    lines.forEach(line -> {
      if (line.receivableId() != null) receivableIds.add(line.receivableId());
    });
    if (receivableIds.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment reservation has no receivable scope.");
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(receivableIds.size(), "?"));
    List<Object> args = new ArrayList<>(receivableIds);
    List<Long> locked = jdbc.query("SELECT id FROM webv9_receivables WHERE id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
        (rs, rowNum) -> rs.getLong(1), args.toArray());
    if (locked.size() != receivableIds.size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more reserved receivables no longer exist.");
    }
    validation.validate(paymentId, payment.memberId(), payment.amount(), lines);
    return payment;
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
    } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment does not exist");
    }
  }

  private record PaymentHeader(Long memberId, BigDecimal amount, String status) {}
}
