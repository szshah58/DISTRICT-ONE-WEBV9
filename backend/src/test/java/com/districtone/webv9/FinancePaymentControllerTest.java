package com.districtone.webv9;

import com.districtone.webv9.controller.FinancePaymentController;
import com.districtone.webv9.service.RecoveryEntryCreationService;
import com.districtone.webv9.service.RecoveryEntryRequest;
import com.districtone.webv9.service.FinanceVerificationService;
import com.districtone.webv9.service.FinanceVerificationRequest;
import com.districtone.webv9.service.AllocationBasedPaymentPostingService;
import com.districtone.webv9.service.CustomerLedgerReportService;
import com.districtone.webv9.security.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FinancePaymentControllerTest {

  private JdbcTemplate jdbc;
  private RecoveryEntryCreationService recoveryEntries;
  private FinanceVerificationService financeVerification;
  private AllocationBasedPaymentPostingService paymentPosting;
  private CustomerLedgerReportService ledgerReports;
  private ProjectAccessService projectAccess;
  private FinancePaymentController controller;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    jdbc = Mockito.mock(JdbcTemplate.class);
    recoveryEntries = Mockito.mock(RecoveryEntryCreationService.class);
    financeVerification = Mockito.mock(FinanceVerificationService.class);
    paymentPosting = Mockito.mock(AllocationBasedPaymentPostingService.class);
    ledgerReports = Mockito.mock(CustomerLedgerReportService.class);
    projectAccess = Mockito.mock(ProjectAccessService.class);
    controller = new FinancePaymentController(jdbc, recoveryEntries, financeVerification, paymentPosting, ledgerReports, projectAccess);
    mvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void createRecoveryDelegatesSharedAllocationRequestToCreationService() {
    RecoveryEntryRequest req = new RecoveryEntryRequest(
        101L, new BigDecimal("50000"), LocalDate.now(), "BANK_TRANSFER",
        1L, "Meezan Bank", "020101", "REF123", LocalDate.now(), null, null, "Test",
        List.of(new com.districtone.webv9.service.AllocationDraft(1, 101L, 201L, 301L, 1L, 401L, null,
            new BigDecimal("50000"), "Installment"))
    );
    when(recoveryEntries.create(req, null)).thenReturn(Map.of("id", 201L, "receiptNo", "DO-000016", "status", "RECOVERY_ENTRY"));

    Map<String, Object> result = controller.createRecovery(req, null);
    assertThat(result).containsEntry("receiptNo", "DO-000016").containsEntry("status", "RECOVERY_ENTRY");
    verify(recoveryEntries).create(req, null);
  }

  @Test
  void verifyPaymentTransitionsStatusToVerified() {
    FinanceVerificationRequest request = new FinanceVerificationRequest(new BigDecimal("75000"), "BANK-REF-1", "Matched bank statement");
    when(financeVerification.verify(201L, request, null)).thenReturn(Map.of("id", 201L, "receiptNo", "DO-000016", "status", "VERIFIED"));

    Map<String, Object> result = controller.verifyPayment(201L, request, null);

    assertThat(result).containsEntry("status", "VERIFIED");
    verify(financeVerification).verify(201L, request, null);
  }

  @Test
  void postPaymentDelegatesTransactionalPostingAndDoesNotWriteAuditAfterCommit() {
    when(paymentPosting.post(201L, null)).thenReturn(Map.of("id", 201L, "receiptNo", "DO-000016", "status", "POSTED"));
    Map<String, Object> result = controller.postPayment(201L, null);
    assertThat(result).containsEntry("status", "POSTED");
    verify(paymentPosting).post(201L, null);
    Mockito.verifyNoInteractions(jdbc);
  }

  @Test
  void postPaymentRejectsAlreadyPostedPayment() {
    when(paymentPosting.post(201L, null)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "already posted"));
    assertThatThrownBy(() -> controller.postPayment(201L, null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("already posted");
  }

  @Test
  void paymentListSelectsStructuredMetadataSeparatelyFromMethodAndReference() {
    Map<String, Object> receipt = Map.of("id", 9L, "salesClassification", "Variation",
        "bankInstrumentType", "Cheque", "paymentChannel", "BANK", "referenceNo", "REF-9");
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(receipt));

    List<Map<String, Object>> rows = controller.listPayments("ALL", "DO-000009", null);

    assertThat(rows).containsExactly(receipt);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), any(Object[].class));
    assertThat(sql.getValue()).contains("p.sales_classification AS salesClassification")
        .contains("p.bank_instrument_type AS bankInstrumentType")
        .contains("p.payment_reference AS paymentReference")
        .contains("p.deposit_reference AS referenceNo")
        .contains("AS paymentChannel");
  }

  @Test
  void receiptManagementFiltersAreCombinableAndPaginated() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

    controller.listPayments("ALL", null, null, "2026-10-01", "2026-10-31", "DO-000", "REG-10",
        "Customer", "District One", "Lahore", "BANK", "BANK-REF", "YES", "PENDING", 2, 25);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), any(Object[].class));
    assertThat(sql.getValue()).contains("p.payment_date >= ?").contains("p.payment_date <= ?")
        .contains("p.receipt_no LIKE ?").contains("r.reg_no LIKE ?").contains("m.name LIKE ?")
        .contains("pr.project_name LIKE ?").contains("pr.location LIKE ?")
        .contains("p.deposit_reference LIKE ?").contains("p.posted_at IS NOT NULL")
        .contains("p.status IN ('RECOVERY_ENTRY','PENDING','PENDING_VERIFICATION')")
        .contains("LIMIT ? OFFSET ?");
  }

  @Test
  void duplicateBankReferencesAreFinanceReviewCandidatesScopedByBankAndReceivingAccount() {
    when(jdbc.queryForList(contains("AND EXISTS"))).thenReturn(List.of(
        Map.of("id", 1L, "bankId", 1L, "receivingAccountKey", "ACCT-1", "referenceKey", "REF-1",
            "receiptNo", "R-1", "status", "POSTED"),
        Map.of("id", 2L, "bankId", 1L, "receivingAccountKey", "ACCT-1", "referenceKey", "REF-1",
            "receiptNo", "R-2", "status", "PENDING")));
    when(projectAccess.canAccessPayment("finance-user", 1L)).thenReturn(true);
    when(projectAccess.canAccessPayment("finance-user", 2L)).thenReturn(true);
    List<Map<String, Object>> candidates = controller.bankReferenceConflicts(
        new UsernamePasswordAuthenticationToken("finance-user", "", List.of()));
    assertThat(candidates).hasSize(1);
    assertThat(candidates.get(0)).containsEntry("candidateCount", 2L)
        .containsEntry("candidates", "1:R-1:POSTED,2:R-2:PENDING");
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture());
    assertThat(sql.getValue())
        .contains("AND EXISTS")
        .contains("duplicate.id <> p.id")
        .contains("duplicate.bank_id = p.bank_id")
        .contains("UPPER(TRIM(p.receiving_account))")
        .contains("UPPER(TRIM(p.deposit_reference))")
        .doesNotContain("POSTED");
    verify(projectAccess).canAccessPayment("finance-user", 1L);
    verify(projectAccess).canAccessPayment("finance-user", 2L);
    var method = org.springframework.util.ReflectionUtils.findMethod(
        FinancePaymentController.class, "bankReferenceConflicts", org.springframework.security.core.Authentication.class);
    assertThat(method).isNotNull();
  }

  @Test
  void duplicateBankReferenceGroupIsHiddenWhenScopeExposesOnlyOnePayment() {
    when(jdbc.queryForList(contains("AND EXISTS"))).thenReturn(List.of(
        Map.of("id", 1L, "bankId", 1L, "receivingAccountKey", "ACCT-1", "referenceKey", "REF-1",
            "receiptNo", "R-1", "status", "POSTED"),
        Map.of("id", 2L, "bankId", 1L, "receivingAccountKey", "ACCT-1", "referenceKey", "REF-1",
            "receiptNo", "R-2", "status", "PENDING")));
    when(projectAccess.canAccessPayment("finance-user", 1L)).thenReturn(true);
    when(projectAccess.canAccessPayment("finance-user", 2L)).thenReturn(false);

    List<Map<String, Object>> candidates = controller.bankReferenceConflicts(
        new UsernamePasswordAuthenticationToken("finance-user", "", List.of()));

    assertThat(candidates).isEmpty();
  }

  @Test
  void duplicateBankReferenceReportRequiresAnAuthenticatedUser() {
    assertThatThrownBy(() -> controller.bankReferenceConflicts(null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Authentication is required");
    Mockito.verifyNoInteractions(jdbc);
  }

  @Test
  void getLedgerCalculatesRunningBalance() {
    when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM members WHERE member_id = ?"), eq(Integer.class), eq(101L)))
        .thenReturn(1);
    when(jdbc.queryForList(contains("FROM webv9_customer_ledger"), eq(101L)))
        .thenReturn(List.of(
            Map.of("id", 1L, "debit", new BigDecimal("100000"), "credit", BigDecimal.ZERO, "description", "Plot Booking Charge"),
            Map.of("id", 2L, "debit", BigDecimal.ZERO, "credit", new BigDecimal("25000"), "description", "Instalment Payment (DO-000001)")
        ));

    List<Map<String, Object>> ledger = controller.getLedger(101L);

    assertThat(ledger).hasSize(2);
    assertThat(ledger.get(0)).containsEntry("runningBalance", new BigDecimal("100000"));
    assertThat(ledger.get(1)).containsEntry("runningBalance", new BigDecimal("75000"));
  }

  @Test
  void allocationDueOptionsAreScopedToTheSelectedCustomerAndBooking() {
    when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM members WHERE member_id = ?"), eq(Integer.class), eq(101L)))
        .thenReturn(1);
    Map<String, Object> receivable = Map.of(
        "receivableId", 401L, "registrationId", 301L, "bookingId", 201L,
        "plotId", 501L, "chargeTypeId", 2L, "availableDue", new BigDecimal("125.00"));
    when(jdbc.queryForList(contains("FROM webv9_receivables vr"), eq(101L), eq(201L)))
        .thenReturn(List.of(receivable));

    List<Map<String, Object>> dues = controller.getMemberAllocationDues(101L, 201L);

    assertThat(dues).containsExactly(receivable);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), eq(101L), eq(201L));
    assertThat(sql.getValue())
        .contains("vr.member_id = ? AND vr.booking_id = ?")
        .contains("vr.registration_id IS NOT NULL AND vr.plot_id IS NOT NULL AND vr.charge_type_id IS NOT NULL")
        .contains("webv9_payment_allocations a")
        .contains("GREATEST(0, vr.original_amount + vr.surcharge_amount - vr.adjustment_amount")
        .contains("'RECOVERY_ENTRY','PENDING','PENDING_VERIFICATION','VERIFIED'");
  }

  @Test
  void memberBookingsIncludeTheirConfiguredProjectLocation() {
    when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM members WHERE member_id = ?"), eq(Integer.class), eq(101L)))
        .thenReturn(1);
    Map<String, Object> booking = Map.of(
        "id", 201L, "bookingNo", "SB-000201", "registrationNo", "REG-201",
        "plotNo", "A-12", "blockName", "Block A", "projectName", "District One Housing",
        "location", "Main Site");
    when(jdbc.queryForList(contains("LEFT JOIN webv9_projects pr"), eq(101L)))
        .thenReturn(List.of(booking));

    List<Map<String, Object>> bookings = controller.getMemberBookings(101L);

    assertThat(bookings).containsExactly(booking);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), eq(101L));
    assertThat(sql.getValue())
        .contains("pr.project_name AS projectName")
        .contains("pr.location AS location")
        .contains("LEFT JOIN webv9_projects pr ON pr.id = b.project_id");
  }

  @Test
  void paymentAllocationDetailsRequireAnExistingReceiptAndReturnItsPersistedLines() {
    Map<String, Object> payment = Map.of("id", 44L, "memberId", 101L);
    Map<String, Object> allocation = Map.of(
        "registrationId", 301L, "bookingId", 201L, "plotId", 501L,
        "chargeTypeId", 2L, "receivableId", 401L, "allocatedAmount", new BigDecimal("125.00"));
    when(jdbc.queryForMap(contains("FROM payments_receipts p"), eq(44L))).thenReturn(payment);
    when(jdbc.queryForList(contains("FROM webv9_payment_allocations a"), eq(44L)))
        .thenReturn(List.of(allocation));

    List<Map<String, Object>> rows = controller.getPaymentAllocations(44L);

    assertThat(rows).containsExactly(allocation);
    verify(jdbc).queryForMap(contains("FROM payments_receipts p"), eq(44L));
    verify(jdbc).queryForList(contains("FROM webv9_payment_allocations a"), eq(44L));
  }

  @Test
  void allocationDueApiReturnsCustomerAndBookingScopedReceivables() throws Exception {
    when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM members WHERE member_id = ?"), eq(Integer.class), eq(101L)))
        .thenReturn(1);
    when(jdbc.queryForList(contains("FROM webv9_receivables vr"), eq(101L), eq(201L)))
        .thenReturn(List.of(Map.of(
            "receivableId", 401L, "bookingId", 201L, "chargeTypeId", 2L,
            "availableDue", new BigDecimal("125.00"))));

    mvc.perform(get("/api/v1/finance/members/101/allocation-dues").param("bookingId", "201"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].receivableId").value(401))
        .andExpect(jsonPath("$[0].availableDue").value(125.00));
  }

  @Test
  void paymentAllocationApiReturnsPersistedChargeHeadLines() throws Exception {
    when(jdbc.queryForMap(contains("FROM payments_receipts p"), eq(44L)))
        .thenReturn(Map.of("id", 44L, "memberId", 101L));
    when(jdbc.queryForList(contains("FROM webv9_payment_allocations a"), eq(44L)))
        .thenReturn(List.of(Map.of(
            "registrationId", 301L, "bookingId", 201L, "plotId", 501L,
            "chargeTypeId", 2L, "receivableId", 401L, "allocatedAmount", new BigDecimal("125.00"))));

    mvc.perform(get("/api/v1/finance/payments/44/allocations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].receivableId").value(401))
        .andExpect(jsonPath("$[0].chargeTypeId").value(2))
        .andExpect(jsonPath("$[0].allocatedAmount").value(125.00));
  }

  @Test
  void customerSearchMapsEachSupportedFieldToItsOwnDatabasePredicate() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

    controller.searchLedgerMembers("MBR_NO", "A");
    controller.searchLedgerMembers("REGISTRATION_NO", "A");
    controller.searchLedgerMembers("FORM_NO", "A");
    controller.searchLedgerMembers("NAME", "A");
    controller.searchLedgerMembers("CNIC_NO", "A");

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, times(5)).queryForList(sql.capture(), any(Object[].class));
    assertThat(sql.getAllValues()).anySatisfy(query -> assertThat(query).contains("m.mbr_no LIKE ?"));
    assertThat(sql.getAllValues()).anySatisfy(query -> assertThat(query).contains("rx.reg_no LIKE ?"));
    assertThat(sql.getAllValues()).anySatisfy(query -> assertThat(query).contains("rx.form_no LIKE ?"));
    assertThat(sql.getAllValues()).anySatisfy(query -> assertThat(query).contains("m.name LIKE ?"));
    assertThat(sql.getAllValues()).anySatisfy(query -> assertThat(query).contains("m.cnic_no LIKE ?"));
  }

  @Test
  void customerSearchRejectsUnsupportedFieldsBeforeQueryingDatabase() {
    assertThatThrownBy(() -> controller.searchLedgerMembers("anything", "A"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Unsupported customer search field");
    Mockito.verifyNoInteractions(jdbc);
  }
}
