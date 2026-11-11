package com.districtone.webv9.security;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Database-backed project/location boundary used by business controllers. */
@Service
public class ProjectAccessService {
  private final JdbcTemplate jdbc;
  private final UserPermissionService permissions;

  public ProjectAccessService(JdbcTemplate jdbc, UserPermissionService permissions) {
    this.jdbc = jdbc;
    this.permissions = permissions;
  }

  public boolean isSuperAdmin(String username) {
    return permissions.isSuperAdmin(username);
  }

  public boolean canAccessProject(String username, long projectId) {
    try {
      var project = jdbc.queryForMap("""
          SELECT location, city FROM webv9_projects
          WHERE id = ? AND is_active = 1
          """, projectId);
      return permissions.hasProjectScope(username, projectId,
          (String) project.get("location"), (String) project.get("city"));
    } catch (EmptyResultDataAccessException ex) {
      return false;
    }
  }

  public java.util.List<Long> accessibleProjectIds(String username) {
    if (!permissions.isAccountActive(username)) return java.util.List.of();
    if (permissions.isSuperAdmin(username)) {
      return jdbc.queryForList(
          "SELECT id FROM webv9_projects WHERE is_active = 1 ORDER BY id", Long.class);
    }
    return jdbc.query("""
        SELECT DISTINCT project.id
        FROM users u
        JOIN webv9_positions position ON position.id = u.position_id AND position.is_active = 1
        JOIN webv9_user_project_scopes scope ON scope.user_id = u.id
        JOIN webv9_projects project ON project.id = scope.project_id AND project.is_active = 1
        WHERE u.username = ? AND u.is_active = 1
          AND (u.access_starts_at IS NULL OR u.access_starts_at <= CURRENT_TIMESTAMP)
          AND (u.access_ends_at IS NULL OR u.access_ends_at >= CURRENT_TIMESTAMP)
          AND (position.department IS NULL OR TRIM(position.department) = ''
               OR UPPER(TRIM(position.department)) = UPPER(TRIM(u.department)))
          AND (scope.location IS NULL OR TRIM(scope.location) = '' OR scope.location = project.location)
          AND (scope.city IS NULL OR TRIM(scope.city) = '' OR scope.city = project.city)
        ORDER BY project.id
        """, (rs, row) -> rs.getLong(1), username.trim());
  }

  public boolean canManageScope(String actor, long projectId, String location, String city) {
    try {
      var project = jdbc.queryForMap("""
          SELECT location, city FROM webv9_projects
          WHERE id = ? AND is_active = 1
          """, projectId);
      String projectLocation = (String) project.get("location");
      String projectCity = (String) project.get("city");
      if (!java.util.Objects.equals(projectLocation, location)
          || !java.util.Objects.equals(projectCity, city)) return false;
      return permissions.isSuperAdmin(actor)
          || (permissions.isGranted(actor, "Users & Roles", "EDIT")
              || permissions.isGranted(actor, "Users & Roles", "ADD"))
          && permissions.hasProjectScope(actor, projectId, location, city);
    } catch (EmptyResultDataAccessException ex) {
      return false;
    }
  }

  public boolean canManageUser(String actor, long userId) {
    if (permissions.isSuperAdmin(actor)) return true;
    if (!permissions.isAccountActive(actor) || !permissions.hasAnyProjectScope(actor)) return false;

    try {
      var target = jdbc.queryForMap("""
          SELECT user.department AS department, user.role AS role,
                 user.is_super_admin AS isSuperAdmin,
                 position.position_code AS positionCode
          FROM users user
          LEFT JOIN webv9_positions position ON position.id = user.position_id
          WHERE user.id = ?
          """, userId);
      if (Boolean.TRUE.equals(target.get("isSuperAdmin"))
          || "SUPER_ADMIN".equalsIgnoreCase(String.valueOf(target.get("role")))
          || "ADMIN".equalsIgnoreCase(String.valueOf(target.get("positionCode")))) return false;

      var actorInfo = jdbc.queryForMap("""
          SELECT user.department AS department, position.position_code AS positionCode
          FROM users user
          JOIN webv9_positions position ON position.id = user.position_id
          WHERE user.username = ? AND user.is_active = 1 AND position.is_active = 1
          """, actor);
      if ("DEPARTMENT_MANAGER".equalsIgnoreCase(String.valueOf(actorInfo.get("positionCode")))
          && !sameDepartment(actorInfo.get("department"), target.get("department"))) return false;

      Integer targetScopes = jdbc.queryForObject(
          "SELECT COUNT(*) FROM webv9_user_project_scopes WHERE user_id = ?", Integer.class, userId);
      if (targetScopes == null || targetScopes == 0) {
        return sameDepartment(actorInfo.get("department"), target.get("department"));
      }
      var scopes = jdbc.query("""
          SELECT project_id, location, city FROM webv9_user_project_scopes
          WHERE user_id = ?
          """, (rs, row) -> new Scope(rs.getLong("project_id"),
          rs.getString("location"), rs.getString("city")), userId);
      return !scopes.isEmpty() && scopes.stream().allMatch(scope ->
          permissions.hasProjectScope(actor, scope.projectId(), scope.location(), scope.city()));
    } catch (EmptyResultDataAccessException ex) {
      return false;
    }
  }

  private boolean sameDepartment(Object left, Object right) {
    if (left == null || right == null) return left == right;
    return String.valueOf(left).trim().equalsIgnoreCase(String.valueOf(right).trim());
  }

  private record Scope(Long projectId, String location, String city) {}

  public long projectForPlot(long plotId) {
    try {
      Long projectId = jdbc.queryForObject("""
          SELECT b.project_id FROM plots pl JOIN blocks b ON b.block_id = pl.block_id
          WHERE pl.plot_id = ?
          """, Long.class, plotId);
      if (projectId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unassigned plot is outside project scope");
      return projectId;
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plot not found");
    }
  }

  public long projectForBlock(long blockId) {
    try {
      Long projectId = jdbc.queryForObject("SELECT project_id FROM blocks WHERE block_id=?", Long.class, blockId);
      if (projectId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unassigned block is outside project scope");
      return projectId;
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Block not found");
    }
  }

  public long projectForBooking(long bookingId) {
    try {
      Long projectId = jdbc.queryForObject("""
          SELECT b.project_id FROM sales_bookings sb JOIN plots pl ON pl.plot_id = sb.plot_id
          JOIN blocks b ON b.block_id = pl.block_id WHERE sb.sale_booking_id = ?
          """, Long.class, bookingId);
      if (projectId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Booking is not linked to a project scope");
      return projectId;
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sales booking not found");
    }
  }

  public void requireProject(String username, long projectId) {
    if (!canAccessProject(username, projectId))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The account is not assigned to this project/location");
  }

  public void requirePlot(String username, long plotId) { requireProject(username, projectForPlot(plotId)); }
  public void requireBooking(String username, long bookingId) { requireProject(username, projectForBooking(bookingId)); }
  public boolean canAccessBooking(String username, long bookingId) {
    return canAccessProject(username, projectForBooking(bookingId));
  }

  public boolean canAccessPayment(String username, long paymentId) {
    if (isSuperAdmin(username)) return true;
    var projectScopes = jdbc.query("""
        SELECT DISTINCT block.project_id AS projectId, project.location, project.city
        FROM webv9_payment_allocations allocation
        LEFT JOIN sales_bookings booking ON booking.sale_booking_id = allocation.booking_id
        LEFT JOIN plots plot ON plot.plot_id = booking.plot_id
        LEFT JOIN blocks block ON block.block_id = plot.block_id
        LEFT JOIN webv9_projects project ON project.id = block.project_id AND project.is_active = 1
        WHERE allocation.payment_id = ?
        """, (rs, row) -> new Scope(rs.getObject("projectId", Long.class),
        rs.getString("location"), rs.getString("city")), paymentId);
    return !projectScopes.isEmpty() && projectScopes.stream().allMatch(scope -> scope.projectId() != null
        &&
        permissions.hasProjectScope(username, scope.projectId(), scope.location(), scope.city()));
  }

  public boolean canAccessMember(String username, long memberId) {
    if (isSuperAdmin(username)) return true;
    var projectScopes = jdbc.query("""
        SELECT DISTINCT block.project_id AS projectId, project.location, project.city
        FROM registrations registration
        LEFT JOIN sales_bookings booking ON booking.registration_id = registration.registration_id
        LEFT JOIN plots plot ON plot.plot_id = booking.plot_id
        LEFT JOIN blocks block ON block.block_id = plot.block_id
        LEFT JOIN webv9_projects project ON project.id = block.project_id AND project.is_active = 1
        WHERE registration.member_id = ?
        """, (rs, row) -> new Scope(rs.getObject("projectId", Long.class),
        rs.getString("location"), rs.getString("city")), memberId);
    return !projectScopes.isEmpty() && projectScopes.stream().allMatch(scope -> scope.projectId() != null
        &&
        permissions.hasProjectScope(username, scope.projectId(), scope.location(), scope.city()));
  }

  public void requirePayment(String username, long paymentId) {
    if (!canAccessPayment(username, paymentId))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The receipt is outside the account's project/location scope");
  }
}
