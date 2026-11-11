package com.districtone.webv9;

import com.districtone.webv9.controller.AuthController;
import com.districtone.webv9.controller.OperationsController;
import com.districtone.webv9.model.User;
import com.districtone.webv9.repo.UserRepository;
import com.districtone.webv9.security.JwtAuthenticationFilter;
import com.districtone.webv9.security.JwtService;
import com.districtone.webv9.security.PasswordVerifier;
import com.districtone.webv9.security.SecurityConfig;
import com.districtone.webv9.security.UserPermissionService;
import com.districtone.webv9.security.ProjectAccessService;
import com.districtone.webv9.service.PlotInventoryService;
import com.districtone.webv9.service.PaymentReversalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({OperationsController.class, AuthController.class})
@AutoConfigureMockMvc(addFilters = true)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, PasswordVerifier.class})
@TestPropertySource(properties = {
    "app.jwt-secret=unit-test-only-jwt-signing-key-32-bytes-minimum",
    "app.cors-origins=http://localhost"
})
class AdminUserManagementFlowTest {
  private static final String RAW_PASSWORD = java.util.UUID.randomUUID() + "Aa9!";
  private static final String OTHER_PASSWORD = java.util.UUID.randomUUID() + "Bb8!";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtService jwt;
  @Autowired private PasswordEncoder encoder;

  @MockBean private JdbcTemplate jdbc;
  @MockBean private UserPermissionService permissions;
  @MockBean private ProjectAccessService projectAccess;
  @MockBean private UserRepository users;
  @MockBean private PlotInventoryService plotInventory;
  @MockBean private PaymentReversalService paymentReversals;

  @BeforeEach
  void resetMocks() {
    reset(jdbc, permissions, projectAccess, users, plotInventory, paymentReversals);
    when(permissions.isAccountActive(anyString())).thenReturn(true);
    when(permissions.hasAnyProjectScope(anyString())).thenReturn(true);
  }

  @Test
  void superAdminCanCreateAdminWithBcryptHashAndConfiguredPermissions() throws Exception {
    allowUserCreate("root", "SUPER_ADMIN");
    when(jdbc.queryForObject(contains("COUNT(*) FROM users WHERE username = ?"), eq(Integer.class), eq("admin.one")))
        .thenReturn(0);
    when(jdbc.queryForObject(contains("SELECT id FROM roles"), eq(Long.class), eq("ADMIN"))).thenReturn(2L);
    when(jdbc.queryForList(eq("SELECT permission_code FROM permissions"), eq(String.class)))
        .thenReturn(List.of("USERS_VIEW", "USERS_ADD"));
    when(jdbc.queryForObject(contains("SELECT id FROM users WHERE username = ?"), eq(Long.class), eq("root")))
        .thenReturn(7L);
    when(jdbc.queryForObject(contains("SELECT id FROM users WHERE username = ?"), eq(Long.class), eq("admin.one")))
        .thenReturn(8L);
    when(jdbc.queryForList(contains("SELECT p.id, p.permission_code"), any(Object[].class)))
        .thenReturn(List.of(
            Map.of("id", 10L, "permission_code", "USERS_VIEW", "position_granted", 1),
            Map.of("id", 11L, "permission_code", "USERS_ADD", "position_granted", 0)));

    mockMvc.perform(post("/api/v1/admin/users")
            .header(AUTHORIZATION, bearer("root", "SUPER_ADMIN"))
            .contentType("application/json")
            .content(adminJson("admin.one", "ADMIN", RAW_PASSWORD, RAW_PASSWORD,
                List.of("USERS_VIEW", "USERS_ADD"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(true))
        .andExpect(jsonPath("$.role").value("ADMIN"))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.password").doesNotExist());

    org.mockito.ArgumentCaptor<Object[]> inserted = org.mockito.ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(contains("INSERT INTO users"), inserted.capture());
    Object[] values = inserted.getValue();
    assertThat(values[1].toString()).startsWith("$2").isNotEqualTo(RAW_PASSWORD);
    assertThat(encoder.matches(RAW_PASSWORD, values[1].toString())).isTrue();
    assertThat(values[6]).isEqualTo("ADMIN");
    assertThat(values[8]).isEqualTo(0);
    verify(jdbc).update(contains("INSERT INTO user_permission_overrides"), any(Object[].class));
  }

  @Test
  void mismatchedPasswordConfirmationIsRejectedBeforeDatabaseWrites() throws Exception {
    allowUserCreate("root", "SUPER_ADMIN");
    mockMvc.perform(post("/api/v1/admin/users")
            .header(AUTHORIZATION, bearer("root", "SUPER_ADMIN"))
            .contentType("application/json")
            .content(adminJson("admin.two", "ADMIN", RAW_PASSWORD, OTHER_PASSWORD, List.of())))
        .andExpect(status().isBadRequest());
    verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
  }

  @Test
  void weakPasswordIsRejectedBeforeDatabaseWrites() throws Exception {
    allowUserCreate("root", "SUPER_ADMIN");
    String weak = "Welcome!Secure2026";
    mockMvc.perform(post("/api/v1/admin/users")
            .header(AUTHORIZATION, bearer("root", "SUPER_ADMIN"))
            .contentType("application/json")
            .content(adminJson("admin.weak", "ADMIN", weak, weak, List.of())))
        .andExpect(status().isBadRequest());
    verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
  }

  @Test
  void duplicateUsernameIsRejected() throws Exception {
    allowUserCreate("root", "SUPER_ADMIN");
    when(jdbc.queryForObject(contains("COUNT(*) FROM users WHERE username = ?"), eq(Integer.class), eq("admin.one")))
        .thenReturn(1);

    mockMvc.perform(post("/api/v1/admin/users")
            .header(AUTHORIZATION, bearer("root", "SUPER_ADMIN"))
            .contentType("application/json")
            .content(adminJson("admin.one", "ADMIN", RAW_PASSWORD, RAW_PASSWORD, List.of())))
        .andExpect(status().isConflict());
  }

  @Test
  void ordinaryAdminCannotGrantSuperAdminRole() throws Exception {
    allowUserCreate("admin.actor", "ADMIN");
    when(jdbc.queryForObject(contains("COUNT(*) FROM users WHERE username = ?"), eq(Integer.class), eq("super.new")))
        .thenReturn(0);

    mockMvc.perform(post("/api/v1/admin/users")
            .header(AUTHORIZATION, bearer("admin.actor", "ADMIN"))
            .contentType("application/json")
            .content(adminJson("super.new", "SUPER_ADMIN", RAW_PASSWORD, RAW_PASSWORD, List.of())))
        .andExpect(status().isForbidden());
  }

  @Test
  void ordinaryAdminCannotCreateAnAdminAccount() throws Exception {
    allowUserCreate("admin.actor", "ADMIN");
    mockMvc.perform(post("/api/v1/admin/users")
            .header(AUTHORIZATION, bearer("admin.actor", "ADMIN"))
            .contentType("application/json")
            .content(adminJson("admin.new", "ADMIN", RAW_PASSWORD, RAW_PASSWORD, List.of())))
        .andExpect(status().isForbidden());
    verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
  }

  @Test
  void adminCanLoginWithHashedPassword() throws Exception {
    User user = user("admin.login", RAW_PASSWORD, true);
    when(users.findByUsername("admin.login")).thenReturn(Optional.of(user));

    mockMvc.perform(post("/api/v1/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"admin.login\",\"password\":\"" + RAW_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andExpect(jsonPath("$.user.role").value("ADMIN"))
        .andExpect(jsonPath("$.password").doesNotExist());
  }

  @Test
  void incorrectPasswordIsRejected() throws Exception {
    when(users.findByUsername("admin.login")).thenReturn(Optional.of(user("admin.login", RAW_PASSWORD, true)));
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"admin.login\",\"password\":\"" + OTHER_PASSWORD + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void inactiveAdminCannotLogin() throws Exception {
    when(users.findByUsername("admin.login")).thenReturn(Optional.of(user("admin.login", RAW_PASSWORD, false)));
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"admin.login\",\"password\":\"" + RAW_PASSWORD + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  private void allowUserCreate(String username, String role) {
    when(permissions.isGranted(eq(username), eq("Users & Roles"), eq("ADD"))).thenReturn(true);
    when(projectAccess.isSuperAdmin(username)).thenReturn("SUPER_ADMIN".equals(role));
    when(jdbc.queryForMap(contains("SELECT u.role"), any(Object[].class))).thenAnswer(call -> {
      Map<String, Object> account = new java.util.HashMap<>();
      account.put("role", role);
      return account;
    });
  }

  private String bearer(String username, String role) {
    return "Bearer " + jwt.create(username, role);
  }

  private String adminJson(String username, String role, String password, String confirmPassword,
                           List<String> permissions) throws Exception {
    return objectMapper.writeValueAsString(Map.of(
        "username", username,
        "displayName", "Test Administrator",
        "email", "admin@example.test",
        "department", "Administration",
        "role", role,
        "password", password,
        "confirmPassword", confirmPassword,
        "active", true,
        "permissions", permissions));
  }

  private User user(String username, String password, boolean active) {
    User user = new User();
    user.setId(99L);
    user.setUsername(username);
    user.setFullName("Test Administrator");
    user.setPasswordHash(encoder.encode(password));
    user.setRole("ADMIN");
    user.setActive(active);
    return user;
  }
}
