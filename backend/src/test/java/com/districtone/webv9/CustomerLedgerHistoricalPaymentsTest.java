package com.districtone.webv9;

import com.districtone.webv9.controller.CustomerLedgerController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the customer ledger API to ensure it correctly returns
 * both new WEBV9 ledger entries and historical verified payments.
 */
class CustomerLedgerHistoricalPaymentsTest {

    private JdbcTemplate jdbc;
    private CustomerLedgerController controller;

    // Helper method to get values case-insensitively from H2 result maps
    private static Object valueIgnoreCase(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> item : row.entrySet()) {
            if (item.getKey().equalsIgnoreCase(key)) {
                return item.getValue();
            }
        }
        return null;
    }

    @BeforeEach
    void createSchemaAndFixture() {
        var ds = new DriverManagerDataSource(
            "jdbc:h2:mem:historical-payments-test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        controller = new CustomerLedgerController(jdbc);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE webv9_customer_accounts (account_id BIGINT PRIMARY KEY, member_id BIGINT, is_active BOOLEAN, registration_status VARCHAR(20))");
        jdbc.execute("CREATE TABLE members (member_id BIGINT PRIMARY KEY, mbr_no VARCHAR(30), name VARCHAR(200), cnic_no VARCHAR(30))");
        jdbc.execute("CREATE TABLE registrations (registration_id BIGINT PRIMARY KEY, member_id BIGINT, reg_no VARCHAR(30), form_no VARCHAR(100), category VARCHAR(100))");
        jdbc.execute("CREATE TABLE sales_bookings (sale_booking_id BIGINT PRIMARY KEY, registration_id BIGINT, plot_id BIGINT, sale_status VARCHAR(50))");
        jdbc.execute("CREATE TABLE plots (plot_id BIGINT PRIMARY KEY, block_id BIGINT, plot_no VARCHAR(50), plot_size_marla DECIMAL(6,2), plot_type VARCHAR(50), category VARCHAR(100))");
        jdbc.execute("CREATE TABLE blocks (block_id BIGINT PRIMARY KEY, block_code VARCHAR(50), block_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE webv9_receivables (id BIGINT PRIMARY KEY, member_id BIGINT, registration_id BIGINT, booking_id BIGINT, plot_id BIGINT, original_amount DECIMAL(16,2), paid_amount DECIMAL(16,2), surcharge_amount DECIMAL(16,2), adjustment_amount DECIMAL(16,2), status VARCHAR(30), description VARCHAR(500))");
        jdbc.execute("CREATE TABLE webv9_customer_ledger (id BIGINT PRIMARY KEY, member_id BIGINT, booking_id BIGINT, payment_id BIGINT, transaction_date TIMESTAMP, transaction_type VARCHAR(50), reference_no VARCHAR(100), description VARCHAR(500), debit DECIMAL(16,2), credit DECIMAL(16,2))");
        jdbc.execute("CREATE TABLE payments_receipts (id BIGINT PRIMARY KEY, customer_id BIGINT, booking_id BIGINT, amount DECIMAL(16,2), status VARCHAR(30), posted_at TIMESTAMP, payment_date TIMESTAMP, payment_method VARCHAR(50), payment_reference VARCHAR(120), deposit_reference VARCHAR(120), sales_classification VARCHAR(20), bank_instrument_type VARCHAR(24), receipt_no VARCHAR(100))");
    }

    /**
     * Test that the ledger API includes historical verified payments
     * that have not been posted through the new workflow.
     */
    @Test
    void ledgerIncludesHistoricalVerifiedPaymentsWithBookingId() {
        // Create customer account and member
        jdbc.update("INSERT INTO members(member_id, mbr_no, name, cnic_no) VALUES (?, ?, ?, ?)",
            999, "MBR-999", "Test Customer", "54400-1234567-1");
        jdbc.update("INSERT INTO webv9_customer_accounts(account_id, member_id, is_active, registration_status) VALUES (?, ?, TRUE, 'ACTIVE')",
            999, 999);

        // Create registration and booking
        jdbc.update("INSERT INTO registrations(registration_id, member_id, reg_no, form_no, category) VALUES (?, ?, ?, ?, ?)",
            999, 999, "TEST-999", "TEST-FORM-999", "RESIDENTIAL");
        jdbc.update("INSERT INTO plots(plot_id, block_id, plot_no, plot_size_marla, plot_type, category) VALUES (?, 1, 'P-999', 5.0, 'RESIDENTIAL', 'RESIDENTIAL')",
            999);
        jdbc.update("INSERT INTO sales_bookings(sale_booking_id, registration_id, plot_id, sale_status) VALUES (?, ?, ?, 'ACTIVE')",
            999, 999, 999);

        // Create a historical verified payment with booking_id (not posted)
        jdbc.update("""
            INSERT INTO payments_receipts(id, customer_id, booking_id, receipt_no, payment_date, amount,
             payment_method, status, posted_at)
            VALUES (?, ?, ?, ?, CURDATE(), ?, 'CASH', 'VERIFIED', NULL)
            """, 999, 999, 999, "HIST-001", new BigDecimal("50000"));

        // Create receivables for the property
        jdbc.update("INSERT INTO webv9_receivables(id, member_id, registration_id, booking_id, plot_id, original_amount, paid_amount, surcharge_amount, adjustment_amount, status, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            1, 999, 999, 999, 999, new BigDecimal("100000"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), "DUE", "Test charge");

        var response = controller.getPropertyLedger(999L,
            UsernamePasswordAuthenticationToken.authenticated("customer:999", "", List.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("totalDue", new BigDecimal("100000.00"))
            .containsEntry("totalPaid", new BigDecimal("50000.00"))
            .containsEntry("outstanding", new BigDecimal("50000.00"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ledger = (List<Map<String, Object>>) body.get("ledger");
        assertThat(ledger).hasSize(1);
        assertThat(valueIgnoreCase(ledger.get(0), "transactionType")).isEqualTo("PAYMENT");
        assertThat(valueIgnoreCase(ledger.get(0), "credit")).isEqualTo(new BigDecimal("50000.00"));
    }

    /**
     * Test that the ledger API EXCLUDES historical verified payments
     * without booking_id (registration-level payments) from the per-booking ledger.
     * Registration-level payments cannot be safely attributed to a specific booking.
     */
    @Test
    void ledgerExcludesRegistrationLevelPaymentsFromBookingLedger() {
        // Create customer account and member
        jdbc.update("INSERT INTO members(member_id, mbr_no, name, cnic_no) VALUES (?, ?, ?, ?)",
            998, "MBR-998", "Test Customer 2", "54400-1234567-2");
        jdbc.update("INSERT INTO webv9_customer_accounts(account_id, member_id, is_active, registration_status) VALUES (?, ?, TRUE, 'ACTIVE')",
            998, 998);

        // Create registration and booking
        jdbc.update("INSERT INTO registrations(registration_id, member_id, reg_no, form_no, category) VALUES (?, ?, ?, ?, ?)",
            998, 998, "TEST-998", "TEST-FORM-998", "RESIDENTIAL");
        jdbc.update("INSERT INTO plots(plot_id, block_id, plot_no, plot_size_marla, plot_type, category) VALUES (?, 1, 'P-998', 5.0, 'RESIDENTIAL', 'RESIDENTIAL')",
            998);
        jdbc.update("INSERT INTO sales_bookings(sale_booking_id, registration_id, plot_id, sale_status) VALUES (?, ?, ?, 'ACTIVE')",
            998, 998, 998);

        // Create a historical verified payment WITHOUT booking_id (registration-level)
        // This should be EXCLUDED from the per-booking ledger
        jdbc.update("""
            INSERT INTO payments_receipts(id, customer_id, booking_id, receipt_no, payment_date, amount,
             payment_method, status, posted_at)
            VALUES (?, ?, NULL, ?, CURDATE(), ?, 'CASH', 'VERIFIED', NULL)
            """, 998, 998, "REG-LEVEL-EXCLUDED", new BigDecimal("30000"));

        // Create receivables for the property
        jdbc.update("INSERT INTO webv9_receivables(id, member_id, registration_id, booking_id, plot_id, original_amount, paid_amount, surcharge_amount, adjustment_amount, status, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            1, 998, 998, 998, 998, new BigDecimal("100000"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), "DUE", "Test charge");

        var response = controller.getPropertyLedger(998L,
            UsernamePasswordAuthenticationToken.authenticated("customer:998", "", List.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        // Registration-level payment should be EXCLUDED from per-booking ledger
        assertThat(body).containsEntry("totalDue", new BigDecimal("100000.00"))
            .containsEntry("totalPaid", new BigDecimal("0.00"))
            .containsEntry("outstanding", new BigDecimal("100000.00"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ledger = (List<Map<String, Object>>) body.get("ledger");
        assertThat(ledger).isEmpty();
    }

    /**
     * Test that the ledger API does not double-count payments that are
     * both in webv9_customer_ledger and in payments_receipts.
     */
    @Test
    void ledgerDoesNotDoubleCountPostedPayments() {
        // Create customer account and member
        jdbc.update("INSERT INTO members(member_id, mbr_no, name, cnic_no) VALUES (?, ?, ?, ?)",
            997, "MBR-997", "Test Customer 3", "54400-1234567-3");
        jdbc.update("INSERT INTO webv9_customer_accounts(account_id, member_id, is_active, registration_status) VALUES (?, ?, TRUE, 'ACTIVE')",
            997, 997);

        // Create registration and booking
        jdbc.update("INSERT INTO registrations(registration_id, member_id, reg_no, form_no, category) VALUES (?, ?, ?, ?, ?)",
            997, 997, "TEST-997", "TEST-FORM-997", "RESIDENTIAL");
        jdbc.update("INSERT INTO plots(plot_id, block_id, plot_no, plot_size_marla, plot_type, category) VALUES (?, 1, 'P-997', 5.0, 'RESIDENTIAL', 'RESIDENTIAL')",
            997);
        jdbc.update("INSERT INTO sales_bookings(sale_booking_id, registration_id, plot_id, sale_status) VALUES (?, ?, ?, 'ACTIVE')",
            997, 997, 997);

        // Create a posted payment (in webv9_customer_ledger)
        jdbc.update("""
            INSERT INTO payments_receipts(id, customer_id, booking_id, receipt_no, payment_date, amount,
             payment_method, status, posted_at)
            VALUES (?, ?, ?, ?, CURDATE(), ?, 'CASH', 'POSTED', NOW())
            """, 997, 997, 997, "POST-001", new BigDecimal("30000"));

        jdbc.update("""
            INSERT INTO webv9_customer_ledger(id, member_id, booking_id, payment_id, transaction_date,
             transaction_type, reference_no, debit, credit, description)
            VALUES (?, ?, ?, ?, NOW(), 'PAYMENT', ?, 0, ?, 'Posted payment')
            """, 997, 997, 997, 997, "POST-001", new BigDecimal("30000"));

        // Create receivables for the property
        jdbc.update("INSERT INTO webv9_receivables(id, member_id, registration_id, booking_id, plot_id, original_amount, paid_amount, surcharge_amount, adjustment_amount, status, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            1, 997, 997, 997, 997, new BigDecimal("100000"), new BigDecimal("30000"), new BigDecimal("0"), new BigDecimal("0"), "PARTIAL", "Test charge");

        var response = controller.getPropertyLedger(997L,
            UsernamePasswordAuthenticationToken.authenticated("customer:997", "", List.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        // The posted payment should be counted once from webv9_customer_ledger (via webv9_receivables.paid_amount)
        // NOT from payments_receipts
        assertThat(body).containsEntry("totalDue", new BigDecimal("100000.00"))
            .containsEntry("totalPaid", new BigDecimal("30000.00"))
            .containsEntry("outstanding", new BigDecimal("70000.00"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ledger = (List<Map<String, Object>>) body.get("ledger");
        // Should only have the ledger entry from webv9_customer_ledger, not the historical payment
        assertThat(ledger).hasSize(1);
        assertThat(valueIgnoreCase(ledger.get(0), "transactionType")).isEqualTo("PAYMENT");
        assertThat(valueIgnoreCase(ledger.get(0), "credit")).isEqualTo(new BigDecimal("30000.00"));
    }

    /**
     * Test that customers with no ledger entries and no historical payments
     * return zero totals correctly.
     */
    @Test
    void ledgerWithNoPaymentsReturnsZero() {
        // Create customer account and member with no payments
        jdbc.update("INSERT INTO members(member_id, mbr_no, name, cnic_no) VALUES (?, ?, ?, ?)",
            996, "MBR-996", "Test Customer 4", "54400-1234567-4");
        jdbc.update("INSERT INTO webv9_customer_accounts(account_id, member_id, is_active, registration_status) VALUES (?, ?, TRUE, 'ACTIVE')",
            996, 996);

        // Create registration and booking
        jdbc.update("INSERT INTO registrations(registration_id, member_id, reg_no, form_no, category) VALUES (?, ?, ?, ?, ?)",
            996, 996, "TEST-996", "TEST-FORM-996", "RESIDENTIAL");
        jdbc.update("INSERT INTO plots(plot_id, block_id, plot_no, plot_size_marla, plot_type, category) VALUES (?, 1, 'P-996', 5.0, 'RESIDENTIAL', 'RESIDENTIAL')",
            996);
        jdbc.update("INSERT INTO sales_bookings(sale_booking_id, registration_id, plot_id, sale_status) VALUES (?, ?, ?, 'ACTIVE')",
            996, 996, 996);

        // Create receivables for the property
        jdbc.update("INSERT INTO webv9_receivables(id, member_id, registration_id, booking_id, plot_id, original_amount, paid_amount, surcharge_amount, adjustment_amount, status, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            1, 996, 996, 996, 996, new BigDecimal("100000"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), "DUE", "Test charge");

        var response = controller.getPropertyLedger(996L,
            UsernamePasswordAuthenticationToken.authenticated("customer:996", "", List.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("totalDue", new BigDecimal("100000.00"))
            .containsEntry("totalPaid", new BigDecimal("0.00"))
            .containsEntry("outstanding", new BigDecimal("100000.00"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ledger = (List<Map<String, Object>>) body.get("ledger");
        assertThat(ledger).isEmpty();
    }

    /**
     * Test that historical payments from another customer are not included.
     */
    @Test
    void ledgerExcludesHistoricalPaymentsFromAnotherCustomer() {
        // Create customer account and member
        jdbc.update("INSERT INTO members(member_id, mbr_no, name, cnic_no) VALUES (?, ?, ?, ?)",
            995, "MBR-995", "Test Customer 5", "54400-1234567-5");
        jdbc.update("INSERT INTO webv9_customer_accounts(account_id, member_id, is_active, registration_status) VALUES (?, ?, TRUE, 'ACTIVE')",
            995, 995);

        // Create another customer
        jdbc.update("INSERT INTO members(member_id, mbr_no, name, cnic_no) VALUES (?, ?, ?, ?)",
            894, "MBR-894", "Other Customer", "54400-7654321-9");
        jdbc.update("INSERT INTO webv9_customer_accounts(account_id, member_id, is_active, registration_status) VALUES (?, ?, TRUE, 'ACTIVE')",
            894, 894);

        // Create registration and booking for first customer
        jdbc.update("INSERT INTO registrations(registration_id, member_id, reg_no, form_no, category) VALUES (?, ?, ?, ?, ?)",
            995, 995, "TEST-995", "TEST-FORM-995", "RESIDENTIAL");
        jdbc.update("INSERT INTO plots(plot_id, block_id, plot_no, plot_size_marla, plot_type, category) VALUES (?, 1, 'P-995', 5.0, 'RESIDENTIAL', 'RESIDENTIAL')",
            995);
        jdbc.update("INSERT INTO sales_bookings(sale_booking_id, registration_id, plot_id, sale_status) VALUES (?, ?, ?, 'ACTIVE')",
            995, 995, 995);

        // Create a historical payment for the OTHER customer
        jdbc.update("""
            INSERT INTO payments_receipts(id, customer_id, booking_id, receipt_no, payment_date, amount,
             payment_method, status, posted_at)
            VALUES (?, ?, NULL, ?, CURDATE(), ?, 'CASH', 'VERIFIED', NULL)
            """, 894, 894, "OTHER-001", new BigDecimal("99999"));

        // Create receivables for the first customer's property
        jdbc.update("INSERT INTO webv9_receivables(id, member_id, registration_id, booking_id, plot_id, original_amount, paid_amount, surcharge_amount, adjustment_amount, status, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            1, 995, 995, 995, 995, new BigDecimal("100000"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), "DUE", "Test charge");

        var response = controller.getPropertyLedger(995L,
            UsernamePasswordAuthenticationToken.authenticated("customer:995", "", List.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        // Should not include the other customer's payment
        assertThat(body).containsEntry("totalDue", new BigDecimal("100000.00"))
            .containsEntry("totalPaid", new BigDecimal("0.00"))
            .containsEntry("outstanding", new BigDecimal("100000.00"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ledger = (List<Map<String, Object>>) body.get("ledger");
        assertThat(ledger).isEmpty();
    }

    /**
     * Test that historical payments with a specific booking_id are correctly attributed
     * to that booking and included in the ledger.
     */
    @Test
    void ledgerIncludesHistoricalPaymentWithSpecificBookingId() {
        // Create customer account and member
        jdbc.update("INSERT INTO members(member_id, mbr_no, name, cnic_no) VALUES (?, ?, ?, ?)",
            993, "MBR-993", "Test Customer 7", "54400-1234567-7");
        jdbc.update("INSERT INTO webv9_customer_accounts(account_id, member_id, is_active, registration_status) VALUES (?, ?, TRUE, 'ACTIVE')",
            993, 993);

        // Create one registration with TWO bookings
        jdbc.update("INSERT INTO registrations(registration_id, member_id, reg_no, form_no, category) VALUES (?, ?, ?, ?, ?)",
            993, 993, "TEST-993", "TEST-FORM-993", "RESIDENTIAL");

        // Create two plots
        jdbc.update("INSERT INTO plots(plot_id, block_id, plot_no, plot_size_marla, plot_type, category) VALUES (?, 1, 'P-993A', 5.0, 'RESIDENTIAL', 'RESIDENTIAL')",
            993);
        jdbc.update("INSERT INTO plots(plot_id, block_id, plot_no, plot_size_marla, plot_type, category) VALUES (?, 1, 'P-993B', 5.0, 'RESIDENTIAL', 'RESIDENTIAL')",
            892);

        // Create two bookings for the same registration
        jdbc.update("INSERT INTO sales_bookings(sale_booking_id, registration_id, plot_id, sale_status) VALUES (?, ?, ?, 'ACTIVE')",
            993, 993, 993);
        jdbc.update("INSERT INTO sales_bookings(sale_booking_id, registration_id, plot_id, sale_status) VALUES (?, ?, ?, 'ACTIVE')",
            892, 993, 892);

        // Create a historical payment WITH specific booking_id (for booking 892)
        jdbc.update("""
            INSERT INTO payments_receipts(id, customer_id, booking_id, receipt_no, payment_date, amount,
             payment_method, status, posted_at)
            VALUES (?, ?, ?, ?, CURDATE(), ?, 'CASH', 'VERIFIED', NULL)
            """, 893, 993, 892, "BOOKING-SPECIFIC-001", new BigDecimal("25000"));

        // Create receivables for both bookings
        jdbc.update("INSERT INTO webv9_receivables(id, member_id, registration_id, booking_id, plot_id, original_amount, paid_amount, surcharge_amount, adjustment_amount, status, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            1, 993, 993, 993, 993, new BigDecimal("50000"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), "DUE", "Test charge booking 1");
        jdbc.update("INSERT INTO webv9_receivables(id, member_id, registration_id, booking_id, plot_id, original_amount, paid_amount, surcharge_amount, adjustment_amount, status, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            2, 993, 993, 892, 892, new BigDecimal("50000"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), "DUE", "Test charge booking 2");

        // Query ledger for registration 993 - will return booking 892 (second booking due to ORDER BY)
        var response = controller.getPropertyLedger(993L,
            UsernamePasswordAuthenticationToken.authenticated("customer:993", "", List.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        // The booking-specific payment should be included for booking 892
        assertThat(body).containsEntry("totalDue", new BigDecimal("50000.00"))
            .containsEntry("totalPaid", new BigDecimal("25000.00"))
            .containsEntry("outstanding", new BigDecimal("25000.00"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ledger = (List<Map<String, Object>>) body.get("ledger");
        assertThat(ledger).hasSize(1);
        assertThat(valueIgnoreCase(ledger.get(0), "transactionType")).isEqualTo("PAYMENT");
        assertThat(valueIgnoreCase(ledger.get(0), "credit")).isEqualTo(new BigDecimal("25000.00"));
    }

    /**
     * Test that historical payments from another property of the same customer are not included.
     */
    @Test
    void ledgerExcludesHistoricalPaymentsFromAnotherProperty() {
        // Create customer account and member
        jdbc.update("INSERT INTO members(member_id, mbr_no, name, cnic_no) VALUES (?, ?, ?, ?)",
            994, "MBR-994", "Test Customer 6", "54400-1234567-6");
        jdbc.update("INSERT INTO webv9_customer_accounts(account_id, member_id, is_active, registration_status) VALUES (?, ?, TRUE, 'ACTIVE')",
            994, 994);

        // Create two registrations for the same customer
        jdbc.update("INSERT INTO registrations(registration_id, member_id, reg_no, form_no, category) VALUES (?, ?, ?, ?, ?)",
            994, 994, "TEST-994", "TEST-FORM-994", "RESIDENTIAL");
        jdbc.update("INSERT INTO registrations(registration_id, member_id, reg_no, form_no, category) VALUES (?, ?, ?, ?, ?)",
            893, 994, "TEST-893", "TEST-FORM-893", "RESIDENTIAL");

        // Create bookings for both
        jdbc.update("INSERT INTO plots(plot_id, block_id, plot_no, plot_size_marla, plot_type, category) VALUES (?, 1, 'P-994', 5.0, 'RESIDENTIAL', 'RESIDENTIAL')",
            994);
        jdbc.update("INSERT INTO sales_bookings(sale_booking_id, registration_id, plot_id, sale_status) VALUES (?, ?, ?, 'ACTIVE')",
            994, 994, 994);

        jdbc.update("INSERT INTO plots(plot_id, block_id, plot_no, plot_size_marla, plot_type, category) VALUES (?, 1, 'P-893', 5.0, 'RESIDENTIAL', 'RESIDENTIAL')",
            893);
        jdbc.update("INSERT INTO sales_bookings(sale_booking_id, registration_id, plot_id, sale_status) VALUES (?, ?, ?, 'ACTIVE')",
            893, 893, 893);

        // Create a historical payment for the OTHER property (with booking_id)
        jdbc.update("""
            INSERT INTO payments_receipts(id, customer_id, booking_id, receipt_no, payment_date, amount,
             payment_method, status, posted_at)
            VALUES (?, ?, ?, ?, CURDATE(), ?, 'CASH', 'VERIFIED', NULL)
            """, 893, 994, 893, "OTHER-PROP-001", new BigDecimal("40000"));

        // Create receivables for the first property
        jdbc.update("INSERT INTO webv9_receivables(id, member_id, registration_id, booking_id, plot_id, original_amount, paid_amount, surcharge_amount, adjustment_amount, status, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            1, 994, 994, 994, 994, new BigDecimal("100000"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), "DUE", "Test charge");

        var response = controller.getPropertyLedger(994L,
            UsernamePasswordAuthenticationToken.authenticated("customer:994", "", List.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        // Should not include the other property's payment
        assertThat(body).containsEntry("totalDue", new BigDecimal("100000.00"))
            .containsEntry("totalPaid", new BigDecimal("0.00"))
            .containsEntry("outstanding", new BigDecimal("100000.00"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ledger = (List<Map<String, Object>>) body.get("ledger");
        assertThat(ledger).isEmpty();
    }
}
