package com.districtone.webv9.controller;
import com.districtone.webv9.model.User;
import com.districtone.webv9.repo.UserRepository;
import com.districtone.webv9.security.JwtService;
import com.districtone.webv9.security.PasswordVerifier;
import com.districtone.webv9.security.UserPermissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private static final String AUTH_COOKIE = "WEBV9_AUTH";
  private static final Duration PERSISTENT_SESSION_LIFETIME = Duration.ofDays(30);
  private final UserRepository users;
  private final PasswordVerifier passwords;
  private final JwtService jwt;
  private final JdbcTemplate jdbc;
  private final UserPermissionService permissions;

  public AuthController(UserRepository users, PasswordVerifier passwords, JwtService jwt, JdbcTemplate jdbc,
                        UserPermissionService permissions) {
    this.users = users;
    this.passwords = passwords;
    this.jwt = jwt;
    this.jdbc = jdbc;
    this.permissions = permissions;
  }

  // role is accepted for compatibility with older clients but is never used to grant access.
  public record Login(@NotBlank String username, @NotBlank String password, String role, boolean rememberMe) {}

  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody Login in, HttpServletRequest request) {
    Optional<User> found = users.findByUsername(in.username());
    if (found.isEmpty() || !"ACTIVE".equals(found.get().getStatus())
        || !passwords.matches(in.password(), found.get().getPasswordHash())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("message", "Invalid username or password"));
    }

    User user = found.get();
    if (!permissions.isAccountActive(user.getUsername()) || !permissions.hasAnyProjectScope(user.getUsername())) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(Map.of("message", "The account has no current job-title and project/location authorization."));
    }
    String role = user.getRole() == null || user.getRole().isBlank()
        ? "STAFF"
        : user.getRole().trim().toUpperCase(Locale.ROOT);

    jdbc.update("UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?", user.getId());
    jdbc.update("INSERT INTO system_logs(username,action,module,details) VALUES (?, 'LOGIN', 'AUTH', ?)",
        user.getUsername(), "WEBV9 API login");

    Duration lifetime = in.rememberMe() ? PERSISTENT_SESSION_LIFETIME : Duration.ofHours(8);
    String token = jwt.create(user.getUsername(), role, lifetime);
    Map<String, Object> response = Map.of("user", userInfo(user.getUsername(), user.getFullName(), role));

    if (in.rememberMe()) {
      return ResponseEntity.ok()
          .header(HttpHeaders.SET_COOKIE, authCookie(token, PERSISTENT_SESSION_LIFETIME, request).toString())
          .body(response);
    }
    return ResponseEntity.ok(Map.of("token", token, "user", response.get("user")));
  }

  @GetMapping("/me")
  public ResponseEntity<?> currentUser(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()
        || "anonymousUser".equals(authentication.getPrincipal())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not authenticated"));
    }
    Optional<User> found = users.findByUsername(authentication.getName());
    if (found.isEmpty() || !found.get().isActive()
        || !permissions.isAccountActive(authentication.getName())
        || !permissions.hasAnyProjectScope(authentication.getName())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("message", "Account is no longer authorized"));
    }
    User user = found.get();
    return ResponseEntity.ok(Map.of("user", userInfo(user.getUsername(), user.getFullName(),
        user.getRole() == null ? "STAFF" : user.getRole().trim().toUpperCase(Locale.ROOT))));
  }

  private Map<String, Object> userInfo(String username, String fullName, String role) {
    return Map.of(
        "username", username,
        "fullName", fullName,
        "role", role,
        "superAdmin", permissions.isSuperAdmin(username),
        "modules", permissions.effectiveModules(username),
        "permissionActions", permissions.effectivePermissionActions(username));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, authCookie("", Duration.ZERO, request).toString())
        .build();
  }

  private ResponseCookie authCookie(String value, Duration lifetime, HttpServletRequest request) {
    String host = request.getServerName();
    boolean secure = request.isSecure() || "localhost".equalsIgnoreCase(host)
        || "127.0.0.1".equals(host) || "::1".equals(host);
    return ResponseCookie.from(AUTH_COOKIE, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Lax")
        .path("/api/v1")
        .maxAge(lifetime)
        .build();
  }
}
