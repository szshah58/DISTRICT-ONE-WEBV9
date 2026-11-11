package com.districtone.webv9;

import com.districtone.webv9.security.PermissionAuthorizationFilter;
import com.districtone.webv9.security.UserPermissionService;
import com.districtone.webv9.service.FinanceVerificationAuditService;
import com.districtone.webv9.service.FinanceVerificationRequest;
import com.districtone.webv9.service.FinanceVerificationService;
import com.districtone.webv9.service.PaymentAllocationReservationService;
import com.districtone.webv9.service.PaymentReservationSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinanceVerificationAuthorizationTest {
  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void financeActionPermissionNotRoleControlsVerificationApi() throws Exception {
    UserPermissionService permissions = mock(UserPermissionService.class);
    PermissionAuthorizationFilter filter = new PermissionAuthorizationFilter(permissions);
    when(permissions.isGranted("recovery-user", "Finance", "RECEIPT_VERIFY")).thenReturn(false);
    when(permissions.isGranted("verifier-user", "Finance", "RECEIPT_VERIFY")).thenReturn(true);

    MockHttpServletResponse denied = invoke(filter, "recovery-user", "ROLE_FINANCE");
    MockHttpServletResponse allowed = invoke(filter, "verifier-user", "ROLE_RECOVERY");

    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(allowed.getStatus()).isEqualTo(200);
    verify(permissions).isGranted("recovery-user", "Finance", "RECEIPT_VERIFY");
    verify(permissions).isGranted("verifier-user", "Finance", "RECEIPT_VERIFY");
  }

  @Test
  void serviceRejectsVerificationWithoutExplicitDesignationActionPermission() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    PaymentAllocationReservationService reservations = mock(PaymentAllocationReservationService.class);
    FinanceVerificationAuditService audit = mock(FinanceVerificationAuditService.class);
    UserPermissionService permissions = mock(UserPermissionService.class);
    PaymentReservationSnapshot payment = new PaymentReservationSnapshot(1L, 10L, new BigDecimal("10.00"),
        "PENDING", "R-1", "CASH", null, null, null, null, null, null, 2L, 11L, null);
    when(reservations.validateExistingReservations(1L)).thenReturn(payment);
    when(permissions.isGranted("recovery-user", "Finance", "RECEIPT_VERIFY")).thenReturn(false);
    FinanceVerificationService service = new FinanceVerificationService(jdbc, reservations, audit, permissions);

    assertThatThrownBy(() -> service.verify(1L,
        new FinanceVerificationRequest(new BigDecimal("10.00"), null, "Confirmed"), "recovery-user"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("receipt-verification permission");

    verify(jdbc, never()).update(anyString(), any(Object[].class));
    verify(audit).recordFailedVerification(1L, "recovery-user",
        "An active designation with receipt-verification permission is required.");
  }

  @Test
  void postReportEndpointRequiresFinanceViewPermission() throws Exception {
    UserPermissionService permissions = mock(UserPermissionService.class);
    PermissionAuthorizationFilter filter = new PermissionAuthorizationFilter(permissions);
    when(permissions.isGranted("report-user", "Finance", "VIEW")).thenReturn(true);
    SecurityContextHolder.getContext().setAuthentication(authentication("report-user", "ROLE_DATA_ENTRY"));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/finance/ledger/report");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean reached = new AtomicBoolean();

    filter.doFilter(request, response, (servletRequest, servletResponse) -> reached.set(true));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(reached).isTrue();
    verify(permissions).isGranted("report-user", "Finance", "VIEW");
  }

  private MockHttpServletResponse invoke(PermissionAuthorizationFilter filter, String username, String role)
      throws Exception {
    SecurityContextHolder.getContext().setAuthentication(authentication(username, role));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/finance/payments/1/verify");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean reached = new AtomicBoolean();
    filter.doFilter(request, response, (servletRequest, servletResponse) -> reached.set(true));
    if (response.getStatus() == 403) assertThat(reached).isFalse();
    else assertThat(reached).isTrue();
    return response;
  }

  private UsernamePasswordAuthenticationToken authentication(String username, String role) {
    return new UsernamePasswordAuthenticationToken(username, "",
        List.of(new SimpleGrantedAuthority(role)));
  }
}
