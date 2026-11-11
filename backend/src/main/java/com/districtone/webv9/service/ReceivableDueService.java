package com.districtone.webv9.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

/** Computes plot-scoped receivable balance and subtracts active, unposted reservations. */
@Service
public class ReceivableDueService {
  static final String ACTIVE_PAYMENT_STATES = "'RECOVERY_ENTRY','PENDING','PENDING_VERIFICATION','VERIFIED'";

  private final JdbcTemplate jdbc;

  public ReceivableDueService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public BigDecimal availableDue(Long receivableId, Long excludePaymentId) {
    BigDecimal due = jdbc.query("""
        SELECT vr.original_amount + vr.surcharge_amount - vr.adjustment_amount - vr.paid_amount
        FROM webv9_receivables vr
        JOIN registrations r ON r.registration_id = vr.registration_id AND r.member_id = vr.member_id
        JOIN sales_bookings b ON b.sale_booking_id = vr.booking_id
                             AND b.registration_id = vr.registration_id
        JOIN plots p ON p.plot_id = vr.plot_id AND p.plot_id = b.plot_id
        WHERE vr.id = ?
        """, rs -> rs.next() ? rs.getBigDecimal(1) : null, receivableId);
    if (due == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Legacy unscoped receivable cannot be used for plot-specific payment allocation.");
    }

    String reservationSql = """
        SELECT COALESCE(SUM(a.allocated_amount), 0)
        FROM webv9_payment_allocations a
        JOIN payments_receipts p ON p.id = a.payment_id
        WHERE a.receivable_id = ? AND p.status IN (
        """ + ACTIVE_PAYMENT_STATES + ") AND (? IS NULL OR p.id <> ?)";
    BigDecimal reserved = jdbc.queryForObject(reservationSql, BigDecimal.class,
        receivableId, excludePaymentId, excludePaymentId);
    BigDecimal available = due.subtract(reserved == null ? BigDecimal.ZERO : reserved);
    return available.max(BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP);
  }
}
