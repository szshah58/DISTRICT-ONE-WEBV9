package com.districtone.webv9;

import com.districtone.webv9.controller.CustomerCareController;
import com.districtone.webv9.security.PermissionAuthorizationFilter;
import com.districtone.webv9.security.ProjectAccessService;
import com.districtone.webv9.security.UserPermissionService;
import com.districtone.webv9.service.ComplaintApiDtos.AddComplaintHistoryRequest;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintCreated;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintDetail;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintHistoryCreated;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintHistoryEntry;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintListItem;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintPage;
import com.districtone.webv9.service.ComplaintApiDtos.CreateComplaintRequest;
import com.districtone.webv9.service.ComplaintApiDtos.CustomerReference;
import com.districtone.webv9.service.CustomerComplaintService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class CustomerCareControllerTest {
  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void customerCareRoleCanSearchButLookupDoesNotReturnFinancialFields() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      when(jdbc.queryForList(anyString(), any(Object[].class)))
          .thenReturn(List.of(Map.of("id", 8L, "mbrNo", "MBR-8", "name", "Test Customer")));

      List<Map<String, Object>> results = controller.searchCustomers("MBR-8");

      assertThat(results).hasSize(1).allSatisfy(row ->
          assertThat(row).doesNotContainKeys("ledger", "payments", "balance", "outstanding"));
      verify(jdbc).queryForList(anyString(), any(Object[].class));
    }
  }

  @Test
  void customerProfileUsesExistingRelationshipsWithoutQueryingFinancialTables() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      when(jdbc.queryForMap(anyString(), eq(8L))).thenReturn(Map.of("id", 8L, "name", "Test Customer"));
      when(jdbc.queryForList(anyString(), eq(8L))).thenReturn(List.of());
      when(jdbc.queryForList(anyString(), eq(8L), eq(8L), eq(8L))).thenReturn(List.of());

      Map<String, Object> profile = controller.getCustomerProfile(8L);

      assertThat(profile).containsKeys("customer", "registrations", "documents")
          .doesNotContainKeys("ledger", "payments", "receivables", "outstanding");
      verify(jdbc).queryForMap(contains("FROM members m"), eq(8L));
      verify(jdbc, times(1)).queryForList(contains("FROM registrations r"), eq(8L));
      verify(jdbc, times(1)).queryForList(contains("FROM webv9_documents d"), eq(8L), eq(8L), eq(8L));
      verify(jdbc, never()).queryForList(contains("payments_receipts"), any(Object[].class));
      verify(jdbc, never()).queryForList(contains("webv9_customer_ledger"), any(Object[].class));
    }
  }

  @Test
  void customerLookupApiReturnsScopedSearchResults() throws Exception {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      when(jdbc.queryForList(anyString(), any(Object[].class)))
          .thenReturn(List.of(Map.of("id", 8L, "mbrNo", "MBR-8", "name", "Test Customer")));
      MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

      mvc.perform(get("/api/v1/customer-care/customers").param("q", "MBR-8"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").value(8))
          .andExpect(jsonPath("$[0].mbrNo").value("MBR-8"));
    }
  }

  @Test
  void customerProfileApiReturnsOnlyProfileRelationshipAndDocumentSections() throws Exception {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      when(jdbc.queryForMap(contains("FROM members m"), eq(8L)))
          .thenReturn(Map.of("id", 8L, "mbrNo", "MBR-8", "name", "Test Customer"));
      when(jdbc.queryForList(contains("FROM registrations r"), eq(8L))).thenReturn(List.of());
      when(jdbc.queryForList(contains("FROM webv9_documents d"), eq(8L), eq(8L), eq(8L))).thenReturn(List.of());
      MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

      mvc.perform(get("/api/v1/customer-care/customers/8"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.customer.mbrNo").value("MBR-8"))
          .andExpect(jsonPath("$.registrations").isArray())
          .andExpect(jsonPath("$.documents").isArray())
          .andExpect(jsonPath("$.ledger").doesNotExist())
          .andExpect(jsonPath("$.payments").doesNotExist());
    }
  }

  @Test
  void customerCareApiRequiresTheExistingCustomerCareViewPermission() throws Exception {
    UserPermissionService permissions = mock(UserPermissionService.class);
    PermissionAuthorizationFilter filter = new PermissionAuthorizationFilter(permissions);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/customer-care/complaints");
    MockHttpServletResponse response = new MockHttpServletResponse();
    SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
    when(permissions.isGranted("care-user", "Customer Care", "VIEW")).thenReturn(true);
    AtomicBoolean reached = new AtomicBoolean();

    filter.doFilter(request, response, (servletRequest, servletResponse) -> reached.set(true));

    assertThat(reached).isTrue();
    verify(permissions).isGranted("care-user", "Customer Care", "VIEW");

    when(permissions.isGranted("care-user", "Customer Care", "VIEW")).thenReturn(false);
    reached.set(false);
    response = new MockHttpServletResponse();
    filter.doFilter(request, response, (servletRequest, servletResponse) -> reached.set(true));
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(reached).isFalse();
  }

  @Test
  void complaintCreationUsesExistingCaseTableAndOpenDefault() throws Exception {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      CustomerComplaintService complaints = context.getBean(CustomerComplaintService.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      when(complaints.create(any(CreateComplaintRequest.class), any()))
          .thenReturn(new ComplaintCreated(73L, "OPEN", LocalDateTime.of(2026, 10, 3, 10, 0)));
      MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

      mvc.perform(post("/api/v1/customer-care/complaints")
              .contentType("application/json")
              .content("{\"memberId\":8,\"subject\":\"Plot boundary concern\",\"description\":\"Details\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.complaintId").value(73))
          .andExpect(jsonPath("$.status").value("OPEN"));

      verify(complaints).create(new CreateComplaintRequest(8L, "Plot boundary concern", "Details"),
          authentication("care-user", "ROLE_CUSTOMER_CARE"));
      assertThat(CustomerComplaintService.class.getMethod("create", CreateComplaintRequest.class,
          org.springframework.security.core.Authentication.class)
          .getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();
    }
  }

  @Test
  void complaintDetailReturnsExistingCaseNotesAsHistory() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      CustomerComplaintService complaints = context.getBean(CustomerComplaintService.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      List<ComplaintHistoryEntry> history = List.of(new ComplaintHistoryEntry(5L, "Contacted customer",
          LocalDateTime.of(2026, 10, 3, 11, 0), "care-user"));
      when(complaints.get(73L)).thenReturn(new ComplaintDetail(73L, "Plot boundary concern", null,
          "OPEN", LocalDateTime.of(2026, 10, 3, 10, 0),
          new CustomerReference(8L, "MBR-8", "Test Customer", null), List.of(), history));

      ComplaintDetail result = controller.getComplaint(73L);

      assertThat(result.history()).isEqualTo(history);
      verify(complaints).get(73L);
    }
  }

  @Test
  void complaintDetailApiReturnsComplaintAndItsHistory() throws Exception {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      CustomerComplaintService complaints = context.getBean(CustomerComplaintService.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      when(complaints.get(73L)).thenReturn(new ComplaintDetail(73L, "Plot boundary concern", null,
          "OPEN", LocalDateTime.of(2026, 10, 3, 10, 0),
          new CustomerReference(8L, "MBR-8", "Test Customer", null), List.of(),
          List.of(new ComplaintHistoryEntry(5L, "Contacted customer",
              LocalDateTime.of(2026, 10, 3, 11, 0), "care-user"))));
      MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

      mvc.perform(get("/api/v1/customer-care/complaints/73"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.complaintId").value(73))
          .andExpect(jsonPath("$.status").value("OPEN"))
          .andExpect(jsonPath("$.history[0].note").value("Contacted customer"))
          .andExpect(jsonPath("$.payments").doesNotExist())
          .andExpect(jsonPath("$.ledger").doesNotExist());
    }
  }

  @Test
  void complaintHistoryCanBeExtendedUsingTheExistingCaseNotesTable() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      CustomerComplaintService complaints = context.getBean(CustomerComplaintService.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      when(complaints.get(73L)).thenReturn(new ComplaintDetail(73L, "Plot boundary concern", null,
          "OPEN", LocalDateTime.of(2026, 10, 3, 10, 0),
          new CustomerReference(8L, "MBR-8", "Test Customer", null), List.of(), List.of()));
      when(complaints.addHistory(eq(73L), any(AddComplaintHistoryRequest.class), any()))
          .thenReturn(new ComplaintHistoryCreated(6L, 73L, LocalDateTime.of(2026, 10, 3, 12, 0)));

      ComplaintHistoryCreated result = controller.addComplaintNote(73L,
          new AddComplaintHistoryRequest("Followed up with customer"),
          authentication("care-user", "ROLE_CUSTOMER_CARE"));

      assertThat(result.historyId()).isEqualTo(6L);
      assertThat(result.complaintId()).isEqualTo(73L);
      verify(complaints).addHistory(73L, new AddComplaintHistoryRequest("Followed up with customer"),
          authentication("care-user", "ROLE_CUSTOMER_CARE"));
    }
  }

  @Test
  void complaintHistoryApiAddsNoteToExistingComplaint() throws Exception {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      CustomerComplaintService complaints = context.getBean(CustomerComplaintService.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      when(complaints.get(73L)).thenReturn(new ComplaintDetail(73L, "Plot boundary concern", null,
          "OPEN", LocalDateTime.of(2026, 10, 3, 10, 0),
          new CustomerReference(8L, "MBR-8", "Test Customer", null), List.of(), List.of()));
      when(complaints.addHistory(eq(73L), any(AddComplaintHistoryRequest.class), any()))
          .thenReturn(new ComplaintHistoryCreated(6L, 73L, LocalDateTime.of(2026, 10, 3, 12, 0)));
      MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

      mvc.perform(post("/api/v1/customer-care/complaints/73/history")
              .contentType("application/json").content("{\"note\":\"Followed up with customer\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.historyId").value(6))
          .andExpect(jsonPath("$.complaintId").value(73))
          .andExpect(jsonPath("$.createdAt").exists());
    }
  }

  @Test
  void complaintHistoryReadApiReturnsStructuredHistoryEntries() throws Exception {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      CustomerComplaintService complaints = context.getBean(CustomerComplaintService.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      when(complaints.get(73L)).thenReturn(new ComplaintDetail(73L, "Plot boundary concern", null,
          "OPEN", LocalDateTime.of(2026, 10, 3, 10, 0),
          new CustomerReference(8L, "MBR-8", "Test Customer", null), List.of(), List.of()));
      when(complaints.history(73L)).thenReturn(List.of(new ComplaintHistoryEntry(6L,
          "Followed up with customer", LocalDateTime.of(2026, 10, 3, 12, 0), "care-user")));
      MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

      mvc.perform(get("/api/v1/customer-care/complaints/73/history"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].historyId").value(6))
          .andExpect(jsonPath("$[0].recordedBy").value("care-user"));
    }
  }

  @Test
  void complaintSearchApiFiltersTheComplaintRegister() throws Exception {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      CustomerComplaintService complaints = context.getBean(CustomerComplaintService.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      when(complaints.list(eq("boundary"), eq(0), eq(100))).thenReturn(new ComplaintPage(
          List.of(new ComplaintListItem(73L, "Plot boundary concern", "Details", "OPEN",
              LocalDateTime.of(2026, 10, 3, 10, 0),
              new CustomerReference(8L, "MBR-8", "Test Customer", null), 0)), 0, 100, 1));
      MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

      mvc.perform(get("/api/v1/customer-care/complaints").param("q", "boundary"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items[0].complaintId").value(73))
          .andExpect(jsonPath("$.items[0].status").value("OPEN"));
    }
  }

  @Test
  void customerCareListDoesNotAcceptClientSuppliedMemberFilter() throws Exception {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      CustomerComplaintService complaints = context.getBean(CustomerComplaintService.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      when(complaints.list(null, 0, 100)).thenReturn(new ComplaintPage(List.of(), 0, 100, 0));
      MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

      mvc.perform(get("/api/v1/customer-care/complaints").param("memberId", "999"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(0));

      verify(complaints).list(null, 0, 100);
      verifyNoMoreInteractions(complaints);
    }
  }

  @Test
  void complaintListRejectsInvalidPageParameters() throws Exception {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      CustomerComplaintService complaints = context.getBean(CustomerComplaintService.class);
      CustomerCareController controller = context.getBean(CustomerCareController.class);
      SecurityContextHolder.getContext().setAuthentication(authentication("care-user", "ROLE_CUSTOMER_CARE"));
      doThrow(new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid page parameters."))
          .when(complaints).list(null, -1, 25);
      MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

      mvc.perform(get("/api/v1/customer-care/complaints").param("page", "-1"))
          .andExpect(status().isBadRequest());
    }
  }

  private static UsernamePasswordAuthenticationToken authentication(String username, String role) {
    return new UsernamePasswordAuthenticationToken(username, "", List.of(new SimpleGrantedAuthority(role)));
  }

  @Configuration
  @EnableMethodSecurity
  static class TestConfig {
    @Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
    @Bean CustomerComplaintService customerComplaintService() { return mock(CustomerComplaintService.class); }
    @Bean ProjectAccessService projectAccessService() {
      ProjectAccessService service = mock(ProjectAccessService.class);
      when(service.canAccessMember(anyString(), anyLong())).thenReturn(true);
      when(service.isSuperAdmin(anyString())).thenReturn(true);
      return service;
    }
    @Bean CustomerCareController customerCareController(JdbcTemplate jdbc, CustomerComplaintService complaints,
                                                         ProjectAccessService projectAccess) {
      return new CustomerCareController(jdbc, complaints, projectAccess);
    }
  }
}
