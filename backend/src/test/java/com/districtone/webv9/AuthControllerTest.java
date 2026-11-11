package com.districtone.webv9;

import com.districtone.webv9.controller.AuthController;
import com.districtone.webv9.model.User;
import com.districtone.webv9.repo.UserRepository;
import com.districtone.webv9.security.JwtService;
import com.districtone.webv9.security.PasswordVerifier;
import com.districtone.webv9.security.UserPermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {
  private final PasswordEncoder encoder = new BCryptPasswordEncoder();
  private final UserRepository users = mock(UserRepository.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final JwtService jwt = new JwtService("isolated-test-signing-secret-which-is-at-least-32-bytes-long");
  private final UserPermissionService permissions = mock(UserPermissionService.class);
  private final AuthController controller = new AuthController(users, new PasswordVerifier(encoder), jwt, jdbc, permissions);

  @Test
  void passwordsAreStoredAsOneWayBcryptHashes() {
    String rawPassword = generatedTestPassword();
    String hash = encoder.encode(rawPassword);
    assertThat(hash).startsWith("$2").isNotEqualTo(rawPassword);
    assertThat(encoder.matches(rawPassword, hash)).isTrue();
    assertThat(encoder.matches(generatedTestPassword(), hash)).isFalse();
  }

  @Test
  void activeAdminCanLoginAndReceivesJwt() {
    String rawPassword = generatedTestPassword();
    when(users.findByUsername("admin.test")).thenReturn(Optional.of(user("admin.test", rawPassword, true)));
    when(permissions.isAccountActive("admin.test")).thenReturn(true);
    when(permissions.hasAnyProjectScope("admin.test")).thenReturn(true);
    when(permissions.isSuperAdmin("admin.test")).thenReturn(false);
    when(permissions.effectiveModules("admin.test")).thenReturn(java.util.List.of("Finance"));

    ResponseEntity<?> response = controller.login(
        new AuthController.Login("admin.test", rawPassword, null, false), new MockHttpServletRequest());

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsKey("token");
    assertThat(body).doesNotContainKey("password");
  }

  @Test
  void activeSuperAdminCanLoginWithoutOrdinaryProjectAssignments() {
    String rawPassword = generatedTestPassword();
    User superAdmin = user("owner.test", rawPassword, true);
    superAdmin.setRole("SUPER_ADMIN");
    when(users.findByUsername("owner.test")).thenReturn(Optional.of(superAdmin));
    when(permissions.isAccountActive("owner.test")).thenReturn(true);
    when(permissions.hasAnyProjectScope("owner.test")).thenReturn(true);
    when(permissions.isSuperAdmin("owner.test")).thenReturn(true);
    when(permissions.effectiveModules("owner.test"))
        .thenReturn(java.util.List.of("Dashboard", "Admin & Settings", "Finance", "Reports"));
    when(permissions.effectivePermissionActions("owner.test")).thenReturn(java.util.List.of());

    ResponseEntity<?> response = controller.login(
        new AuthController.Login("owner.test", rawPassword, null, false), new MockHttpServletRequest());

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    @SuppressWarnings("unchecked")
    Map<String, Object> userInfo = (Map<String, Object>) body.get("user");
    assertThat(userInfo).containsEntry("superAdmin", true)
        .containsEntry("modules", java.util.List.of("Dashboard", "Admin & Settings", "Finance", "Reports"));
  }

  @Test
  void ordinaryAccountWithoutCurrentAuthorizationIsDeniedAtLogin() {
    String rawPassword = generatedTestPassword();
    when(users.findByUsername("unassigned.user"))
        .thenReturn(Optional.of(user("unassigned.user", rawPassword, true)));
    when(permissions.isAccountActive("unassigned.user")).thenReturn(true);
    when(permissions.hasAnyProjectScope("unassigned.user")).thenReturn(false);

    ResponseEntity<?> response = controller.login(
        new AuthController.Login("unassigned.user", rawPassword, null, false), new MockHttpServletRequest());

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(permissions, never()).effectiveModules("unassigned.user");
  }

  @Test
  void incorrectPasswordAndInactiveAccountAreRejected() {
    String rawPassword = generatedTestPassword();
    when(users.findByUsername("admin.test"))
        .thenReturn(Optional.of(user("admin.test", rawPassword, true)))
        .thenReturn(Optional.of(user("admin.test", rawPassword, false)));

    ResponseEntity<?> wrongPassword = controller.login(
        new AuthController.Login("admin.test", generatedTestPassword(), null, false), new MockHttpServletRequest());
    ResponseEntity<?> inactive = controller.login(
        new AuthController.Login("admin.test", rawPassword, null, false), new MockHttpServletRequest());

    assertThat(wrongPassword.getStatusCode().value()).isEqualTo(401);
    assertThat(inactive.getStatusCode().value()).isEqualTo(401);
  }

  private User user(String username, String rawPassword, boolean active) {
    User user = new User();
    user.setId(1L);
    user.setUsername(username);
    user.setFullName("Isolated test administrator");
    user.setPasswordHash(encoder.encode(rawPassword));
    user.setRole("ADMIN");
    user.setActive(active);
    return user;
  }

  private static String generatedTestPassword() {
    return UUID.randomUUID() + "Aa9!";
  }
}