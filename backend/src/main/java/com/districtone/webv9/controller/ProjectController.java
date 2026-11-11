package com.districtone.webv9.controller;

import com.districtone.webv9.service.PlotInventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;
import com.districtone.webv9.security.ProjectAccessService;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
  private static final String PROJECT_SELECT = """
      SELECT p.id, p.project_code AS projectCode, p.project_name AS projectName,
             p.project_description AS projectDescription, p.location, p.city, p.status,
             p.start_date AS startDate, p.remarks, p.is_active AS active,
             COALESCE(bc.blockCount, 0) AS blockCount,
             COALESCE(bc.activeBlockCount, 0) AS activeBlockCount,
             COALESCE(pc.plotCount, 0) AS plotCount
      FROM webv9_projects p
      LEFT JOIN (
        SELECT project_id, COUNT(*) AS blockCount,
               SUM(CASE WHEN is_active = 1 THEN 1 ELSE 0 END) AS activeBlockCount
        FROM blocks WHERE project_id IS NOT NULL GROUP BY project_id
      ) bc ON bc.project_id = p.id
      LEFT JOIN (
        SELECT b.project_id, COUNT(pl.plot_id) AS plotCount
        FROM blocks b LEFT JOIN plots pl ON pl.block_id = b.block_id
        WHERE b.project_id IS NOT NULL GROUP BY b.project_id
      ) pc ON pc.project_id = p.id
      """;

  private final JdbcTemplate jdbc;
  private final PlotInventoryService plotInventory;
  private final ProjectAccessService projectAccess;

  public ProjectController(JdbcTemplate jdbc, PlotInventoryService plotInventory, ProjectAccessService projectAccess) {
    this.jdbc = jdbc;
    this.plotInventory = plotInventory;
    this.projectAccess = projectAccess;
  }

  public record ProjectRequest(
      @NotBlank @Size(max = 160) String projectName,
      @Size(max = 1000) String projectDescription,
      @Size(max = 255) String location,
      @Size(max = 120) String city,
      @Size(max = 40) String status,
      LocalDate startDate,
      @Size(max = 1000) String remarks,
      Boolean active) {}

  public record BlockRequest(@NotBlank @Size(max = 100) String blockName, Boolean active) {}
  public record ActiveRequest(@NotNull Boolean active) {}

  @GetMapping
  public List<Map<String, Object>> projects(Authentication auth) {
    return jdbc.queryForList(PROJECT_SELECT + " ORDER BY p.project_name").stream()
        .filter(project -> projectAccess.canAccessProject(auth.getName(), ((Number) project.get("id")).longValue()))
        .toList();
  }

  @GetMapping("/{id}")
  public Map<String, Object> project(@PathVariable Long id, Authentication auth) {
    projectAccess.requireProject(auth.getName(), id);
    return one(PROJECT_SELECT + " WHERE p.id = ?", id);
  }

  @PostMapping
  @Transactional
  public Map<String, Object> create(@Valid @RequestBody ProjectRequest request, Authentication auth) {
    if (!projectAccess.isSuperAdmin(auth.getName()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Super Admin can create a project scope");
    String code = nextCode("webv9_projects", "project_code", "PR");
    try {
      jdbc.update("""
          INSERT INTO webv9_projects
            (project_code, project_name, project_description, location, city, status, start_date, remarks, is_active)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, code, request.projectName().trim(), blankToNull(request.projectDescription()),
          blankToNull(request.location()), blankToNull(request.city()), statusValue(request.status()),
          request.startDate(), blankToNull(request.remarks()), activeValue(request.active()));
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The project name or generated project code already exists. Refresh and retry.");
    }
    Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    return project(id, auth);
  }

  @PutMapping("/{id}")
  @Transactional
  public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody ProjectRequest request, Authentication auth) {
    projectAccess.requireProject(auth.getName(), id);
    if (!projectAccess.isSuperAdmin(auth.getName())) {
      Map<String, Object> current = jdbc.queryForMap(
          "SELECT location, city FROM webv9_projects WHERE id = ?", id);
      if (!Objects.equals(current.get("location"), blankToNull(request.location()))
          || !Objects.equals(current.get("city"), blankToNull(request.city()))) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Only Super Admin may change a project's location or city scope");
      }
    }
    try {
      int changed = jdbc.update("""
          UPDATE webv9_projects SET project_name = ?, project_description = ?, location = ?, city = ?,
                 status = ?, start_date = ?, remarks = ?, is_active = ?
          WHERE id = ?
          """, request.projectName().trim(), blankToNull(request.projectDescription()),
          blankToNull(request.location()), blankToNull(request.city()), statusValue(request.status()),
          request.startDate(), blankToNull(request.remarks()), activeValue(request.active()), id);
      if (changed == 0 && !projectExists(id)) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
      }
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "A project with this name already exists.");
    }
    return project(id, auth);
  }

  @GetMapping("/{projectId}/blocks")
  public List<Map<String, Object>> blocks(@PathVariable Long projectId, Authentication auth) {
    ensureProject(projectId, auth);
    return jdbc.queryForList("""
        SELECT b.block_id AS id, b.block_code AS blockCode, b.block_name AS blockName,
               b.project_id AS projectId, b.is_active AS active, COUNT(p.plot_id) AS plotCount
        FROM blocks b LEFT JOIN plots p ON p.block_id = b.block_id
        WHERE b.project_id = ?
        GROUP BY b.block_id, b.block_code, b.block_name, b.project_id, b.is_active
        ORDER BY b.block_name, b.block_code
        """, projectId);
  }

  @GetMapping("/blocks/unassigned")
  public List<Map<String, Object>> unassignedBlocks(Authentication auth) {
    if (!projectAccess.isSuperAdmin(auth.getName()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unassigned blocks are outside the account's project scope");
    return jdbc.queryForList("""
        SELECT b.block_id AS id, b.block_code AS blockCode, b.block_name AS blockName,
               b.is_active AS active, COUNT(p.plot_id) AS plotCount
        FROM blocks b LEFT JOIN plots p ON p.block_id = b.block_id
        WHERE b.project_id IS NULL
        GROUP BY b.block_id, b.block_code, b.block_name, b.is_active
        ORDER BY b.block_name, b.block_code
        """);
  }

  @PostMapping("/{projectId}/blocks")
  @Transactional
  public Map<String, Object> createBlock(@PathVariable Long projectId, @Valid @RequestBody BlockRequest request, Authentication auth) {
    ensureProject(projectId, auth);
    String code = nextCode("blocks", "block_code", "BL");
    try {
      jdbc.update("INSERT INTO blocks(block_code, block_name, project_id, is_active) VALUES (?, ?, ?, ?)",
          code, request.blockName().trim(), projectId, activeValue(request.active()));
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "The generated block code already exists. Refresh and retry.");
    }
    Long id = jdbc.queryForObject("SELECT block_id FROM blocks WHERE block_code = ?", Long.class, code);
    return block(id);
  }

  @PutMapping("/blocks/{id}")
  @Transactional
  public Map<String, Object> updateBlock(@PathVariable Long id, @Valid @RequestBody BlockRequest request, Authentication auth) {
    long projectId = blockProject(id);
    projectAccess.requireProject(auth.getName(), projectId);
    int changed = jdbc.update("UPDATE blocks SET block_name = ?, is_active = ? WHERE block_id = ?",
        request.blockName().trim(), activeValue(request.active()), id);
    if (changed == 0 && !blockExists(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Block not found");
    return block(id);
  }

  @PatchMapping("/blocks/{id}/status")
  @Transactional
  public Map<String, Object> blockStatus(@PathVariable Long id, @Valid @RequestBody ActiveRequest request, Authentication auth) {
    projectAccess.requireProject(auth.getName(), blockProject(id));
    int changed = jdbc.update("UPDATE blocks SET is_active = ? WHERE block_id = ?", request.active(), id);
    if (changed == 0 && !blockExists(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Block not found");
    return block(id);
  }

  @PatchMapping("/{projectId}/blocks/{blockId}/assign")
  @Transactional
  public Map<String, Object> assignUnassignedBlock(@PathVariable Long projectId, @PathVariable Long blockId, Authentication auth) {
    ensureProject(projectId, auth);
    Map<String, Object> existing = lockBlock(blockId);
    if (existing.get("projectId") != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an unassigned block can be linked to this project.");
    }
    jdbc.update("UPDATE blocks SET project_id = ? WHERE block_id = ? AND project_id IS NULL", projectId, blockId);
    return block(blockId);
  }

  @GetMapping("/{projectId}/inventory")
  public List<Map<String, Object>> projectInventory(@PathVariable Long projectId,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String availability,
                                                    Authentication auth) {
    ensureProject(projectId, auth);
    return plotInventory.list(q, status, availability, projectId);
  }

  private String nextCode(String table, String column, String prefix) {
    String sql = "SELECT COALESCE(MAX(CAST(SUBSTRING(" + column + ", " + (prefix.length() + 2)
        + ") AS UNSIGNED)), 0) + 1 FROM " + table + " WHERE " + column + " REGEXP '^"
        + prefix + "-[0-9]{5}$'";
    Integer next = jdbc.queryForObject(sql, Integer.class);
    if (next == null || next > 99999) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "The generated " + prefix + " code sequence is exhausted.");
    }
    return String.format(Locale.ROOT, "%s-%05d", prefix, next);
  }

  private Map<String, Object> one(String sql, Object... args) {
    try {
      return jdbc.queryForMap(sql, args);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found");
    }
  }

  private Map<String, Object> block(Long id) {
    return one("""
        SELECT b.block_id AS id, b.block_code AS blockCode, b.block_name AS blockName,
               b.project_id AS projectId, b.is_active AS active,
               (SELECT COUNT(*) FROM plots p WHERE p.block_id = b.block_id) AS plotCount
        FROM blocks b WHERE b.block_id = ?
        """, id);
  }

  private Map<String, Object> lockBlock(Long id) {
    try {
      jdbc.queryForObject("SELECT block_id FROM blocks WHERE block_id = ? FOR UPDATE", Long.class, id);
      return one("SELECT block_id AS id, project_id AS projectId FROM blocks WHERE block_id = ?", id);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Block not found");
    }
  }

  private void ensureProject(Long id, Authentication auth) {
    if (!projectExists(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
    projectAccess.requireProject(auth.getName(), id);
  }

  private long blockProject(Long id) {
    try {
      Long projectId = jdbc.queryForObject("SELECT project_id FROM blocks WHERE block_id = ?", Long.class, id);
      if (projectId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unassigned block is outside project scope");
      return projectId;
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Block not found");
    }
  }

  private boolean projectExists(Long id) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM webv9_projects WHERE id = ?", Integer.class, id);
    return count != null && count > 0;
  }

  private boolean blockExists(Long id) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM blocks WHERE block_id = ?", Integer.class, id);
    return count != null && count > 0;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String statusValue(String value) {
    String normalized = blankToNull(value);
    return normalized == null ? "ACTIVE" : normalized.toUpperCase(Locale.ROOT);
  }

  private boolean activeValue(Boolean value) {
    return value == null || value;
  }
}
