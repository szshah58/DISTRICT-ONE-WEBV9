package com.districtone.webv9;

import com.districtone.webv9.controller.SalesController;
import com.districtone.webv9.repo.SalesWorkflowRepository;
import com.districtone.webv9.service.SalesWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SalesMergeCancellationTest {
  private JdbcTemplate jdbc;
  private SalesWorkflowRepository repository;
  private SalesWorkflowService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    repository = mock(SalesWorkflowRepository.class);
    service = new SalesWorkflowService(jdbc, repository, new ObjectMapper());
  }

  @Test
  void mergeSubmissionSnapshotsBothDolsAndDoesNotChangeBookingOrInventory() {
    stubCommonBookingReads();
    when(jdbc.queryForObject(contains("SELECT id FROM users"), eq(Long.class), eq("reviewer"))).thenReturn(9L);
    when(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(501L);

    Map<String, Object> result = service.submitMerge(101L, 102L, LocalDate.of(2026, 1, 15),
        "Review transfer", "reviewer");

    assertThat(result).containsEntry("mergeId", 501L).containsEntry("status", "PENDING");
    verify(jdbc).update(contains("INSERT INTO webv9_plot_merges"), any(Object[].class));
    verify(jdbc).update(contains("INSERT INTO webv9_sales_request_events"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE sales_bookings"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE plots"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE webv9_payment"));
  }

  @Test
  void selfMergeIsRejectedBeforeWriting() {
    assertThatThrownBy(() -> service.submitMerge(101L, 101L, LocalDate.now(), null, "reviewer"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("cannot be identical");
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void mergeApprovalTransfersPostedApplicationAndPreservesReceiptHistory() {
    stubCommonBookingReads();
    when(repository.lockMerge(501L)).thenReturn(mergeRequest());
    when(jdbc.queryForObject(contains("SELECT id FROM users"), eq(Long.class), eq("reviewer"))).thenReturn(9L);
    when(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(601L);
    when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any(Object[].class))).thenReturn(0);
    when(jdbc.queryForMap(contains("FROM webv9_receivables WHERE id = ? FOR UPDATE"), any(Object[].class)))
        .thenAnswer(call -> receivable(((Number) firstArgument(call.getArgument(1))).longValue()));
    when(jdbc.queryForList(contains("FROM webv9_payment_receivable_applications pra"), any(Object[].class)))
        .thenReturn(List.of(postedApplication()));
    when(jdbc.queryForList(contains("FROM webv9_receivables vr LEFT JOIN webv9_charge_types"), any(Object[].class)))
        .thenReturn(List.of());
    when(jdbc.queryForList(contains("FROM webv9_payment_allocations a JOIN payments_receipts p"), any(Object[].class)))
        .thenReturn(List.of());
    when(jdbc.queryForList(contains("FROM webv9_finance_adjustments fa"), any(Object[].class)))
        .thenReturn(List.of());
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    Map<String, Object> result = service.approveMerge(501L,
        List.of(new SalesWorkflowService.MergeTransferMapping(901L, 202L)),
        "Approved after financial review", "reviewer");

    assertThat(result).containsEntry("status", "APPROVED").containsEntry("transferredAmount", new BigDecimal("100.00"));
    verify(jdbc).update(contains("INSERT INTO webv9_sales_merge_transfer_lines"),
        eq(601L), eq(41L), eq(51L), eq(901L), eq(201L), eq(202L), eq(new BigDecimal("100.00")));
    verify(jdbc, never()).update(startsWith("UPDATE payments_receipts"));
    verify(jdbc, never()).update(startsWith("UPDATE webv9_payment_allocations"));
    verify(jdbc, never()).update(startsWith("UPDATE webv9_payment_receivable_applications"));
    verify(jdbc).update(contains("UPDATE plots SET status = 'AVAILABLE'"), eq(101L));
    verify(jdbc).update(contains("SET sale_status = 'MERGED'"), eq(201L));
  }

  @Test
  void mergeApprovalIsBlockedByUnpostedFinanceReservation() {
    stubCommonBookingReads();
    when(repository.lockMerge(501L)).thenReturn(mergeRequest());
    when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any(Object[].class))).thenReturn(0);
    when(jdbc.queryForObject(contains("FROM webv9_payment_allocations a"), eq(Integer.class), any(Object[].class)))
        .thenReturn(1);

    assertThatThrownBy(() -> service.approveMerge(501L, List.of(), "Approved", "reviewer"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Unposted Finance reservations");
    verify(jdbc, never()).update(contains("INSERT INTO webv9_sales_merge_transfers"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE plots"));
  }

  @Test
  void mergeApprovalIsBlockedWhenReceivableDoesNotReconcileToPostedAllocations() {
    stubCommonBookingReads();
    when(repository.lockMerge(501L)).thenReturn(mergeRequest());
    when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any(Object[].class)))
        .thenAnswer(call -> ((String) call.getArgument(0)).contains("vr.paid_amount") ? 1 : 0);

    assertThatThrownBy(() -> service.approveMerge(501L, List.of(), "Approved", "reviewer"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("do not reconcile");
    verify(jdbc, never()).update(contains("INSERT INTO webv9_sales_merge_transfers"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE plots"));
  }

  @Test
  void mergeCannotDropEarlierSalesTransferInflows() {
    stubCommonBookingReads();
    when(repository.lockMerge(501L)).thenReturn(mergeRequest());
    when(jdbc.queryForObject(contains("WHERE target_booking_id = ?"), eq(Integer.class), any(Object[].class)))
        .thenReturn(1);

    assertThatThrownBy(() -> service.approveMerge(501L, List.of(), "Approved", "reviewer"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("prior Sales merge receipts");
    verify(jdbc, never()).update(contains("INSERT INTO webv9_sales_merge_transfers"), any(Object[].class));
  }

  @Test
  void mergeRejectionRecordsDecisionWithoutChangingInventoryOrFinancialRecords() {
    when(repository.lockMerge(501L)).thenReturn(mergeRequest());
    when(jdbc.queryForObject(contains("SELECT id FROM users"), eq(Long.class), eq("reviewer"))).thenReturn(9L);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    Map<String, Object> result = service.rejectMerge(501L, "Insufficient documents", "reviewer");

    assertThat(result).containsEntry("status", "REJECTED");
    verify(jdbc).update(contains("UPDATE webv9_plot_merges"), any(Object[].class));
    verify(jdbc).update(contains("INSERT INTO webv9_sales_request_events"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE sales_bookings"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE plots"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE webv9_receivables"), any(Object[].class));
  }

  @Test
  void cancellationSubmissionRemainsPendingAndDoesNotChangeBookingOrPlot() {
    stubCommonBookingReads();
    when(jdbc.queryForObject(contains("SELECT id FROM users"), eq(Long.class), eq("reviewer"))).thenReturn(9L);
    when(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(701L);

    Map<String, Object> result = service.submitCancellation(201L, LocalDate.of(2026, 1, 15),
        "Customer request", "Signed form received", "reviewer");

    assertThat(result).containsEntry("cancellationId", 701L).containsEntry("status", "PENDING");
    verify(jdbc).update(contains("INSERT INTO webv9_cancellations"), any(Object[].class));
    verify(jdbc).update(contains("INSERT INTO webv9_sales_request_events"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE sales_bookings"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE plots"), any(Object[].class));
  }

  @Test
  void duplicatePendingMergeForEitherPlotIsRejected() {
    stubCommonBookingReads();
    when(jdbc.queryForObject(contains("source_plot_id = ? OR target_plot_id = ?"),
        eq(Integer.class), any(Object[].class))).thenReturn(1);

    assertThatThrownBy(() -> service.submitMerge(101L, 102L, LocalDate.now(), null, "reviewer"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("pending or finalized merge request");
    verify(jdbc, never()).update(contains("INSERT INTO webv9_plot_merges"), any(Object[].class));
  }

  @Test
  void cancellationApprovalCalculatesRefundFromPostedApplicationsAndApprovedAdjustments() {
    stubCommonBookingReads();
    when(repository.lockCancellation(701L)).thenReturn(cancellationRequest());
    when(jdbc.queryForObject(contains("SELECT id FROM users"), eq(Long.class), eq("reviewer"))).thenReturn(9L);
    when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any(Object[].class))).thenReturn(0);
    when(jdbc.queryForObject(contains("SELECT COALESCE(("), eq(BigDecimal.class), any(Object[].class)))
        .thenReturn(new BigDecimal("100.00"));
    when(jdbc.queryForList(contains("FROM webv9_payment_receivable_applications pra"), any(Object[].class)))
        .thenReturn(List.of(postedApplication()));
    when(jdbc.queryForList(contains("FROM webv9_finance_adjustments fa"), any(Object[].class)))
        .thenReturn(List.of(Map.of("adjustmentId", 801L, "receivableId", 201L,
            "amount", new BigDecimal("20.00"), "description", "Approved society charge")));
    when(jdbc.queryForList(contains("FROM webv9_receivables vr LEFT JOIN webv9_charge_types"), any(Object[].class)))
        .thenReturn(List.of());
    when(jdbc.queryForList(contains("FROM webv9_payment_allocations a JOIN payments_receipts p"), any(Object[].class)))
        .thenReturn(List.of());
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    Map<String, Object> result = service.approveCancellation(701L, List.of(801L),
        "Approved after settlement review", "reviewer");

    assertThat(result).containsEntry("status", "APPROVED")
        .containsEntry("postedAmount", new BigDecimal("100.00"))
        .containsEntry("deductionAmount", new BigDecimal("20.00"))
        .containsEntry("refundAmount", new BigDecimal("80.00"))
        .containsEntry("refundStatus", "PENDING_DISBURSEMENT");
    verify(jdbc).update(contains("INSERT INTO webv9_sales_cancellation_settlement_lines"),
        eq(701L), eq(801L), eq(201L), eq(new BigDecimal("20.00")), eq("Approved society charge"), eq(9L));
    verify(jdbc).update(contains("INSERT INTO webv9_sales_refund_disbursements"),
        eq(701L), eq(11L), eq(new BigDecimal("100.00")), eq(new BigDecimal("20.00")),
        eq(new BigDecimal("80.00")), eq("PENDING_DISBURSEMENT"), eq(9L), eq("Approved after settlement review"));
    verify(jdbc, never()).update(startsWith("UPDATE payments_receipts"));
    verify(jdbc, never()).update(startsWith("UPDATE webv9_payment_allocations"));
    verify(jdbc).update(contains("UPDATE plots SET status = 'AVAILABLE'"), eq(101L));
    verify(jdbc).update(contains("SET sale_status = 'CANCELLED'"), eq(201L));
  }

  @Test
  void cancellationRejectRequiresPendingStateAndDoesNotChangeInventory() {
    when(repository.lockCancellation(701L)).thenReturn(Map.of("status", "APPROVED"));
    assertThatThrownBy(() -> service.rejectCancellation(701L, "Reject", "reviewer"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Only a pending request");
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void cancellationRejectionRecordsDecisionAndLeavesBookingPlotAndFinancialStateAlone() {
    when(repository.lockCancellation(701L)).thenReturn(cancellationRequest());
    when(jdbc.queryForObject(contains("SELECT id FROM users"), eq(Long.class), eq("reviewer"))).thenReturn(9L);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    Map<String, Object> result = service.rejectCancellation(701L, "Request not authorized", "reviewer");

    assertThat(result).containsEntry("status", "REJECTED");
    verify(jdbc).update(contains("UPDATE webv9_cancellations"), any(Object[].class));
    verify(jdbc).update(contains("INSERT INTO webv9_sales_request_events"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE sales_bookings"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE plots"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE webv9_receivables"), any(Object[].class));
  }

  @Test
  void refundCompletionRecordsPayoutReferenceWithoutCreatingReceipt() {
    when(repository.lockCancellation(701L)).thenReturn(Map.of(
        "status", "APPROVED", "request_version", 2));
    when(jdbc.queryForObject(contains("SELECT id FROM users"), eq(Long.class), eq("reviewer"))).thenReturn(9L);
    when(jdbc.queryForList(contains("FROM webv9_sales_refund_disbursements"), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 91L, "status", "PENDING_DISBURSEMENT")));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    Map<String, Object> result = service.completeRefund(701L, "BANK-REF-901", "Paid to member", "reviewer");

    assertThat(result).containsEntry("status", "DISBURSED").containsEntry("payoutReference", "BANK-REF-901");
    verify(jdbc).update(contains("UPDATE webv9_sales_refund_disbursements"), any(Object[].class));
    verify(jdbc).update(contains("INSERT INTO webv9_sales_request_events"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("INSERT INTO payments_receipts"), any(Object[].class));
  }

  @Test
  void decisionOperationsAreTransactionalAndDuplicateApprovalIsRejected() throws Exception {
    assertThat(SalesWorkflowService.class.getMethod("approveMerge", Long.class, List.class, String.class, String.class)
        .isAnnotationPresent(Transactional.class)).isTrue();
    assertThat(SalesWorkflowService.class.getMethod("approveCancellation", Long.class, List.class, String.class, String.class)
        .isAnnotationPresent(Transactional.class)).isTrue();

    when(repository.lockMerge(501L)).thenReturn(Map.of("status", "APPROVED"));
    assertThatThrownBy(() -> service.rejectMerge(501L, "Duplicate decision", "reviewer"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Only a pending request");
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void concurrentMergeDecisionLosingOptimisticUpdateDoesNotReleasePlot() {
    stubCommonBookingReads();
    when(repository.lockMerge(501L)).thenReturn(mergeRequest());
    when(jdbc.queryForObject(contains("SELECT id FROM users"), eq(Long.class), eq("reviewer"))).thenReturn(9L);
    when(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(601L);
    when(jdbc.update(contains("UPDATE webv9_plot_merges"), any(Object[].class))).thenReturn(0);

    assertThatThrownBy(() -> service.approveMerge(501L, List.of(), "Approved", "reviewer"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("changed during approval");
    verify(jdbc).update(contains("COALESCE(request_version, 1) = ?"), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE plots"), any(Object[].class));
    verify(jdbc, never()).update(contains("UPDATE sales_bookings SET sale_status = 'MERGED'"), any(Object[].class));
  }

  private void stubCommonBookingReads() {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(call -> {
      String sql = call.getArgument(0);
      int parameterCount = (int) sql.chars().filter(character -> character == '?').count();
      return java.util.stream.LongStream.range(1, parameterCount + 1).boxed().toList();
    });
    when(jdbc.queryForMap(contains("WHERE sb.sale_booking_id = ?"), any(Object[].class)))
        .thenAnswer(call -> booking(((Number) firstArgument(call.getArgument(1))).longValue()));
    when(jdbc.queryForList(contains("WHERE sb.plot_id = ? ORDER BY sb.sale_booking_id DESC LIMIT 1"),
        any(Object[].class))).thenAnswer(call -> {
      Long plotId = ((Number) firstArgument(call.getArgument(1))).longValue();
      return List.of(booking(plotId == 101L ? 201L : 202L));
    });
    when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any(Object[].class))).thenReturn(0);
    when(jdbc.queryForList(contains("FROM webv9_payment_receivable_applications pra"), any(Object[].class)))
        .thenReturn(List.of());
    when(jdbc.queryForList(contains("FROM webv9_receivables vr LEFT JOIN webv9_charge_types"), any(Object[].class)))
        .thenReturn(List.of());
    when(jdbc.queryForList(contains("FROM webv9_payment_allocations a JOIN payments_receipts p"), any(Object[].class)))
        .thenReturn(List.of());
    when(jdbc.queryForList(contains("FROM webv9_finance_adjustments fa"), any(Object[].class)))
        .thenReturn(List.of());
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
  }

  private Map<String, Object> booking(Long id) {
    long plotId = id == 201L ? 101L : 102L;
    return Map.of("bookingId", id, "status", "ALLOCATED", "registrationId", id + 1000,
        "memberId", 11L, "plotId", plotId, "plotNo", plotId == 101L ? "DOL-00101" : "DOL-00102",
        "bookingNo", "SB-" + id);
  }

  private Map<String, Object> mergeRequest() {
    return Map.of("id", 501L, "status", "PENDING", "request_version", 1,
        "source_plot_id", 101L, "target_plot_id", 102L, "source_booking_id", 201L,
        "target_booking_id", 202L, "source_plot_no", "DOL-00101", "target_plot_no", "DOL-00102");
  }

  private Map<String, Object> cancellationRequest() {
    return Map.of("id", 701L, "status", "PENDING", "request_version", 1,
        "booking_id", 201L, "source_plot_id", 101L, "source_plot_no", "DOL-00101");
  }

  private Map<String, Object> postedApplication() {
    return Map.of("applicationId", 901L, "allocationId", 51L, "paymentId", 41L,
        "receiptNo", "R-100", "bookingId", 201L, "plotId", 101L, "registrationId", 1201L,
        "chargeTypeId", 8L, "receivableId", 201L, "appliedAmount", new BigDecimal("100.00"));
  }

  private Map<String, Object> receivable(Long id) {
    long bookingId = id == 201L ? 201L : 202L;
    return Map.of("id", id, "bookingId", bookingId, "plotId", bookingId == 201L ? 101L : 102L,
        "memberId", 11L, "chargeTypeId", 8L, "originalAmount", new BigDecimal("1000.00"),
        "paidAmount", id == 201L ? new BigDecimal("100.00") : BigDecimal.ZERO,
        "surchargeAmount", BigDecimal.ZERO, "adjustmentAmount", BigDecimal.ZERO);
  }

  private static Object[] arguments(Object value) {
    return value instanceof Object[] values ? values : new Object[] {value};
  }

  private static Object firstArgument(Object value) {
    return arguments(value)[0];
  }
}
