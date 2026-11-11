package com.districtone.webv9.service;

import com.districtone.webv9.util.FinanceReceiptNumbering;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Creates one recovery header and its due-reserving allocation lines atomically. */
@Service
public class RecoveryEntryCreationService {
  private final JdbcTemplate jdbc;
  private final PaymentAllocationReservationService reservations;

  public RecoveryEntryCreationService(JdbcTemplate jdbc, PaymentAllocationReservationService reservations) {
    this.jdbc = jdbc;
    this.reservations = reservations;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> create(RecoveryEntryRequest request, String submittedBy) {
    if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recovery entry is required");
    if (request.memberId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Member ID is required");
    if (request.paymentDate() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment date is required");
    if (request.method() == null || request.method().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment method is required");
    BigDecimal amount = PaymentAllocationValidationService.currency(request.amount(), "Payment total");
    if (amount.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment amount must be greater than zero");
    if (request.paymentDate().isAfter(LocalDate.now().plusDays(1))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment date cannot be in the future");
    }
    String method = request.method().trim().toUpperCase(java.util.Locale.ROOT);
    String salesClassification = null;
    String bankInstrumentType = null;
    if (request.allocationScopeBookingId() != null) {
      salesClassification = canonicalChoice(request.salesClassification(), Set.of("Sales", "Charges", "Variation"),
          "Sales classification is required and must be Sales, Charges, or Variation.");
      if ("CASH".equals(method)) {
        if (request.bankInstrumentType() != null && !request.bankInstrumentType().isBlank()) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
              "Bank instrument type only applies when the payment method is Bank.");
        }
      } else {
        bankInstrumentType = canonicalChoice(request.bankInstrumentType(),
            Set.of("Online Transfer", "Cash", "Cheque", "DD", "PO"),
            "Bank instrument type is required and must be Online Transfer, Cash, Cheque, DD, or PO.");
      }
    }
    switch (method) {
      case "CASH" -> {
        if (request.cashSiteId() == null) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cash payment requires a collection site.");
        }
      }
      case "BANK_TRANSFER", "ONLINE", "ONLINE_TRANSFER", "CHEQUE" -> {
        if (request.bankId() == null) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bank or online payment requires a receiving bank.");
        }
        if (request.referenceNo() == null || request.referenceNo().isBlank()) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
              "Bank or online payment requires a bank document number or transaction/reference number.");
        }
        if (request.depositDate() == null) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bank or online payment requires a deposit or instrument date.");
        }
      }
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payment method.");
    }
    List<AllocationDraft> lines = request.allocationLines();
    if (lines == null || lines.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one payment allocation is required");
    }
    validateReceiptEntryScope(request.memberId(), request.allocationScopeBookingId(), request.partyRoleType(), lines);
    ensureMemberExists(request.memberId());

    Long legacyHeaderChargeTypeId = singleChargeType(lines);
    if (legacyHeaderChargeTypeId != null && !isActive("webv9_charge_types", legacyHeaderChargeTypeId)) {
      legacyHeaderChargeTypeId = null; // The allocation validator returns the client-facing charge error.
    }
    if (request.bankId() != null && !isActive("webv9_banks", request.bankId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected bank does not exist or is inactive.");
    }
    if (request.cashSiteId() != null && !isActive("webv9_cash_sites", request.cashSiteId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected cash site does not exist or is inactive.");
    }
    Long headerChargeTypeId = legacyHeaderChargeTypeId;
    Long userId = findUserId(submittedBy);
    String receiptNo;
    Long paymentId;
    FinanceReceiptNumbering.acquire(jdbc);
    boolean releaseAfterCompletion = false;
    try {
      receiptNo = FinanceReceiptNumbering.next(jdbc);
      KeyHolder keys = new GeneratedKeyHolder();
      String finalReceiptNo = receiptNo;
      String finalSalesClassification = salesClassification;
      String finalBankInstrumentType = bankInstrumentType;
      jdbc.update(connection -> {
        PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO payments_receipts
              (customer_id, charge_type_id, receipt_no, payment_date, amount,
               payment_method, bank_id, receiving_account, payment_reference, deposit_reference, deposit_date,
               cash_site_id, sales_classification, bank_instrument_type, status, remarks, entered_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECOVERY_ENTRY', ?, ?)
            """, new String[] {"id"});
        statement.setLong(1, request.memberId());
        if (headerChargeTypeId == null) statement.setNull(2, java.sql.Types.BIGINT);
        else statement.setLong(2, headerChargeTypeId);
        statement.setString(3, finalReceiptNo);
        statement.setObject(4, request.paymentDate());
        statement.setBigDecimal(5, amount);
        statement.setString(6, method);
        if (request.bankId() == null) statement.setNull(7, java.sql.Types.BIGINT); else statement.setLong(7, request.bankId());
        statement.setString(8, blankToNull(request.receivingAccount()));
        statement.setString(9, blankToNull(request.paymentReference()));
        statement.setString(10, blankToNull(request.referenceNo()));
        if (request.depositDate() == null) statement.setNull(11, java.sql.Types.DATE); else statement.setObject(11, request.depositDate());
        if (request.cashSiteId() == null) statement.setNull(12, java.sql.Types.BIGINT); else statement.setLong(12, request.cashSiteId());
        statement.setString(13, finalSalesClassification);
        statement.setString(14, finalBankInstrumentType);
        statement.setString(15, blankToNull(request.remarks()));
        if (userId == null) statement.setNull(16, java.sql.Types.BIGINT); else statement.setLong(16, userId);
        return statement;
      }, keys);
      Number generatedKey = keys.getKey();
      if (generatedKey == null) throw new IllegalStateException("Could not retrieve the new payment ID");
      paymentId = generatedKey.longValue();
      FinanceReceiptNumbering.releaseAfterTransaction(jdbc);
      releaseAfterCompletion = true;

      reservations.replaceReservations(paymentId, lines, userId);
      jdbc.update("INSERT INTO system_logs(username, action, module, details) VALUES (?, 'CREATE', 'FINANCE', ?)",
          submittedBy == null || submittedBy.isBlank() ? "SYSTEM" : submittedBy,
          "Created recovery entry " + receiptNo + " for member ID=" + request.memberId() + " amount=" + amount
              + "; salesClassification=" + (salesClassification == null ? "" : salesClassification)
              + "; paymentReference=" + safeAudit(request.paymentReference())
              + "; bankInstrumentType=" + (bankInstrumentType == null ? "" : bankInstrumentType));
    } finally {
      if (!releaseAfterCompletion) FinanceReceiptNumbering.release(jdbc);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", paymentId); result.put("receiptNo", receiptNo); result.put("status", "RECOVERY_ENTRY");
    result.put("amount", amount); result.put("memberId", request.memberId()); result.put("allocationCount", lines.size());
    result.put("paymentReference", blankToNull(request.paymentReference()));
    result.put("salesClassification", salesClassification); result.put("bankInstrumentType", bankInstrumentType);
    return result;
  }

  /** Receipt Entry is intentionally confined to its selected booking and one line per charge head/due. */
  private void validateReceiptEntryScope(Long memberId, Long bookingId, String partyRoleType, List<AllocationDraft> lines) {
    if (bookingId == null) return; // Existing recovery callers retain their established allocation behavior.
    Set<Long> receivableIds = new HashSet<>();
    Set<Long> chargeTypeIds = new HashSet<>();
    for (AllocationDraft line : lines) {
      if (line == null || !bookingId.equals(line.bookingId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Receipt allocations must belong to the selected sale/booking.");
      }
      if (line.receivableId() == null || !receivableIds.add(line.receivableId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "A receipt cannot allocate the same receivable more than once.");
      }
      if (line.chargeTypeId() == null || !chargeTypeIds.add(line.chargeTypeId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "A receipt can allocate to each charge head only once.");
      }
    }
    if (partyRoleType == null || partyRoleType.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Party role type is required for Receipt Entry.");
    }
    Integer roleMatches = jdbc.queryForObject("""
        SELECT COUNT(*) FROM sales_bookings sb
        JOIN registrations r ON r.registration_id = sb.registration_id
        JOIN plots p ON p.plot_id = sb.plot_id
        WHERE sb.sale_booking_id = ? AND r.member_id = ? AND LOWER(p.plot_type) = LOWER(?)
        """, Integer.class, bookingId, memberId, partyRoleType.trim());
    if (roleMatches == null || roleMatches != 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Party role type does not match the selected customer's sale/booking.");
    }
  }

  private Long singleChargeType(List<AllocationDraft> lines) {
    Set<Long> chargeTypes = new LinkedHashSet<>();
    for (AllocationDraft line : lines) {
      if (line != null && line.chargeTypeId() != null) chargeTypes.add(line.chargeTypeId());
    }
    return chargeTypes.size() == 1 ? chargeTypes.iterator().next() : null;
  }

  private void ensureMemberExists(Long memberId) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE member_id = ?", Integer.class, memberId);
    if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
  }

  private boolean isActive(String table, Long id) {
    // The table name comes only from the fixed call sites above.
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ? AND is_active = TRUE",
        Integer.class, id);
    return count != null && count > 0;
  }

  private Long findUserId(String username) {
    if (username == null || username.isBlank()) return null;
    try {
      return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username.trim());
    } catch (EmptyResultDataAccessException ex) {
      return null;
    }
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String safeAudit(String value) {
    return value == null ? "" : value.replace(';', ',').replace('\r', ' ').replace('\n', ' ').trim();
  }

  private String canonicalChoice(String value, Set<String> choices, String error) {
    if (value != null) {
      for (String choice : choices) if (choice.equalsIgnoreCase(value.trim())) return choice;
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
  }
}

