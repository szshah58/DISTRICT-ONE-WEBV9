package com.districtone.webv9.controller;

import com.districtone.webv9.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/customer")
public class CustomerAuthController {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;

    public CustomerAuthController(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            JwtService jwt) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
    }

    public record RegisterRequest(
            @NotBlank String cnic,
            String regNo,
            @NotBlank String password,
            @NotBlank String confirmPassword) {}

    public record LoginRequest(
            @NotBlank String cnic,
            @NotBlank String password) {}

    public record ChangePasswordRequest(
            @NotBlank String cnic,
            @NotBlank String currentPassword,
            @NotBlank String newPassword,
            @NotBlank String confirmPassword) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {

        String cnic = normalizeCnic(req.cnic());

        if (!validPassword(req.password())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message",
                    "Password must be at least 10 characters and include uppercase and lowercase letters, a number, and a special character, with no spaces."
            ));
        }

        if (!req.password().equals(req.confirmPassword())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Password and confirm password do not match."
            ));
        }

        var accounts = jdbc.queryForList("""
            SELECT a.account_id, a.member_id, a.password_hash,
                   a.registration_status, a.is_active,
                   m.name, m.cnic_no
            FROM webv9_customer_accounts a
            JOIN members m ON m.member_id = a.member_id
            WHERE (REPLACE(REPLACE(a.cnic_no, '-', ''), ' ', '') = ?
                OR REPLACE(REPLACE(m.cnic_no, '-', ''), ' ', '') = ?)
              AND (? IS NULL OR EXISTS (
                    SELECT 1
                    FROM registrations r
                    WHERE r.member_id = a.member_id
                      AND r.reg_no = ?
              ))
            LIMIT 1
            """, cnic, cnic, req.regNo(), req.regNo());

        if (accounts.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "message",
                    "CNIC was not found in District One customer records."
            ));
        }

        Map<String,Object> account = accounts.get(0);

        String status = String.valueOf(account.get("registration_status"));

        if ("ACTIVE".equalsIgnoreCase(status)
                && account.get("password_hash") != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message",
                    "This CNIC is already registered. Please use Customer Login."
            ));
        }

        String hash = passwordEncoder.encode(req.password());

        jdbc.update("""
            UPDATE webv9_customer_accounts
            SET password_hash = ?,
                registration_status = 'ACTIVE',
                is_active = 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE account_id = ?
            """, hash, account.get("account_id"));

        return ResponseEntity.ok(Map.of(
                "message", "Customer registration completed successfully.",
                "cnic", cnic,
                "memberId", account.get("member_id"),
                "fullName", account.get("name")
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {

        String identifier = req.cnic().trim();
        String cnic = normalizeCnic(identifier);

        var accounts = jdbc.queryForList("""
            SELECT a.account_id, a.member_id, a.cnic_no,
                   a.password_hash, a.registration_status,
                   a.is_active, m.name
            FROM webv9_customer_accounts a
            JOIN members m ON m.member_id = a.member_id
            WHERE REPLACE(REPLACE(a.cnic_no, '-', ''), ' ', '') = ?
               OR REPLACE(REPLACE(m.cnic_no, '-', ''), ' ', '') = ?
            LIMIT 1
            """, cnic, cnic);

        if (accounts.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "message", "Invalid CNIC or password."
            ));
        }

        Map<String,Object> account = accounts.get(0);

        if (!Boolean.TRUE.equals(account.get("is_active"))
                || !"ACTIVE".equalsIgnoreCase(
                    String.valueOf(account.get("registration_status")))
                || account.get("password_hash") == null
                || !passwordEncoder.matches(
                    req.password(),
                    String.valueOf(account.get("password_hash")))) {

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "message", "Invalid CNIC or password."
            ));
        }

        Long accountId = ((Number) account.get("account_id")).longValue();
        Long memberId = ((Number) account.get("member_id")).longValue();

        jdbc.update("""
            UPDATE webv9_customer_accounts
            SET last_login_at = CURRENT_TIMESTAMP
            WHERE account_id = ?
            """, accountId);

        String subject = "customer:" + accountId;
        String token = jwt.create(subject, "CUSTOMER", Duration.ofHours(8));

        Map<String,Object> user = new LinkedHashMap<>();
        user.put("cnic", account.get("cnic_no"));
        user.put("loginIdentifier", identifier);
        user.put("fullName", account.get("name"));
        user.put("memberId", memberId);
        user.put("accountId", accountId);
        user.put("role", "CUSTOMER");

        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", user
        ));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        String cnic = normalizeCnic(req.cnic());
        if (cnic.length() != 13) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Please enter a valid 13-digit CNIC number."
            ));
        }
        if (!validPassword(req.newPassword())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Password must be at least 10 characters and include uppercase and lowercase letters, a number, and a special character, with no spaces."
            ));
        }
        if (!req.newPassword().equals(req.confirmPassword())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "New password and confirmation do not match."
            ));
        }

        var accounts = jdbc.queryForList("""
            SELECT a.account_id, a.password_hash, a.registration_status, a.is_active
            FROM webv9_customer_accounts a
            JOIN members m ON m.member_id = a.member_id
            WHERE REPLACE(REPLACE(a.cnic_no, '-', ''), ' ', '') = ?
               OR REPLACE(REPLACE(m.cnic_no, '-', ''), ' ', '') = ?
            LIMIT 1
            """, cnic, cnic);

        if (accounts.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "message", "CNIC or current password is incorrect."
            ));
        }
        Map<String, Object> account = accounts.get(0);
        Object storedHash = account.get("password_hash");
        if (!Boolean.TRUE.equals(account.get("is_active"))
                || !"ACTIVE".equalsIgnoreCase(String.valueOf(account.get("registration_status")))
                || storedHash == null
                || !passwordEncoder.matches(req.currentPassword(), String.valueOf(storedHash))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "message", "CNIC or current password is incorrect."
            ));
        }

        jdbc.update("""
            UPDATE webv9_customer_accounts
            SET password_hash = ?, updated_at = CURRENT_TIMESTAMP
            WHERE account_id = ?
            """, passwordEncoder.encode(req.newPassword()), account.get("account_id"));

        return ResponseEntity.ok(Map.of("message", "Password changed successfully."));
    }

    private String normalizeCnic(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    private boolean validPassword(String password) {
        return password != null
                && password.length() >= 10
                && password.matches("^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^A-Za-z0-9\\s])\\S+$");
    }
}
