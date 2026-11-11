package com.districtone.webv9.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/system/database-verification")
public class DatabaseVerificationController {
  private final JdbcTemplate jdbc;
  private final String token;

  public DatabaseVerificationController(JdbcTemplate jdbc,
                                        @Value("${app.database-verification-token:}") String token) {
    this.jdbc = jdbc;
    this.token = token;
  }

  @PostMapping
  public Map<String, Object> verify(@RequestHeader("X-WEBV9-DB-VERIFY") String suppliedToken) {
    if (token.isBlank() || !token.equals(suppliedToken)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "Not found");
    }
    Long customerId = jdbc.queryForObject("SELECT member_id FROM members ORDER BY member_id LIMIT 1", Long.class);
    String receiptNo = "WEBV9-CHECK-" + UUID.randomUUID();
    jdbc.update("""
        INSERT INTO payments_receipts
          (customer_id, receipt_no, payment_date, amount, payment_method, status)
        VALUES (?, ?, ?, ?, ?, ?)
        """, customerId, receiptNo, LocalDate.now(), new BigDecimal("0.01"), "OTHER", "RECOVERY_ENTRY");
    Map<String, Object> persisted = jdbc.queryForMap(
        "SELECT receipt_no, customer_id, amount, status FROM payments_receipts WHERE receipt_no = ?",
        receiptNo);
    Long paymentId = jdbc.queryForObject("SELECT id FROM payments_receipts WHERE receipt_no = ?", Long.class, receiptNo);
    jdbc.update("""
        INSERT INTO webv9_customer_ledger
          (member_id, payment_id, transaction_type, reference_no, credit, description)
        VALUES (?, ?, ?, ?, ?, ?)
        """, customerId, paymentId, "PAYMENT", receiptNo, new BigDecimal("0.01"), "WEBV9 database verification");
    Map<String, Object> ledger = jdbc.queryForMap(
        "SELECT member_id, payment_id, credit, transaction_type FROM webv9_customer_ledger WHERE payment_id = ?",
        paymentId);
    jdbc.update("DELETE FROM webv9_customer_ledger WHERE payment_id = ?", paymentId);
    jdbc.update("DELETE FROM payments_receipts WHERE receipt_no = ?", receiptNo);
    return Map.of("database", jdbc.queryForObject("SELECT DATABASE()", String.class),
        "querySucceeded", true, "insertReadSucceeded", true, "verifiedReceipt", persisted, "verifiedLedger", ledger);
  }
}
