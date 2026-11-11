package com.districtone.webv9;

import com.districtone.webv9.controller.CustomerLedgerController;
import com.districtone.webv9.security.JwtAuthenticationFilter;
import com.districtone.webv9.security.JwtService;
import com.districtone.webv9.security.SecurityConfig;
import com.districtone.webv9.security.UserPermissionService;
import com.districtone.webv9.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.web.FilterChainProxy;
import jakarta.servlet.Filter;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
@WebMvcTest(CustomerLedgerController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = true)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class})
@TestPropertySource(properties = {
    "app.jwt-secret=unit-test-only-jwt-signing-key-32-bytes-minimum",
    "app.cors-origins=http://localhost"
})
class CustomerLedgerSecurityFlowTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwt;
  @Autowired private FilterChainProxy securityFilterChain;

  @MockBean private JdbcTemplate jdbc;
  @MockBean private UserPermissionService permissions;
  @MockBean private UserRepository users;

  @BeforeEach
  void resetJdbc() {
    reset(jdbc);
  }

  @Test
  void productionSecurityChainContainsRealJwtAndPermissionFilters() {
    List<Filter> filters = securityFilterChain.getFilters("/api/v1/customer/properties/41/ledger");
    assertThat(filters).anyMatch(JwtAuthenticationFilter.class::isInstance);
    assertThat(filters).anyMatch(com.districtone.webv9.security.PermissionAuthorizationFilter.class::isInstance);
  }

  @Test
  void customerAuthorityCanReadAssociatedPropertyLedger() throws Exception {
    stubLedgerForOwnedProperty();

    mockMvc.perform(get("/api/v1/customer/properties/41/ledger")
            .header(AUTHORIZATION, bearer("customer:7001", "CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalDue").value(100))
        .andExpect(jsonPath("$.totalPaid").value(25))
        .andExpect(jsonPath("$.outstanding").value(75));
  }

  @Test
  void customerCannotReadAnotherCustomersProperty() throws Exception {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

    mockMvc.perform(get("/api/v1/customer/properties/41/ledger")
            .header(AUTHORIZATION, bearer("customer:7002", "CUSTOMER")))
        .andExpect(status().isNotFound());
  }

  @Test
  void missingTokenIsRejected(CapturedOutput output) throws Exception {
    mockMvc.perform(get("/api/v1/customer/properties/41/ledger"))
        .andExpect(status().isForbidden());
    assertThat(output).contains("TEMP customer-ledger 403")
        .contains("SecurityFilterChain.anyRequest().authenticated()")
        .contains("authenticationExists=false")
        .contains("authenticationType=none")
        .contains("authorities=[]");
  }

  @Test
  void invalidTokenIsRejectedAsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/customer/properties/41/ledger")
            .header(AUTHORIZATION, "Bearer not-a-valid-jwt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredTokenIsRejectedAsUnauthorized() throws Exception {
    String token = jwt.create("customer:7001", "CUSTOMER", Duration.ofSeconds(-1));

    mockMvc.perform(get("/api/v1/customer/properties/41/ledger")
            .header(AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void nonCustomerRoleIsRejectedBeforeLedgerLookup(CapturedOutput output) throws Exception {
    mockMvc.perform(get("/api/v1/customer/properties/41/ledger")
            .header(AUTHORIZATION, bearer("customer:7001", "STAFF")))
        .andExpect(status().isForbidden());
    assertThat(output).contains("TEMP customer-ledger 403")
        .contains("CustomerLedgerController.@PreAuthorize(hasRole(CUSTOMER))")
        .contains("authenticationType=UsernamePasswordAuthenticationToken")
        .contains("authorities=[ROLE_STAFF]");
  }

  private String bearer(String subject, String role) {
    return "Bearer " + jwt.create(subject, role);
  }

  private void stubLedgerForOwnedProperty() {
    Map<String, Object> property = Map.ofEntries(
        Map.entry("registrationId", 41L),
        Map.entry("regNo", "TEST-REG"),
        Map.entry("formNo", "TEST-FORM"),
        Map.entry("category", "RESIDENTIAL"),
        Map.entry("plotId", 51L),
        Map.entry("plotNo", "TEST-PLOT"),
        Map.entry("plotSizeMarla", 5),
        Map.entry("plotType", "RESIDENTIAL"),
        Map.entry("blockCode", "T"),
        Map.entry("blockName", "Test Block"),
        Map.entry("bookingId", 61L));
    Map<String, Object> ledgerRow = Map.of(
        "ledgerId", 1L,
        "memberId", 7001L,
        "bookingId", 61L,
        "paymentId", 71L,
        "debit", new BigDecimal("100"),
        "credit", new BigDecimal("25"),
        "runningBalance", new BigDecimal("75"));

    when(jdbc.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(property), List.of(ledgerRow));
    when(jdbc.queryForMap(anyString(), any(Object[].class)))
        .thenReturn(Map.of(
            "totalDue", new BigDecimal("100"),
            "totalPaid", new BigDecimal("25"),
            "outstanding", new BigDecimal("75")));
  }
}
