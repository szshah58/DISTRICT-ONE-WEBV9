package com.districtone.webv9.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reusable server-side validation for scoped, receivable-specific allocations. */
@Service
public class PaymentAllocationValidationService {
  private final JdbcTemplate jdbc;
  private final ReceivableDueService dueService;

  public PaymentAllocationValidationService(JdbcTemplate jdbc, ReceivableDueService dueService) {
    this.jdbc = jdbc;
    this.dueService = dueService;
  }

  public void validate(Long paymentId, Long memberId, BigDecimal headerAmount, List<AllocationDraft> lines) {
    if (paymentId == null || memberId == null) reject("Payment and member are required");
    if (lines == null || lines.isEmpty()) reject("At least one payment allocation is required");
    BigDecimal header = currency(headerAmount, "Payment header amount");
    if (header.signum() <= 0) reject("Payment header amount must be positive");

    BigDecimal allocationTotal = BigDecimal.ZERO.setScale(2);
    Map<Long, BigDecimal> perReceivable = new HashMap<>();
    Set<Integer> lineNumbers = new HashSet<>();
    for (AllocationDraft line : lines) {
      if (line == null) reject("Allocation line is required");
      if (line.lineNo() == null || line.lineNo() <= 0) reject("Allocation line number must be positive");
      if (!lineNumbers.add(line.lineNo())) reject("Allocation line numbers must be unique within a payment");
      if (line.registrationId() == null || line.bookingId() == null || line.plotId() == null
          || line.chargeTypeId() == null || line.receivableId() == null) {
        reject("Registration, booking, plot, charge type and receivable are required for each allocation");
      }
      BigDecimal amount = currency(line.allocatedAmount(), "Allocation amount");
      if (amount.signum() <= 0) reject("Allocation amount must be positive");
      allocationTotal = allocationTotal.add(amount);

      Integer plotTupleMatches = jdbc.queryForObject("""
          SELECT COUNT(*)
          FROM registrations r
          JOIN sales_bookings b ON b.registration_id = r.registration_id
          JOIN plots p ON p.plot_id = b.plot_id
          WHERE r.registration_id = ? AND b.sale_booking_id = ? AND p.plot_id = ?
            AND r.member_id = ?
          """, Integer.class, line.registrationId(), line.bookingId(), line.plotId(), memberId);
      if (plotTupleMatches == null || plotTupleMatches != 1) {
        reject("Selected registration, booking and plot do not match.");
      }

      Map<String, Object> receivable = findReceivable(line.receivableId());
      if (receivable == null) reject("Selected receivable does not exist.");
      if (receivable.get("registration_id") == null || receivable.get("booking_id") == null
          || receivable.get("plot_id") == null) {
        reject("Legacy unscoped receivable cannot be used for plot-specific payment allocation.");
      }
      if (!sameId(receivable.get("member_id"), memberId)
          || !sameId(receivable.get("registration_id"), line.registrationId())
          || !sameId(receivable.get("booking_id"), line.bookingId())
          || !sameId(receivable.get("plot_id"), line.plotId())) {
        reject("Selected receivable does not belong to the selected registration/booking/plot.");
      }
      if (!sameId(receivable.get("charge_type_id"), line.chargeTypeId())) {
        reject("Selected charge type does not match the selected receivable.");
      }
      Integer activeCharge = jdbc.queryForObject(
          "SELECT COUNT(*) FROM webv9_charge_types WHERE id = ? AND is_active = TRUE",
          Integer.class, line.chargeTypeId());
      if (activeCharge == null || activeCharge != 1) reject("Selected charge type is invalid or inactive.");
      if (line.installmentScheduleId() != null) {
        Integer scheduleMatches = jdbc.queryForObject("""
            SELECT COUNT(*) FROM webv9_instalment_schedules
            WHERE id = ? AND booking_id = ?
            """, Integer.class, line.installmentScheduleId(), line.bookingId());
        if (scheduleMatches == null || scheduleMatches != 1) {
          reject("Selected instalment schedule does not belong to the selected booking.");
        }
      }

      BigDecimal available = dueService.availableDue(line.receivableId(), paymentId);
      BigDecimal previous = perReceivable.getOrDefault(line.receivableId(), BigDecimal.ZERO.setScale(2));
      BigDecimal combined = previous.add(amount);
      if (combined.compareTo(available) > 0) {
        if (previous.signum() == 0) reject("Allocation amount exceeds available due for the selected receivable.");
        reject("Combined allocation amount exceeds available due for the selected receivable.");
      }
      perReceivable.put(line.receivableId(), combined);
    }

    if (allocationTotal.compareTo(header) != 0) {
      reject("Allocation total must equal payment total.");
    }
  }

  private Map<String, Object> findReceivable(Long receivableId) {
    try {
      return jdbc.queryForMap("""
          SELECT member_id, registration_id, booking_id, plot_id, charge_type_id
          FROM webv9_receivables WHERE id = ?
          """, receivableId);
    } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
      return null;
    }
  }

  private static boolean sameId(Object actual, Long expected) {
    return actual instanceof Number number && number.longValue() == expected;
  }

  public static BigDecimal currency(BigDecimal value, String label) {
    if (value == null) reject(label + " is required");
    try {
      return value.setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must have no more than two decimal places");
    }
  }

  private static void reject(String message) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
