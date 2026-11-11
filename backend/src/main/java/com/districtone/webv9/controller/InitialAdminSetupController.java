package com.districtone.webv9.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.districtone.webv9.security.AdminPasswordPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/initial-admin")
public class InitialAdminSetupController {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder encoder;
  private final String setupToken;

  public InitialAdminSetupController(JdbcTemplate jdbc, PasswordEncoder encoder,
                                     @Value("${app.initial-admin-setup-token:}") String setupToken) {
    this.jdbc = jdbc;
    this.encoder = encoder;
    this.setupToken = setupToken;
  }

  public record SetupRequest(@NotBlank @Size(min = 8, max = 72) String password,
                             @NotBlank @Size(min = 8, max = 72) String confirmPassword,
                             boolean resetExisting, String username) {}

  @PostMapping
  @Transactional
  public Map<String, Object> setup(@RequestHeader("X-WEBV9-SETUP-TOKEN") String suppliedToken,
                                   @Valid @RequestBody SetupRequest request,
                                   HttpServletRequest servletRequest) {
    if (setupToken.isBlank() || !setupToken.equals(suppliedToken) || !isLocal(servletRequest)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
    if (!request.password().equals(request.confirmPassword())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password and confirmation do not match.");
    }
    AdminPasswordPolicy.validate(request.password());
    String username = request.username() == null || request.username().isBlank()
        ? "webv9admin" : request.username().trim().toLowerCase(java.util.Locale.ROOT);
    if (!username.equals("admin") && !username.equals("webv9admin")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only the configured Admin accounts can be reset");
    }
    Integer existing = jdbc.queryForObject(
        "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
    if (username.equals("admin") && (existing == null || existing == 0)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Admin account not found");
    }
    if (existing != null && existing > 0 && !request.resetExisting()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "WEBV9 administrator is already configured");
    }
    String passwordHash = encoder.encode(request.password());
    if (existing != null && existing > 0) {
      jdbc.update("""
          UPDATE users
          SET password_hash = ?, role = 'SUPER_ADMIN', is_active = 1,
              is_super_admin = 1, updated_at = CURRENT_TIMESTAMP
          WHERE username = ?
          """, passwordHash, username);
      return Map.of("configured", true, "reset", true, "username", username,
          "message", "WEBV9 administrator password reset");
    }
    if (!username.equals("webv9admin")) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Admin account not found");
    }
    jdbc.update("""
        INSERT INTO users
          (username, display_name, password_hash, role, is_active, is_super_admin, created_at, updated_at)
        VALUES ('webv9admin', 'WEBV9 Administrator', ?, 'SUPER_ADMIN', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, passwordHash);
    return Map.of("configured", true, "username", username, "message", "Initial administrator configured");
  }

  private boolean isLocal(HttpServletRequest request) {
    String address = request.getRemoteAddr();
    try {
      return java.net.InetAddress.getByName(address).isLoopbackAddress();
    } catch (java.net.UnknownHostException exception) {
      return false;
    }
  }
}
