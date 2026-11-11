package com.districtone.webv9.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/customer")
public class CustomerRegistrationLedgerController {
    private final JdbcTemplate jdbc;

    public CustomerRegistrationLedgerController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/registrations/{registrationId}/ledger")
    public ResponseEntity<?> getRegistrationLedger(
            @PathVariable Long registrationId,
            Authentication authentication) {
        String subject = authentication == null ? null : authentication.getName();
        if (subject == null || !subject.startsWith("customer:")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Customer authentication required."));
        }

        final Long accountId;
        try {
            accountId = Long.parseLong(subject.substring("customer:".length()));
        } catch (NumberFormatException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Invalid customer account."));
        }

        List<Map<String, Object>> registrations = jdbc.queryForList("""
            SELECT r.registration_id AS registrationId,
                   r.reg_no AS regNo,
                   r.form_no AS formNo,
                   r.category AS category,
                   m.member_id AS memberId,
                   m.mbr_no AS mbrNo,
                   m.name AS customerName
            FROM webv9_customer_accounts a
            JOIN registrations r ON r.member_id = a.member_id
            JOIN members m ON m.member_id = a.member_id
            WHERE a.account_id = ?
              AND a.is_active = 1
              AND a.registration_status = 'ACTIVE'
              AND r.registration_id = ?
            """, accountId, registrationId);
        if (registrations.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Selected registration was not found for this customer."));
        }

        Map<String, Object> registration = registrations.get(0);
        // Use registration-scoped receivables so all properties and charges for
        // this registration are represented, without importing unvalidated staging rows.
        List<Map<String, Object>> sourceRows = jdbc.queryForList("""
            SELECT vr.id AS ledgerId,
                   vr.due_date AS transactionDate,
                   CAST('' AS CHAR) AS referenceNo,
                   COALESCE(vr.description, ct.name, 'Account charge') AS description,
                   COALESCE(ct.code, 'CHARGE') AS transactionType,
                   (vr.original_amount + vr.surcharge_amount - vr.adjustment_amount) AS debit,
                   0 AS credit,
                   GREATEST(vr.original_amount + vr.surcharge_amount - vr.adjustment_amount - vr.paid_amount, 0) AS outstanding,
                   vr.surcharge_amount AS surcharge
            FROM webv9_receivables vr
            LEFT JOIN webv9_charge_types ct ON ct.id = vr.charge_type_id
            WHERE vr.registration_id = ?
            ORDER BY vr.due_date, vr.id
            """, registrationId);

        List<Map<String, Object>> receiptRows = jdbc.queryForList("""
            SELECT pr.id AS ledgerId,
                   pr.payment_date AS transactionDate,
                   pr.receipt_no AS referenceNo,
                   pr.payment_method AS paymentMethod,
                   pr.payment_reference AS paymentReference,
                   pr.sales_classification AS salesClassification,
                   pr.bank_instrument_type AS bankInstrumentType,
                   pr.deposit_reference AS depositReference,
                   CONCAT(pr.status, ' receipt - ', pr.payment_method,
                     CASE WHEN pr.sales_classification IS NULL THEN '' ELSE CONCAT(' - ', pr.sales_classification) END,
                     CASE WHEN pr.bank_instrument_type IS NULL THEN '' ELSE CONCAT(' - ', pr.bank_instrument_type) END,
                     ' - ', COALESCE(ct.name, 'Account charge')) AS description,
                   COALESCE(ct.code, 'PAYMENT') AS transactionType,
                   0 AS debit,
                   SUM(pa.allocated_amount) AS credit,
                   0 AS outstanding,
                   0 AS surcharge
            FROM payments_receipts pr
            JOIN webv9_payment_allocations pa ON pa.payment_id = pr.id
            LEFT JOIN webv9_charge_types ct ON ct.id = pa.charge_type_id
            WHERE pa.registration_id = ?
              AND pr.status IN ('VERIFIED', 'POSTED')
            GROUP BY pr.id, pr.payment_date, pr.receipt_no, pr.status, pr.payment_method,
                     pr.payment_reference, pr.sales_classification, pr.bank_instrument_type, ct.code, ct.name
            ORDER BY pr.payment_date, pr.id
            """, registrationId);
        sourceRows.addAll(receiptRows);

        // Legacy imported account lines are property-scoped only after an exact
        // registration match. Use them as a fallback when this property has no
        // operational WEBV9 receivables or payment allocations; otherwise the
        // historical lines could duplicate data already migrated into Finance.
        if (sourceRows.isEmpty()) {
            sourceRows.addAll(jdbc.queryForList("""
                SELECT s.staging_id AS ledgerId,
                       s.due_date AS transactionDate,
                       COALESCE(NULLIF(s.docno, ''), NULLIF(s.ds_no, ''), '') AS referenceNo,
                       COALESCE(NULLIF(s.acc_desc, ''), NULLIF(s.tran_desc, ''), NULLIF(s.acc_head, ''), 'Account charge') AS description,
                       COALESCE(NULLIF(s.acc_head, ''), NULLIF(s.doc_type, ''), 'CHARGE') AS transactionType,
                       (COALESCE(s.due_amount, 0) + COALESCE(s.sur_amount, 0)) AS debit,
                       CASE WHEN s.source_posted_flag = 1 THEN COALESCE(s.amount, 0) ELSE 0 END AS credit,
                       0 AS outstanding,
                       COALESCE(s.sur_amount, 0) AS surcharge
                FROM recovery_staging s
                WHERE s.matched_registration_id = ?
                  AND s.validation_status = 'MATCHED'
                ORDER BY s.due_date, s.staging_id
                """, registrationId));
        }

        sourceRows.sort((left, right) -> {
            Object leftDate = valueIgnoreCase(left, "transactionDate");
            Object rightDate = valueIgnoreCase(right, "transactionDate");
            if (leftDate == null && rightDate == null) return 0;
            if (leftDate == null) return 1;
            if (rightDate == null) return -1;
            return leftDate.toString().compareTo(rightDate.toString());
        });

        BigDecimal totalDue = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal totalSurcharge = BigDecimal.ZERO;
        BigDecimal developmentChargesDue = BigDecimal.ZERO;
        BigDecimal developmentChargesPaid = BigDecimal.ZERO;
        boolean hasDevelopmentChargeLedgerDue = false;
        List<Map<String, Object>> ledger = new ArrayList<>(sourceRows.size());
        for (Map<String, Object> row : sourceRows) {
            BigDecimal debit = decimal(valueIgnoreCase(row, "debit"));
            BigDecimal credit = decimal(valueIgnoreCase(row, "credit"));
            boolean developmentCharge = isDevelopmentCharge(row);
            totalDue = totalDue.add(debit);
            totalPaid = totalPaid.add(credit);
            outstanding = outstanding.add(decimal(valueIgnoreCase(row, "outstanding")));
            totalSurcharge = totalSurcharge.add(decimal(valueIgnoreCase(row, "surcharge")));
            if (developmentCharge) {
                developmentChargesDue = developmentChargesDue.add(debit);
                developmentChargesPaid = developmentChargesPaid.add(credit);
                hasDevelopmentChargeLedgerDue |= debit.signum() > 0;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ledgerId", valueIgnoreCase(row, "ledgerId"));
            entry.put("transactionDate", valueIgnoreCase(row, "transactionDate"));
            entry.put("referenceNo", valueIgnoreCase(row, "referenceNo"));
            entry.put("description", valueIgnoreCase(row, "description"));
            entry.put("transactionType", valueIgnoreCase(row, "transactionType"));
            entry.put("debit", debit);
            entry.put("credit", credit);
            entry.put("outstanding", decimal(valueIgnoreCase(row, "outstanding")));
            entry.put("surcharge", decimal(valueIgnoreCase(row, "surcharge")));
            ledger.add(entry);
        }
        // The legacy Development Charges account stores the May 2026 installment
        // separately from WEBV9 receivables. Include it only when no assessed or
        // matched Development Charges debit is already present for this property.
        if (!hasDevelopmentChargeLedgerDue) {
            BigDecimal scheduledDevelopmentCharge = jdbc.queryForObject("""
                SELECT COALESCE(SUM(installment_may_2026), 0)
                FROM development_charge_accounts
                WHERE registration_id = ?
                """, BigDecimal.class, registrationId);
            BigDecimal scheduleDue = decimal(scheduledDevelopmentCharge);
            developmentChargesDue = developmentChargesDue.add(scheduleDue);
            totalDue = totalDue.add(scheduleDue);
            if (scheduleDue.signum() > 0) {
                Map<String, Object> scheduledDevelopmentChargeRow = new LinkedHashMap<>();
                scheduledDevelopmentChargeRow.put("ledgerId", "DC-SCHEDULE-" + registrationId);
                scheduledDevelopmentChargeRow.put("transactionDate", null);
                scheduledDevelopmentChargeRow.put("referenceNo", "MAY-2026");
                scheduledDevelopmentChargeRow.put("description", "Development Charges - May 2026 installment");
                scheduledDevelopmentChargeRow.put("transactionType", "DEVELOPMENT_CHARGES");
                scheduledDevelopmentChargeRow.put("debit", scheduleDue);
                scheduledDevelopmentChargeRow.put("credit", BigDecimal.ZERO);
                scheduledDevelopmentChargeRow.put(
                    "outstanding",
                    scheduleDue.subtract(developmentChargesPaid).max(BigDecimal.ZERO));
                scheduledDevelopmentChargeRow.put("surcharge", BigDecimal.ZERO);
                ledger.add(scheduledDevelopmentChargeRow);
            }
        }
        BigDecimal developmentChargesBalance = developmentChargesDue.subtract(developmentChargesPaid);
        // Payments come directly from receipts, regardless of posted_at. This
        // avoids both omitting valid receipts and double-counting paid_amount
        // already reflected in receivables.
        outstanding = totalDue.subtract(totalPaid);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("registration", registration);
        response.put("registrationId", registrationId);
        response.put("totalDue", totalDue);
        response.put("totalPaid", totalPaid);
        response.put("outstanding", outstanding);
        response.put("developmentChargesDue", developmentChargesDue);
        response.put("developmentChargesPaid", developmentChargesPaid);
        response.put("developmentChargesBalance", developmentChargesBalance);
        response.put("totalSurcharge", totalSurcharge);
        response.put("ledger", ledger);
        return ResponseEntity.ok(response);
    }

    private static Object valueIgnoreCase(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> item : row.entrySet()) {
            if (item.getKey().equalsIgnoreCase(key)) return item.getValue();
        }
        return null;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal amount) return amount;
        return new BigDecimal(value.toString());
    }

    private static boolean isDevelopmentCharge(Map<String, Object> row) {
        String head = String.valueOf(valueIgnoreCase(row, "transactionType"));
        String description = String.valueOf(valueIgnoreCase(row, "description"));
        String normalized = (head + " " + description).toLowerCase().replace('_', ' ');
        return normalized.contains("development")
                || normalized.matches(".*\\b(dca|dc)\\b.*");
    }
}
