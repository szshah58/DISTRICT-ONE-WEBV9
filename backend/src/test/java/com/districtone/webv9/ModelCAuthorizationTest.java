package com.districtone.webv9;

import com.districtone.webv9.security.PermissionAuthorizationFilter;
import com.districtone.webv9.security.ProjectAccessService;
import com.districtone.webv9.security.UserPermissionService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelCAuthorizationTest {
  @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

  @Test
  void financePositionPermissionQueryUsesPositionRightsOverridesAndExpiry() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(contains("LEFT JOIN webv9_positions"), eq(Integer.class), eq("finance.verifier")))
        .thenReturn(1);
    when(jdbc.queryForObject(contains("is_super_admin = 1 OR UPPER(role)"), eq(Integer.class), eq("finance.verifier")))
        .thenReturn(0);
    when(jdbc.queryForObject(contains("JOIN webv9_user_project_scopes"), eq(Integer.class), eq("finance.verifier")))
        .thenReturn(1);
    when(jdbc.queryForList(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(java.util.List.of(1));
    UserPermissionService service = new UserPermissionService(jdbc);

    assertThat(service.isGranted("finance.verifier", "Finance", "RECEIPT_VERIFY")).isTrue();

    var permissionSql = org.mockito.ArgumentCaptor.forClass(String.class);
    var permissionArgs = org.mockito.ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForList(permissionSql.capture(), eq(Integer.class), permissionArgs.capture());
    assertThat(permissionSql.getValue()).contains("webv9_position_permissions", "user_permission_overrides",
        "permission.action = ? OR permission.permission_code = ?");
    assertThat(permissionArgs.getValue()).contains("Finance", "RECEIPT_VERIFY", "finance.verifier");
    verify(jdbc, atLeastOnce()).queryForObject(
        contains("access_starts_at"), eq(Integer.class), eq("finance.verifier"));
  }

  @Test
  void scopedUserCannotAccessAnUnassignedProject() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    UserPermissionService permissions = mock(UserPermissionService.class);
    when(jdbc.queryForMap(contains("FROM webv9_projects"), eq(51L)))
        .thenReturn(java.util.Map.of("location", "District One", "city", "Lahore"));
    when(permissions.hasProjectScope("operator", 51L, "District One", "Lahore")).thenReturn(false);
    ProjectAccessService service = new ProjectAccessService(jdbc, permissions);

    assertThat(service.canAccessProject("operator", 51L)).isFalse();
    assertThatThrownBy(() -> service.requireProject("operator", 51L))
        .hasMessageContaining("not assigned to this project/location");
  }

  @Test
  void expiredAccountHasNoEffectivePermission() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
    UserPermissionService service = new UserPermissionService(jdbc);

    assertThat(service.isGranted("expired.user", "Finance", "RECEIPT_VERIFY")).isFalse();
    verify(jdbc).queryForObject(contains("access_starts_at"), eq(Integer.class), eq("expired.user"));
    verify(jdbc, never()).queryForList(anyString(), eq(Integer.class), any(Object[].class));
  }

  @Test
  void explicitUserDenialOverridesDesignationPermission() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(contains("LEFT JOIN webv9_positions"), eq(Integer.class), eq("finance.user")))
        .thenReturn(1);
    when(jdbc.queryForObject(contains("is_super_admin = 1 OR UPPER(role)"), eq(Integer.class), eq("finance.user")))
        .thenReturn(0);
    when(jdbc.queryForObject(contains("JOIN webv9_user_project_scopes"), eq(Integer.class), eq("finance.user")))
        .thenReturn(1);
    when(jdbc.queryForList(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(java.util.List.of(0));
    UserPermissionService service = new UserPermissionService(jdbc);

    assertThat(service.isGranted("finance.user", "Finance", "RECEIPT_VERIFY")).isFalse();

    var permissionSql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(permissionSql.capture(), eq(Integer.class), any(Object[].class));
    assertThat(permissionSql.getValue())
        .contains("WHEN override.is_granted IS NOT NULL THEN override.is_granted");
  }

  @Test
  void financePostingAndVerificationRequireDifferentPermissionActions() throws Exception {
    UserPermissionService permissions = mock(UserPermissionService.class);
    when(permissions.isGranted("finance.verifier", "Finance", "RECEIPT_VERIFY")).thenReturn(true);
    PermissionAuthorizationFilter filter = new PermissionAuthorizationFilter(permissions);

    MockHttpServletResponse verifyResponse = invoke(filter, "POST", "/api/v1/finance/payments/91/verify");
    MockHttpServletResponse postResponse = invoke(filter, "POST", "/api/v1/finance/payments/91/post");

    assertThat(verifyResponse.getStatus()).isEqualTo(200);
    assertThat(postResponse.getStatus()).isEqualTo(403);
    verify(permissions).isGranted("finance.verifier", "Finance", "RECEIPT_VERIFY");
    verify(permissions).isGranted("finance.verifier", "Finance", "RECEIPT_POST");
  }

  @Test
  void reportsEndpointRequiresReportsViewPermissionForNonAdmin() throws Exception {
    UserPermissionService permissions = mock(UserPermissionService.class);
    when(permissions.isGranted("report.user", "Reports", "VIEW")).thenReturn(true);
    when(permissions.isGranted("report.user", "Finance", "VIEW")).thenReturn(true);
    PermissionAuthorizationFilter filter = new PermissionAuthorizationFilter(permissions);

    MockHttpServletResponse allowed =
        invokeAs(filter, "GET", "/api/v1/reports/finance/receipts", "report.user");

    assertThat(allowed.getStatus()).isEqualTo(200);
    verify(permissions).isGranted("report.user", "Reports", "VIEW");
    verify(permissions).isGranted("report.user", "Finance", "VIEW");
    verify(permissions, never()).isSuperAdmin("report.user");
  }

  @Test
  void scopedUsersWithReportsViewPermissionReceiveReportsNavigationModule() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(contains("LEFT JOIN webv9_positions"), eq(Integer.class), eq("report.user")))
        .thenReturn(1);
    when(jdbc.queryForObject(contains("is_super_admin = 1 OR UPPER(role)"), eq(Integer.class), eq("report.user")))
        .thenReturn(0);
    when(jdbc.queryForObject(contains("JOIN webv9_user_project_scopes"), eq(Integer.class), eq("report.user")))
        .thenReturn(1);
    doReturn(java.util.List.of("Finance", "Reports"))
        .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    UserPermissionService permissions = new UserPermissionService(jdbc);

    assertThat(permissions.effectiveModules("report.user")).contains("Reports", "Finance");
  }

  @Test
  void superAdminGetsEveryRegisteredModuleWithoutPermissionOrScopeAssignments() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(contains("LEFT JOIN webv9_positions"), eq(Integer.class), eq("owner")))
        .thenReturn(1);
    when(jdbc.queryForObject(contains("is_super_admin = 1 OR UPPER(role)"), eq(Integer.class), eq("owner")))
        .thenReturn(1);
    when(jdbc.queryForList(contains("SELECT DISTINCT module"), eq(String.class)))
        .thenReturn(java.util.List.of("Custom Registered Module"));
    UserPermissionService permissions = new UserPermissionService(jdbc);

    var modules = permissions.effectiveModules("owner");

    assertThat(modules).contains(
        "Dashboard", "Admin & Settings", "Users & Roles", "Plots & Sales",
        "Finance", "Customer Care", "Reports", "System Logs", "Members",
        "Documents", "Custom Registered Module");
    verify(jdbc, never()).queryForObject(contains("JOIN webv9_user_project_scopes"),
        eq(Integer.class), any(Object[].class));
  }

  @Test
  void superAdminPermissionGrantDoesNotRequireDesignationOrProjectScope() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(contains("LEFT JOIN webv9_positions"), eq(Integer.class), eq("owner")))
        .thenReturn(1);
    when(jdbc.queryForObject(contains("is_super_admin = 1 OR UPPER(role)"), eq(Integer.class), eq("owner")))
        .thenReturn(1);
    UserPermissionService permissions = new UserPermissionService(jdbc);

    assertThat(permissions.isGranted("owner", "Finance", "RECEIPT_POST")).isTrue();
    verify(jdbc, never()).queryForList(anyString(), eq(Integer.class), any(Object[].class));
    verify(jdbc, never()).queryForObject(contains("JOIN webv9_user_project_scopes"),
        eq(Integer.class), any(Object[].class));
  }

  @Test
  void superAdminLoginAndProjectDiscoveryDoNotRequireProjectAssignments() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(contains("LEFT JOIN webv9_positions"), eq(Integer.class), eq("owner")))
        .thenReturn(1);
    when(jdbc.queryForObject(contains("is_super_admin = 1 OR UPPER(role)"), eq(Integer.class), eq("owner")))
        .thenReturn(1);
    when(jdbc.queryForList(contains("SELECT id FROM webv9_projects"), eq(Long.class)))
        .thenReturn(java.util.List.of(17L, 23L));
    UserPermissionService permissions = new UserPermissionService(jdbc);
    ProjectAccessService projectAccess = new ProjectAccessService(jdbc, permissions);

    assertThat(permissions.hasAnyProjectScope("owner")).isTrue();
    assertThat(projectAccess.accessibleProjectIds("owner")).containsExactly(17L, 23L);
    verify(jdbc, never()).queryForObject(contains("JOIN webv9_user_project_scopes"),
        eq(Integer.class), any(Object[].class));
  }

  @Test
  void superAdminCanReachUserAndJobTitleAdministrationWithoutOrdinaryGrants() throws Exception {
    UserPermissionService permissions = mock(UserPermissionService.class);
    when(permissions.isSuperAdmin("owner")).thenReturn(true);
    when(permissions.isGranted("owner", "Users & Roles", "VIEW")).thenReturn(true);
    when(permissions.isGranted("owner", "Users & Roles", "ADD")).thenReturn(true);
    when(permissions.isGranted("owner", "Finance", "VIEW")).thenReturn(true);
    when(permissions.isGranted("owner", "Finance", "RECEIPT_POST")).thenReturn(true);
    when(permissions.isGranted("owner", "Reports", "VIEW")).thenReturn(true);
    PermissionAuthorizationFilter filter = new PermissionAuthorizationFilter(permissions);

    assertThat(invokeAs(filter, "GET", "/api/v1/admin/users", "owner").getStatus()).isEqualTo(200);
    assertThat(invokeAs(filter, "POST", "/api/v1/admin/positions", "owner").getStatus()).isEqualTo(200);
    assertThat(invokeAs(filter, "GET", "/api/v1/finance/payments", "owner").getStatus()).isEqualTo(200);
    assertThat(invokeAs(filter, "POST", "/api/v1/finance/payments/91/post", "owner").getStatus()).isEqualTo(200);
    assertThat(invokeAs(filter, "GET", "/api/v1/reports/finance/receipts", "owner").getStatus()).isEqualTo(200);
    verify(permissions).isGranted("owner", "Users & Roles", "VIEW");
    verify(permissions).isGranted("owner", "Users & Roles", "ADD");
    verify(permissions).isGranted("owner", "Finance", "RECEIPT_POST");
    verify(permissions).isGranted("owner", "Reports", "VIEW");
  }

  @Test
  void forgedSuperAdminAuthorityDoesNotGrantSystemWideAccessToOrdinaryUser() throws Exception {
    UserPermissionService permissions = mock(UserPermissionService.class);
    when(permissions.isSuperAdmin("operator")).thenReturn(false);
    PermissionAuthorizationFilter filter = new PermissionAuthorizationFilter(permissions);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        "operator", "", java.util.List.of(
            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/config/settings");
    request.setServletPath("/api/v1/admin/config/settings");
    MockHttpServletResponse response = new MockHttpServletResponse();
    java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();

    filter.doFilter(request, response, (servletRequest, servletResponse) -> reached.set(true));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(reached).isFalse();
    verify(permissions).isSuperAdmin("operator");
    verify(permissions, never()).isGranted(eq("operator"), anyString(), anyString());
  }

  @Test
  void ordinaryUserStillRequiresAssignedPermissionAndProjectScope() throws Exception {
    UserPermissionService permissions = mock(UserPermissionService.class);
    when(permissions.isSuperAdmin("operator")).thenReturn(false);
    when(permissions.isGranted("operator", "Plots & Sales", "VIEW")).thenReturn(false);
    PermissionAuthorizationFilter filter = new PermissionAuthorizationFilter(permissions);

    assertThat(invokeAs(filter, "GET", "/api/v1/sales/bookings", "operator").getStatus()).isEqualTo(403);
    verify(permissions).isGranted("operator", "Plots & Sales", "VIEW");
  }

  @Test
  void financeReportRequiresFinanceSourcePermissionInAdditionToReportsView() throws Exception {
    UserPermissionService permissions = mock(UserPermissionService.class);
    when(permissions.isGranted("report.user", "Reports", "VIEW")).thenReturn(true);
    PermissionAuthorizationFilter filter = new PermissionAuthorizationFilter(permissions);

    MockHttpServletResponse denied =
        invokeAs(filter, "GET", "/api/v1/reports/finance/receipts", "report.user");

    assertThat(denied.getStatus()).isEqualTo(403);
    verify(permissions).isGranted("report.user", "Reports", "VIEW");
    verify(permissions).isGranted("report.user", "Finance", "VIEW");
  }

  @Test
  void financeReversalRequiresItsOwnExplicitPermission() throws Exception {
    UserPermissionService permissions = mock(UserPermissionService.class);
    when(permissions.isGranted("finance.reverser", "Finance", "RECEIPT_REVERSE")).thenReturn(true);
    PermissionAuthorizationFilter filter = new PermissionAuthorizationFilter(permissions);

    MockHttpServletResponse denied = invokeAs(filter, "POST", "/api/v1/finance/payments/91/reverse", "finance.viewer");
    MockHttpServletResponse allowed = invokeAs(filter, "POST", "/api/v1/finance/payments/91/reverse", "finance.reverser");

    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(allowed.getStatus()).isEqualTo(200);
    verify(permissions).isGranted("finance.viewer", "Finance", "RECEIPT_REVERSE");
    verify(permissions).isGranted("finance.reverser", "Finance", "RECEIPT_REVERSE");
  }

  @Test
  void customerIdentityCannotReachStaffApisAndStaffIdentityCannotUseCustomerApis() throws Exception {
    UserPermissionService permissions = mock(UserPermissionService.class);
    PermissionAuthorizationFilter filter = new PermissionAuthorizationFilter(permissions);

    assertThat(invokeAs(filter, "GET", "/api/v1/dashboard", "customer:17").getStatus()).isEqualTo(403);
    assertThat(invokeAs(filter, "GET", "/api/v1/customer/properties", "staff.user").getStatus()).isEqualTo(403);
    assertThat(invokeAs(filter, "GET", "/api/v1/customer/properties", "customer:17").getStatus()).isEqualTo(200);
    verifyNoInteractions(permissions);
  }

  @Test
  void missingPermissionRejectsApiBeforeControllerExecution() throws Exception {
    UserPermissionService permissions = mock(UserPermissionService.class);
    when(permissions.isGranted("operator", "Plots & Sales", "ADD")).thenReturn(false);
    PermissionAuthorizationFilter filter = new PermissionAuthorizationFilter(permissions);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("operator", "", java.util.List.of()));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sales/bookings");
    MockHttpServletResponse response = new MockHttpServletResponse();
    java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();

    filter.doFilter(request, response, (servletRequest, servletResponse) -> reached.set(true));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(reached).isFalse();
  }

  private MockHttpServletResponse invoke(PermissionAuthorizationFilter filter, String method, String path) throws Exception {
    return invokeAs(filter, method, path, "finance.verifier");
  }

  private MockHttpServletResponse invokeAs(PermissionAuthorizationFilter filter, String method, String path,
                                           String principal) throws Exception {
    var auth = new UsernamePasswordAuthenticationToken(principal, "", java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    return response;
  }
}
