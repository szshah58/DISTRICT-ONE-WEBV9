package com.districtone.webv9.security;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Resolves active designation grants and user overrides without inheriting role grants. */
@Service
public class UserPermissionService {
  private static final List<String> SUPER_ADMIN_MODULES = List.of(
      "Dashboard", "Admin & Settings", "Users & Roles", "Plots & Sales",
      "Finance", "Customer Care", "Reports", "System Logs", "Members", "Documents");

  private final JdbcTemplate jdbc;

  public UserPermissionService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean isAccountActive(String username) {
    if (username == null || username.isBlank()) return false;
    Integer active = jdbc.queryForObject("""
        SELECT COUNT(*)
        FROM users u
        LEFT JOIN webv9_positions pos ON pos.id = u.position_id
        WHERE u.username = ? AND u.is_active = 1
          AND (u.access_starts_at IS NULL OR u.access_starts_at <= CURRENT_TIMESTAMP)
          AND (u.access_ends_at IS NULL OR u.access_ends_at >= CURRENT_TIMESTAMP)
          AND (
            u.is_super_admin = 1 OR UPPER(u.role) = 'SUPER_ADMIN'
            OR (pos.id IS NOT NULL AND pos.is_active = 1
                AND (pos.department IS NULL OR TRIM(pos.department) = ''
                     OR UPPER(TRIM(pos.department)) = UPPER(TRIM(u.department))))
          )
        """, Integer.class, username.trim());
    return active != null && active > 0;
  }

  public boolean isSuperAdmin(String username) {
    if (!isAccountActive(username)) return false;
    Integer admin = jdbc.queryForObject("""
        SELECT COUNT(*) FROM users
        WHERE username = ? AND is_active = 1
          AND (access_starts_at IS NULL OR access_starts_at <= CURRENT_TIMESTAMP)
          AND (access_ends_at IS NULL OR access_ends_at >= CURRENT_TIMESTAMP)
          AND (is_super_admin = 1 OR UPPER(role) = 'SUPER_ADMIN')
        """, Integer.class, username.trim());
    return admin != null && admin > 0;
  }

  public boolean hasAnyProjectScope(String username) {
    if (!isAccountActive(username) || isSuperAdmin(username)) return isSuperAdmin(username);
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*)
        FROM users u
        JOIN webv9_positions pos ON pos.id = u.position_id AND pos.is_active = 1
        JOIN webv9_user_project_scopes scope ON scope.user_id = u.id
        JOIN webv9_projects project ON project.id = scope.project_id AND project.is_active = 1
        WHERE u.username = ?
          AND (u.access_starts_at IS NULL OR u.access_starts_at <= CURRENT_TIMESTAMP)
          AND (u.access_ends_at IS NULL OR u.access_ends_at >= CURRENT_TIMESTAMP)
          AND (pos.department IS NULL OR TRIM(pos.department) = ''
               OR UPPER(TRIM(pos.department)) = UPPER(TRIM(u.department)))
          AND (scope.location IS NULL OR TRIM(scope.location) = '' OR scope.location = project.location)
          AND (scope.city IS NULL OR TRIM(scope.city) = '' OR scope.city = project.city)
        """, Integer.class, username.trim());
    return count != null && count > 0;
  }

  public boolean hasProjectScope(String username, long projectId, String location, String city) {
    if (!isAccountActive(username)) return false;
    if (isSuperAdmin(username)) return true;
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*)
        FROM users u
        JOIN webv9_positions pos ON pos.id = u.position_id AND pos.is_active = 1
        JOIN webv9_user_project_scopes scope ON scope.user_id = u.id
        JOIN webv9_projects project ON project.id = scope.project_id AND project.is_active = 1
        WHERE u.username = ? AND project.id = ?
          AND (u.access_starts_at IS NULL OR u.access_starts_at <= CURRENT_TIMESTAMP)
          AND (u.access_ends_at IS NULL OR u.access_ends_at >= CURRENT_TIMESTAMP)
          AND (pos.department IS NULL OR TRIM(pos.department) = ''
               OR UPPER(TRIM(pos.department)) = UPPER(TRIM(u.department)))
          AND (scope.location IS NULL OR TRIM(scope.location) = '' OR scope.location = ?)
          AND (scope.city IS NULL OR TRIM(scope.city) = '' OR scope.city = ?)
          AND (project.location IS NULL OR project.location = ?)
          AND (project.city IS NULL OR project.city = ?)
        """, Integer.class, username.trim(), projectId, location, city, location, city);
    return count != null && count > 0;
  }

  public boolean isGranted(String username, String module, String action) {
    if (!isAccountActive(username) || module == null || action == null) return false;
    if (isSuperAdmin(username)) return true;
    if (!hasAnyProjectScope(username)) return false;

    List<Integer> grants = jdbc.queryForList("""
        SELECT CASE
          WHEN override.is_granted IS NOT NULL THEN override.is_granted
          WHEN position_grant.permission_id IS NOT NULL THEN 1
          ELSE 0
        END
        FROM permissions permission
        JOIN users user ON user.username = ?
        LEFT JOIN webv9_position_permissions position_grant
          ON position_grant.position_id = user.position_id
         AND position_grant.permission_id = permission.id
        LEFT JOIN user_permission_overrides override
          ON override.user_id = user.id AND override.permission_id = permission.id
        WHERE permission.module = ?
          AND (
            (? = 'RECEIPT_CREATE' AND
              (permission.action = 'ADD' OR permission.permission_code = 'RECEIPT_CREATE'))
            OR (? IN ('RECEIPT_VERIFY', 'RECEIPT_POST', 'RECEIPT_REVERSE') AND
              (permission.action = ? OR permission.permission_code = ?))
            OR (? NOT IN ('RECEIPT_CREATE', 'RECEIPT_VERIFY', 'RECEIPT_POST', 'RECEIPT_REVERSE')
                AND permission.action = ?)
          )
        """, Integer.class, username.trim(), module, action, action, action, action, action, action);
    return grants.stream().anyMatch(grant -> grant != null && grant == 1);
  }

  public List<String> effectivePermissionCodes(String username) {
    if (!isAccountActive(username)) return List.of();
    if (isSuperAdmin(username)) {
      return jdbc.queryForList(
          "SELECT permission_code FROM permissions ORDER BY module, action, permission_code", String.class);
    }
    if (!hasAnyProjectScope(username)) return List.of();
    return jdbc.query("""
        SELECT DISTINCT permission.permission_code
        FROM users user
        CROSS JOIN permissions permission
        LEFT JOIN webv9_position_permissions position_grant
          ON position_grant.position_id = user.position_id
         AND position_grant.permission_id = permission.id
        LEFT JOIN user_permission_overrides override
          ON override.user_id = user.id AND override.permission_id = permission.id
        WHERE user.username = ?
          AND (override.is_granted = 1 OR
               (override.permission_id IS NULL AND position_grant.permission_id IS NOT NULL))
        ORDER BY permission.module, permission.action, permission.permission_code
        """, (rs, row) -> rs.getString(1), username.trim());
  }

  public List<String> effectivePermissionActions(String username) {
    if (!isAccountActive(username)) return List.of();
    if (isSuperAdmin(username)) {
      return jdbc.query("""
          SELECT DISTINCT permission.module, permission.action, permission.permission_code
          FROM permissions permission ORDER BY permission.module, permission.action, permission.permission_code
          """, (rs, row) -> List.of(rs.getString("module") + "|" + rs.getString("action"),
          rs.getString("module") + "|" + rs.getString("permission_code"))).stream()
          .flatMap(List::stream).distinct().toList();
    }
    if (!hasAnyProjectScope(username)) return List.of();
    return jdbc.query("""
        SELECT DISTINCT permission.module, permission.action, permission.permission_code
        FROM users user
        CROSS JOIN permissions permission
        LEFT JOIN webv9_position_permissions position_grant
          ON position_grant.position_id = user.position_id
         AND position_grant.permission_id = permission.id
        LEFT JOIN user_permission_overrides override
          ON override.user_id = user.id AND override.permission_id = permission.id
        WHERE user.username = ?
          AND (override.is_granted = 1 OR
               (override.permission_id IS NULL AND position_grant.permission_id IS NOT NULL))
        ORDER BY permission.module, permission.action, permission.permission_code
        """, (rs, row) -> List.of(rs.getString("module") + "|" + rs.getString("action"),
        rs.getString("module") + "|" + rs.getString("permission_code")), username.trim()).stream()
        .flatMap(List::stream).distinct().toList();
  }

  public List<String> effectiveModules(String username) {
    if (!isAccountActive(username)) return List.of();
    if (isSuperAdmin(username)) {
      LinkedHashSet<String> modules = new LinkedHashSet<>(SUPER_ADMIN_MODULES);
      modules.addAll(jdbc.queryForList(
          "SELECT DISTINCT module FROM permissions ORDER BY module", String.class));
      return new ArrayList<>(modules);
    }
    if (!hasAnyProjectScope(username)) return List.of();
    return jdbc.query("""
        SELECT DISTINCT permission.module
        FROM users user
        CROSS JOIN permissions permission
        LEFT JOIN webv9_position_permissions position_grant
          ON position_grant.position_id = user.position_id
         AND position_grant.permission_id = permission.id
        LEFT JOIN user_permission_overrides override
          ON override.user_id = user.id AND override.permission_id = permission.id
        WHERE user.username = ?
          AND permission.action = 'VIEW'
          AND (override.is_granted = 1 OR
               (override.permission_id IS NULL AND position_grant.permission_id IS NOT NULL))
        ORDER BY permission.module
        """, (rs, row) -> rs.getString(1), username.trim()).stream()
        .filter(module -> !List.of("Dashboard", "System Logs", "Admin & Settings").contains(module))
        .toList();
  }
}
