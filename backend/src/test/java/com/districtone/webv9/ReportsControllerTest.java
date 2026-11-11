package com.districtone.webv9;

import com.districtone.webv9.controller.ReportsController;
import com.districtone.webv9.repo.SalesWorkflowRepository;
import com.districtone.webv9.security.ProjectAccessService;
import com.districtone.webv9.service.PlotInventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import java.sql.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReportsControllerTest {
  @Test
  void agingBindsSelectDatesBeforeProjectScopeAndDueDate() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ProjectAccessService projectAccess = mock(ProjectAccessService.class);
    when(projectAccess.accessibleProjectIds("report.user")).thenReturn(List.of(17L, 23L));
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    ReportsController controller = new ReportsController(jdbc, projectAccess,
        mock(PlotInventoryService.class), mock(SalesWorkflowRepository.class));

    controller.receivablesAging("2026-10-31", null, null, null, null, null, null,
        authentication("report.user"));

    var sql = org.mockito.ArgumentCaptor.forClass(String.class);
    var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForList(sql.capture(), args.capture());
    assertThat(sql.getValue()).contains("DATEDIFF(?, vr.due_date)", "WHERE pr.id IN (");
    assertThat(args.getValue()).containsExactly(
        Date.valueOf("2026-10-31"), Date.valueOf("2026-10-31"),
        Date.valueOf("2026-10-31"), Date.valueOf("2026-10-31"),
        17L, 23L, Date.valueOf("2026-10-31"));
  }

  @Test
  void reportWithNoAssignedProjectsReturnsNoRowsWithoutQueryingData() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ProjectAccessService projectAccess = mock(ProjectAccessService.class);
    when(projectAccess.accessibleProjectIds("report.user")).thenReturn(List.of());
    ReportsController controller = new ReportsController(jdbc, projectAccess,
        mock(PlotInventoryService.class), mock(SalesWorkflowRepository.class));

    Map<String, Object> response = controller.receivablesAging(null, null, null,
        null, null, null, null, authentication("report.user"));

    assertThat(response.get("rows")).isEqualTo(List.of());
    verifyNoInteractions(jdbc);
  }

  @Test
  void plotInventoryQueriesOnlyAssignedProjects() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ProjectAccessService projectAccess = mock(ProjectAccessService.class);
    PlotInventoryService inventory = mock(PlotInventoryService.class);
    when(projectAccess.accessibleProjectIds("report.user")).thenReturn(List.of(17L, 23L));
    when(inventory.list(null, null, null, 17L)).thenReturn(List.of(Map.of("plotId", 1L)));
    when(inventory.list(null, null, null, 23L)).thenReturn(List.of(Map.of("plotId", 2L)));
    ReportsController controller = new ReportsController(jdbc, projectAccess, inventory,
        mock(SalesWorkflowRepository.class));

    Map<String, Object> response = controller.plotInventory(null, null, null, null,
        authentication("report.user"));

    assertThat(response.get("rows")).isEqualTo(List.of(Map.of("plotId", 1L), Map.of("plotId", 2L)));
    verify(inventory).list(null, null, null, 17L);
    verify(inventory).list(null, null, null, 23L);
    verifyNoMoreInteractions(inventory);
  }

  @Test
  void requestedProjectOutsideAssignedScopeIsNotQueried() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ProjectAccessService projectAccess = mock(ProjectAccessService.class);
    PlotInventoryService inventory = mock(PlotInventoryService.class);
    when(projectAccess.accessibleProjectIds("report.user")).thenReturn(List.of(17L));
    ReportsController controller = new ReportsController(jdbc, projectAccess, inventory,
        mock(SalesWorkflowRepository.class));

    Map<String, Object> response = controller.plotInventory(null, null, null, 99L,
        authentication("report.user"));

    assertThat(response.get("rows")).isEqualTo(List.of());
    verifyNoInteractions(inventory, jdbc);
  }

  @Test
  void receiptRegisterRestrictsEveryAllocationToAssignedProjects() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ProjectAccessService projectAccess = mock(ProjectAccessService.class);
    when(projectAccess.accessibleProjectIds("report.user")).thenReturn(List.of(17L, 23L));
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    ReportsController controller = new ReportsController(jdbc, projectAccess,
        mock(PlotInventoryService.class), mock(SalesWorkflowRepository.class));

    controller.financeReceipts("2026-10-01", "2026-10-31", "POSTED",
        "BANK", "North", "Example", authentication("report.user"));

    var sql = org.mockito.ArgumentCaptor.forClass(String.class);
    var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForList(sql.capture(), args.capture());
    assertThat(sql.getValue()).contains("WHERE EXISTS", "AND NOT EXISTS", "block.project_id NOT IN");
    assertThat(args.getValue()).containsExactly(
        17L, 23L, 17L, 23L,
        Date.valueOf("2026-10-01"), Date.valueOf("2026-10-31"),
        "POSTED", "BANK", "%North%", "%Example%", "%Example%");
  }

  @Test
  void cancellationSummaryUsesApprovalAndRefundStatuses() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ProjectAccessService projectAccess = mock(ProjectAccessService.class);
    SalesWorkflowRepository workflow = mock(SalesWorkflowRepository.class);
    when(projectAccess.accessibleProjectIds("report.user")).thenReturn(List.of(17L));
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
        Map.of("cancellationId", 42L, "approvalStatus", "APPROVED", "refundStatus", "PENDING")));
    when(workflow.cancellationEvents(42L)).thenReturn(List.of());
    when(workflow.cancellationLines(42L)).thenReturn(List.of());
    ReportsController controller = new ReportsController(jdbc, projectAccess,
        mock(PlotInventoryService.class), workflow);

    Map<String, Object> response = controller.cancellations(null, null, null, null,
        null, null, null, authentication("report.user"));

    Map<?, ?> summary = (Map<?, ?>) response.get("summary");
    assertThat(summary.get("countByStatus")).isEqualTo(Map.of("APPROVED", 1L));
    assertThat(summary.get("countByRefundStatus")).isEqualTo(Map.of("PENDING", 1L));
  }

  private Authentication authentication(String username) {
    Authentication authentication = mock(Authentication.class);
    when(authentication.getName()).thenReturn(username);
    return authentication;
  }
}
