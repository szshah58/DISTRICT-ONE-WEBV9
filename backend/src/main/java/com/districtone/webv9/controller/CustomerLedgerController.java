package com.districtone.webv9.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/customer")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerLedgerController {

    private final JdbcTemplate jdbc;

    public CustomerLedgerController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/properties/{bookingId}/ledger")
    public ResponseEntity<?> getPropertyLedger(
            @PathVariable Long bookingId,
            Authentication authentication) {

        String subject = authentication.getName();

        if (subject == null || !subject.startsWith("customer:")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    Map.of("message", "Customer authentication required.")
            );
        }

        Long accountId;
        try {
            accountId = Long.parseLong(subject.substring("customer:".length()));
        } catch (NumberFormatException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    Map.of("message", "Invalid customer account.")
            );
        }

        // Resolve the exact booking only through the authenticated active account.
        List<Map<String, Object>> property = jdbc.queryForList("""
            SELECT
                r.registration_id AS registrationId,
                r.reg_no AS regNo,
                r.form_no AS formNo,
                r.category AS category,
                p.plot_id AS plotId,
                p.plot_no AS plotNo,
                p.plot_size_marla AS plotSizeMarla,
                p.plot_type AS plotType,
                b.block_code AS blockCode,
                b.block_name AS blockName,
                sb.sale_booking_id AS bookingId
            FROM webv9_customer_accounts a
            JOIN registrations r
                ON r.member_id = a.member_id
            LEFT JOIN sales_bookings sb
                ON sb.registration_id = r.registration_id
                AND sb.sale_status <> 'CANCELLED'
            LEFT JOIN plots p
                ON p.plot_id = sb.plot_id
            LEFT JOIN blocks b
                ON b.block_id = p.block_id
            WHERE a.account_id = ?
              AND a.is_active = 1
              AND a.registration_status = 'ACTIVE'
              AND sb.sale_booking_id = ?
              AND sb.sale_status <> 'CANCELLED'
            """, accountId, bookingId);

        if (property.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("message", "Selected property was not found for this customer.")
            );
        }

        Map<String, Object> selectedProperty = property.get(0);
        Object selectedBookingId = selectedProperty.get("bookingId");

        // Calculate totals from the new receivables system for the specific booking.
        // The ledger is per-booking, so totals should only include receivables for this booking.
        // Use the specific booking_id returned by the property query.
        if (selectedBookingId == null) {
            // No booking exists for this registration - return zero totals
            return ResponseEntity.ok(Map.of(
                    "property", selectedProperty,
                    "totalDue", BigDecimal.ZERO,
                    "totalPaid", BigDecimal.ZERO,
                    "outstanding", BigDecimal.ZERO,
                    "ledger", List.of()
            ));
        }

        Map<String, Object> totals = jdbc.queryForMap("""
            SELECT
                COALESCE(SUM(vr.original_amount + vr.surcharge_amount - vr.adjustment_amount), 0) AS totalDue,
                COALESCE(SUM(vr.paid_amount), 0) AS totalPaid,
                COALESCE(SUM(vr.original_amount + vr.surcharge_amount - vr.adjustment_amount - vr.paid_amount), 0) AS outstanding
            FROM webv9_receivables vr
            WHERE vr.booking_id = ?
            """, selectedBookingId);

        BigDecimal totalDue = decimal(totals.get("totalDue"));
        BigDecimal totalPaid = decimal(totals.get("totalPaid"));
        BigDecimal outstanding = decimal(totals.get("outstanding"));

        // Get member_id for historical payments query
        // payments_receipts.customer_id references members.member_id
        Long historicalMemberId = (Long) jdbc.queryForObject(
            "SELECT member_id FROM webv9_customer_accounts WHERE account_id = ?", Long.class, accountId);

        // Add historical verified payments from payments_receipts that have NOT been posted
        // These are receipts verified before the posting workflow was implemented
        // We only add them if they are NOT already included in webv9_customer_ledger
        // (i.e., they have posted_at IS NULL)
        // Only include payments for the specific booking
        // Registration-level payments (booking_id IS NULL) cannot be safely attributed to a specific booking
        // and should be excluded from the per-booking ledger
        Map<String, Object> historicalTotals = jdbc.queryForMap("""
            SELECT
                COALESCE(SUM(pr.amount), 0) AS historicalPaid
            FROM payments_receipts pr
            WHERE pr.booking_id = ?
              AND pr.status = 'VERIFIED'
              AND pr.posted_at IS NULL
            """, selectedBookingId);

        BigDecimal historicalPaid = decimal(historicalTotals.get("historicalPaid"));
        totalPaid = totalPaid.add(historicalPaid);
        // Recalculate outstanding after adding historical payments
        outstanding = totalDue.subtract(totalPaid);

        // Query ledger entries from both the new webv9_customer_ledger and the original payments table
        List<Map<String, Object>> ledger = new ArrayList<>();

        // Add entries from the new webv9_customer_ledger table
        if (selectedBookingId != null) {
            List<Map<String, Object>> newLedger = jdbc.queryForList("""
                SELECT
                    l.id AS ledgerId,
                    l.member_id AS memberId,
                    l.booking_id AS bookingId,
                    l.payment_id AS paymentId,
                    l.debit AS debit,
                    l.credit AS credit,
                    l.transaction_date AS transactionDate,
                    l.transaction_type AS transactionType,
                    l.reference_no AS referenceNo,
                    l.description AS description
                FROM webv9_customer_ledger l
                JOIN sales_bookings sb
                    ON sb.sale_booking_id = l.booking_id
                WHERE sb.sale_booking_id = ?
                ORDER BY l.id
                """, selectedBookingId);
            newLedger.forEach(row -> ledger.add(new java.util.HashMap<>(row)));
        }

        // Add historical verified payments from payments_receipts that have not been posted
        // These are receipts that were verified before the posting workflow was implemented
        // Only include payments for the specific booking
        // Registration-level payments (booking_id IS NULL) cannot be safely attributed to a specific booking
        // and should be excluded from the per-booking ledger
        if (selectedBookingId != null) {
            List<Map<String, Object>> historicalPayments = jdbc.queryForList("""
                SELECT
                    pr.id AS ledgerId,
                    pr.customer_id AS memberId,
                    pr.booking_id AS bookingId,
                    pr.id AS paymentId,
                    0 AS debit,
                    pr.amount AS credit,
                    CAST(pr.payment_date AS TIMESTAMP) AS transactionDate,
                    'PAYMENT' AS transactionType,
                    pr.receipt_no AS referenceNo,
                    pr.payment_method AS paymentMethod,
                    pr.payment_reference AS paymentReference,
                    pr.deposit_reference AS depositReference,
                    pr.sales_classification AS salesClassification,
                    pr.bank_instrument_type AS bankInstrumentType,
                    CONCAT('Historical payment: ', pr.payment_method) AS description
                FROM payments_receipts pr
                WHERE pr.customer_id = ?
                  AND pr.booking_id = ?
                  AND pr.status = 'VERIFIED'
                  AND pr.posted_at IS NULL
                ORDER BY pr.payment_date, pr.id
                """, historicalMemberId, selectedBookingId);
            historicalPayments.forEach(row -> ledger.add(new java.util.HashMap<>(row)));
        }

        // Sort combined ledger by date and calculate running balance
        ledger.sort((a, b) -> {
            java.time.LocalDateTime dateA = (java.time.LocalDateTime) a.get("transactionDate");
            java.time.LocalDateTime dateB = (java.time.LocalDateTime) b.get("transactionDate");
            if (dateA == null && dateB == null) return 0;
            if (dateA == null) return 1;
            if (dateB == null) return -1;
            int dateCompare = dateA.compareTo(dateB);
            if (dateCompare != 0) return dateCompare;
            // If dates are equal, sort by ID
            Object idA = a.get("ledgerId");
            Object idB = b.get("ledgerId");
            if (idA == null && idB == null) return 0;
            if (idA == null) return 1;
            if (idB == null) return -1;
            return ((Comparable) idA).compareTo(idB);
        });

        // Calculate running balance
        BigDecimal runningBalance = BigDecimal.ZERO;
        for (Map<String, Object> entry : ledger) {
            BigDecimal debit = decimal(valueIgnoreCase(entry, "debit"));
            BigDecimal credit = decimal(valueIgnoreCase(entry, "credit"));
            runningBalance = runningBalance.add(debit).subtract(credit);
            entry.put("runningBalance", runningBalance);
        }

        return ResponseEntity.ok(Map.of(
                "property", selectedProperty,
                "totalDue", totalDue,
                "totalPaid", totalPaid,
                "outstanding", outstanding,
                "ledger", ledger
        ));
    }

    private static Object valueIgnoreCase(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> item : row.entrySet()) {
            if (item.getKey().equalsIgnoreCase(key)) {
                return item.getValue();
            }
        }
        return null;
    }
    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}






