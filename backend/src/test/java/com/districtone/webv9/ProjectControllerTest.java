package com.districtone.webv9;

import com.districtone.webv9.controller.ProjectController;
import com.districtone.webv9.service.PlotInventoryService;
import com.districtone.webv9.security.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectControllerTest {
  private JdbcTemplate jdbc;
  private PlotInventoryService plotInventory;
  private ProjectAccessService projectAccess;
  private ProjectController projects;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    plotInventory = mock(PlotInventoryService.class);
    projectAccess = mock(ProjectAccessService.class);
    when(projectAccess.canAccessProject(anyString(), anyLong())).thenReturn(true);
    when(projectAccess.isSuperAdmin("super")).thenReturn(true);
    projects = new ProjectController(jdbc, plotInventory, projectAccess);
    mvc = MockMvcBuilders.standaloneSetup(projects).build();
  }

  @Test
  void projectListIncludesProjectBlockAndPlotCounts() {
    when(jdbc.queryForList(contains("activeBlockCount"))).thenReturn(List.of(
        Map.of("id", 7L, "projectCode", "PR-00001", "blockCount", 2L, "activeBlockCount", 1L, "plotCount", 8L)));

    List<Map<String, Object>> result = projects.projects(superAuth());

    assertThat(result).hasSize(1);
    assertThat(result.get(0))
        .containsEntry("projectCode", "PR-00001")
        .containsEntry("blockCount", 2L)
        .containsEntry("activeBlockCount", 1L)
        .containsEntry("plotCount", 8L);
  }

  @Test
  void createsProjectWithGeneratedCodeAndSchemaFields() {
    when(jdbc.queryForObject(contains("FROM webv9_projects WHERE project_code"), eq(Integer.class))).thenReturn(9);
    when(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(19L);
    when(jdbc.queryForMap(contains("WHERE p.id = ?"), eq(19L))).thenReturn(projectRow(19L));

    Map<String, Object> result = projects.create(new ProjectController.ProjectRequest(
        " District One ", " Description ", " Location ", " City ", "active",
        LocalDate.of(2026, 1, 1), " Notes ", true), superAuth());

    assertThat(result).containsEntry("id", 19L);
    ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(contains("INSERT INTO webv9_projects"), values.capture());
    assertThat(values.getValue()).containsExactly(
        "PR-00009", "District One", "Description", "Location", "City", "ACTIVE",
        LocalDate.of(2026, 1, 1), "Notes", true);
    ArgumentCaptor<String> codeQuery = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForObject(codeQuery.capture(), eq(Integer.class));
    assertThat(codeQuery.getValue()).contains("SUBSTRING(project_code, 4)");
  }

  @Test
  void duplicateProjectNameOrCodeReturnsConflict() {
    when(jdbc.queryForObject(contains("FROM webv9_projects WHERE project_code"), eq(Integer.class))).thenReturn(1);
    when(jdbc.update(contains("INSERT INTO webv9_projects"), any(Object[].class)))
        .thenThrow(new DuplicateKeyException("duplicate"));

    assertThatThrownBy(() -> projects.create(new ProjectController.ProjectRequest(
        "Duplicate", null, null, null, "ACTIVE", null, null, true), superAuth()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("project name or generated project code");
  }

  @Test
  void scopedAdministratorCannotChangeProjectLocationOrCity() {
    Authentication scoped = new UsernamePasswordAuthenticationToken("operator", "", List.of());
    when(jdbc.queryForMap("SELECT location, city FROM webv9_projects WHERE id = ?", 7L))
        .thenReturn(Map.of("location", "North", "city", "Lahore"));

    assertThatThrownBy(() -> projects.update(7L, new ProjectController.ProjectRequest(
        "District One", null, "South", "Lahore", "ACTIVE", null, null, true), scoped))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Only Super Admin may change a project's location or city scope");

    verify(jdbc, never()).update(contains("UPDATE webv9_projects"), any(Object[].class));
  }

  @Test
  void projectsApiValidatesRequiredProjectName() throws Exception {
    mvc.perform(post("/api/v1/projects")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"projectName":" ","status":"ACTIVE","active":true}
                """))
        .andExpect(status().isBadRequest());

    verify(jdbc, never()).update(contains("INSERT INTO webv9_projects"), any(Object[].class));
  }

  @Test
  void unassignedBlockAssignmentLocksAndLinksOnlyNullProject() {
    when(jdbc.queryForObject("SELECT COUNT(*) FROM webv9_projects WHERE id = ?", Integer.class, 7L)).thenReturn(1);
    when(jdbc.queryForObject("SELECT block_id FROM blocks WHERE block_id = ? FOR UPDATE", Long.class, 11L)).thenReturn(11L);
    when(jdbc.queryForMap("SELECT block_id AS id, project_id AS projectId FROM blocks WHERE block_id = ?", 11L))
        .thenReturn(unassignedBlockRow(11L));
    when(jdbc.queryForMap(contains("SELECT b.block_id AS id"), eq(11L))).thenReturn(blockRow(11L, 7L));

    Map<String, Object> result = projects.assignUnassignedBlock(7L, 11L, superAuth());

    assertThat(result).containsEntry("projectId", 7L);
    verify(jdbc).update("UPDATE blocks SET project_id = ? WHERE block_id = ? AND project_id IS NULL", 7L, 11L);
  }

  @Test
  void alreadyAssignedBlockCannotBeReassigned() {
    when(jdbc.queryForObject("SELECT COUNT(*) FROM webv9_projects WHERE id = ?", Integer.class, 7L)).thenReturn(1);
    when(jdbc.queryForObject("SELECT block_id FROM blocks WHERE block_id = ? FOR UPDATE", Long.class, 11L)).thenReturn(11L);
    when(jdbc.queryForMap("SELECT block_id AS id, project_id AS projectId FROM blocks WHERE block_id = ?", 11L))
        .thenReturn(Map.of("id", 11L, "projectId", 4L));

    assertThatThrownBy(() -> projects.assignUnassignedBlock(7L, 11L, superAuth()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Only an unassigned block");

    verify(jdbc, never()).update(contains("SET project_id"), any(Object[].class));
  }

  @Test
  void projectInventoryUsesExistingInventoryServiceAndProjectFilter() throws Exception {
    when(jdbc.queryForObject("SELECT COUNT(*) FROM webv9_projects WHERE id = ?", Integer.class, 7L)).thenReturn(1);
    when(plotInventory.list("DOL", "BOOKED", "COMMITTED", 7L)).thenReturn(List.of(
        Map.of("plotNo", "DOL-00001", "availability", "COMMITTED")));

    mvc.perform(get("/api/v1/projects/7/inventory")
            .param("q", "DOL").param("status", "BOOKED").param("availability", "COMMITTED").principal(superAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].plotNo").value("DOL-00001"));

    verify(plotInventory).list("DOL", "BOOKED", "COMMITTED", 7L);
  }

  private Map<String, Object> projectRow(Long id) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("projectCode", "PR-00009");
    result.put("projectName", "District One");
    return result;
  }

  private Authentication superAuth() {
    return new UsernamePasswordAuthenticationToken("super", "", List.of());
  }

  private Map<String, Object> blockRow(Long id, Long projectId) {
    return Map.of("id", id, "blockCode", "BL-00001", "blockName", "A", "projectId", projectId,
        "active", true, "plotCount", 3L);
  }

  private Map<String, Object> unassignedBlockRow(Long id) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("projectId", null);
    return result;
  }
}
