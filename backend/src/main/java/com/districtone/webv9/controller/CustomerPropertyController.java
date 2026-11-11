package com.districtone.webv9.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/customer")
public class CustomerPropertyController {

    private final JdbcTemplate jdbc;

    public CustomerPropertyController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/properties")
    public List<Map<String, Object>> properties(Authentication authentication) {

        Long accountId = customerAccountId(authentication);

        return jdbc.queryForList("""
            SELECT
                r.registration_id AS registrationId,
                r.reg_no AS regNo,
                r.form_no AS formNo,
                r.category AS category,
                r.status AS status,
                m.member_id AS memberId,
                m.mbr_no AS mbrNo,
                m.name AS customerName,
                sb.sale_booking_id AS bookingId,
                p.plot_id AS plotId,
                p.plot_no AS plotNo,
                p.plot_size_marla AS plotSizeMarla,
                p.plot_type AS plotType,
                b.block_code AS blockCode,
                b.block_name AS blockName
            FROM webv9_customer_accounts a
            JOIN members m ON m.member_id = a.member_id
            JOIN registrations r ON r.member_id = m.member_id
            LEFT JOIN sales_bookings sb ON sb.registration_id = r.registration_id
                AND sb.sale_status <> 'CANCELLED'
            LEFT JOIN plots p ON p.plot_id = sb.plot_id
            LEFT JOIN blocks b ON b.block_id = p.block_id
            WHERE a.account_id = ?
              AND a.is_active = 1
              AND a.registration_status = 'ACTIVE'
            ORDER BY r.registration_id, sb.sale_booking_id
            """, accountId);
    }

    private Long customerAccountId(Authentication authentication) {

        if (authentication == null
                || authentication.getName() == null
                || !authentication.getName().startsWith("customer:")) {
            throw new IllegalStateException("Customer authentication required.");
        }

        return Long.valueOf(
                authentication.getName().substring("customer:".length())
        );
    }
}
