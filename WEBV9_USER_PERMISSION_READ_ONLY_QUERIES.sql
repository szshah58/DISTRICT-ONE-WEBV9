-- WEBV9 user/role/designation audit queries
-- MySQL 8.4. SELECT-only; run using an approved read-only account.
-- Do not select password_hash, JWTs, secret/config values, or credential tables.

-- 1) User account, database role field, role-link records, designation,
--    department, active status, and access dates. One row per linked role;
--    LEFT JOIN preserves users with missing role/designation links.
SELECT
    u.id AS user_id,
    u.username,
    u.department AS user_department,
    u.role AS users_role,
    r.role_code AS linked_role_code,
    r.role_name AS linked_role_name,
    u.position_id,
    p.position_code,
    p.position_name,
    p.department AS designation_department,
    p.is_active AS designation_active,
    u.is_active AS account_active,
    u.is_super_admin,
    u.access_starts_at,
    u.access_ends_at,
    CASE
        WHEN u.is_active <> 1 THEN 'INACTIVE'
        WHEN u.access_starts_at IS NOT NULL AND u.access_starts_at > CURRENT_TIMESTAMP THEN 'NOT_STARTED'
        WHEN u.access_ends_at IS NOT NULL AND u.access_ends_at < CURRENT_TIMESTAMP THEN 'EXPIRED'
        WHEN p.id IS NOT NULL AND p.is_active <> 1 THEN 'INACTIVE_DESIGNATION'
        ELSE 'ACTIVE_BY_STORED_FIELDS'
    END AS stored_status_assessment
FROM users u
LEFT JOIN user_roles ur ON ur.user_id = u.id
LEFT JOIN roles r ON r.id = ur.role_id
LEFT JOIN webv9_positions p ON p.id = u.position_id
ORDER BY u.username, r.role_code;

-- 2) Full configured role catalog and role-based permissions.
SELECT
    r.id AS role_id,
    r.role_code,
    r.role_name,
    p.permission_code,
    p.module,
    p.action
FROM roles r
LEFT JOIN role_permissions rp ON rp.role_id = r.id
LEFT JOIN permissions p ON p.id = rp.permission_id
ORDER BY r.role_code, p.module, p.action, p.permission_code;

-- 3) Designation catalog and permissions, including active designations with
--    no permission rows.
SELECT
    pos.id AS position_id,
    pos.position_code,
    pos.position_name,
    pos.department,
    pos.is_active AS position_active,
    perm.permission_code,
    perm.module,
    perm.action
FROM webv9_positions pos
LEFT JOIN webv9_position_permissions pp ON pp.position_id = pos.id
LEFT JOIN permissions perm ON perm.id = pp.permission_id
ORDER BY pos.department, pos.position_name, perm.module, perm.action;

-- 4) Explicit per-user permission overrides (do not infer a grant from absence
--    of a row).
SELECT
    u.username,
    u.department,
    u.role,
    pos.position_code,
    perm.permission_code,
    perm.module,
    perm.action,
    upo.is_granted,
    upo.updated_by
FROM user_permission_overrides upo
JOIN users u ON u.id = upo.user_id
JOIN permissions perm ON perm.id = upo.permission_id
LEFT JOIN webv9_positions pos ON pos.id = u.position_id
ORDER BY u.username, perm.module, perm.action, perm.permission_code;

-- 5) User project/location/city scopes, with project activity and location.
SELECT
    u.username,
    u.department,
    u.role,
    pos.position_code,
    s.project_id,
    pr.project_name,
    pr.location AS project_location,
    pr.city AS project_city,
    pr.is_active AS project_active,
    s.location AS assigned_location,
    s.city AS assigned_city,
    s.assigned_by
FROM webv9_user_project_scopes s
JOIN users u ON u.id = s.user_id
LEFT JOIN webv9_positions pos ON pos.id = u.position_id
LEFT JOIN webv9_projects pr ON pr.id = s.project_id
ORDER BY u.username, pr.project_name, s.location, s.city;

-- 6) Source-equivalent effective permission for one user and each configured
--    permission. Role permissions are returned for comparison only: the
--    request-time UserPermissionService inherits designation permissions and
--    individual overrides, not role_permissions.
--    This does not replace checking endpoint annotations, per-record guards,
--    or each concrete project/location scope.
SELECT
    u.username,
    u.department,
    u.role AS users_role,
    pos.position_code,
    u.is_active AS account_active,
    pos.is_active AS designation_active,
    u.access_starts_at,
    u.access_ends_at,
    perm.permission_code,
    perm.module,
    perm.action,
    CASE WHEN pp.permission_id IS NOT NULL THEN 1 ELSE 0 END AS designation_grants,
    upo.is_granted AS individual_override,
    CASE WHEN rp.permission_id IS NOT NULL THEN 1 ELSE 0 END AS role_config_grants_for_comparison,
    CASE
        WHEN u.is_active <> 1
          OR (u.access_starts_at IS NOT NULL AND u.access_starts_at > CURRENT_TIMESTAMP)
          OR (u.access_ends_at IS NOT NULL AND u.access_ends_at < CURRENT_TIMESTAMP)
          OR (
            NOT (COALESCE(u.is_super_admin, 0) = 1 OR UPPER(u.role) = 'SUPER_ADMIN')
            AND (
              pos.id IS NULL OR pos.is_active <> 1
              OR (
                pos.department IS NOT NULL AND TRIM(pos.department) <> ''
                AND COALESCE(UPPER(TRIM(u.department)), '') <> UPPER(TRIM(pos.department))
              )
            )
          )
          OR (
            NOT (COALESCE(u.is_super_admin, 0) = 1 OR UPPER(u.role) = 'SUPER_ADMIN')
            AND NOT EXISTS (
              SELECT 1
              FROM webv9_user_project_scopes scope
              JOIN webv9_projects project
                ON project.id = scope.project_id AND project.is_active = 1
              WHERE scope.user_id = u.id
                AND (scope.location IS NULL OR TRIM(scope.location) = ''
                     OR scope.location = project.location)
                AND (scope.city IS NULL OR TRIM(scope.city) = ''
                     OR scope.city = project.city)
            )
          )
        THEN 0
        WHEN COALESCE(u.is_super_admin, 0) = 1 OR UPPER(u.role) = 'SUPER_ADMIN' THEN 1
        WHEN upo.is_granted IS NOT NULL THEN upo.is_granted
        WHEN pp.permission_id IS NOT NULL THEN 1
        ELSE 0
    END AS source_equivalent_effective_grant,
    CASE
        WHEN u.is_active <> 1
          OR (u.access_starts_at IS NOT NULL AND u.access_starts_at > CURRENT_TIMESTAMP)
          OR (u.access_ends_at IS NOT NULL AND u.access_ends_at < CURRENT_TIMESTAMP)
          THEN 'ACCOUNT_INACTIVE_OR_OUTSIDE_ACCESS_DATES'
        WHEN COALESCE(u.is_super_admin, 0) = 1 OR UPPER(u.role) = 'SUPER_ADMIN'
          THEN 'SUPER_ADMIN_BYPASS'
        WHEN pos.id IS NULL OR pos.is_active <> 1 THEN 'MISSING_OR_INACTIVE_DESIGNATION'
        WHEN upo.is_granted IS NOT NULL THEN 'INDIVIDUAL_OVERRIDE'
        WHEN pp.permission_id IS NOT NULL THEN 'DESIGNATION_PERMISSION'
        ELSE 'NO_EFFECTIVE_GRANT'
    END AS effective_grant_source
FROM users u
CROSS JOIN permissions perm
LEFT JOIN webv9_positions pos ON pos.id = u.position_id
LEFT JOIN roles r ON r.role_code = u.role
LEFT JOIN role_permissions rp ON rp.role_id = r.id AND rp.permission_id = perm.id
LEFT JOIN webv9_position_permissions pp ON pp.position_id = u.position_id AND pp.permission_id = perm.id
LEFT JOIN user_permission_overrides upo ON upo.user_id = u.id AND upo.permission_id = perm.id
WHERE u.username = ?
ORDER BY u.username, perm.module, perm.action, perm.permission_code;

-- 7) Concise assignments needing review: role-field/link mismatch, missing or
--    inactive designation, department mismatch, or date-state mismatch.
SELECT
    u.username,
    u.department AS user_department,
    u.role AS users_role,
    linked.linked_roles,
    u.position_id,
    pos.position_code,
    pos.position_name,
    pos.department AS position_department,
    pos.is_active AS position_active,
    u.is_active AS account_active,
    u.access_starts_at,
    u.access_ends_at,
    CASE
        WHEN linked.linked_roles IS NULL THEN 'NO_USER_ROLE_LINK'
        WHEN FIND_IN_SET(u.role, linked.linked_roles) = 0 THEN 'ROLE_FIELD_LINK_MISMATCH'
        WHEN u.position_id IS NULL THEN 'NO_DESIGNATION'
        WHEN pos.id IS NULL THEN 'MISSING_DESIGNATION_ROW'
        WHEN pos.is_active <> 1 THEN 'INACTIVE_DESIGNATION'
        WHEN pos.department IS NOT NULL AND TRIM(pos.department) <> ''
             AND COALESCE(UPPER(TRIM(u.department)), '') <> UPPER(TRIM(pos.department))
          THEN 'DEPARTMENT_MISMATCH'
        WHEN u.access_starts_at IS NOT NULL AND u.access_starts_at > CURRENT_TIMESTAMP THEN 'ACCESS_NOT_STARTED'
        WHEN u.access_ends_at IS NOT NULL AND u.access_ends_at < CURRENT_TIMESTAMP THEN 'ACCESS_EXPIRED'
        WHEN u.is_active <> 1 THEN 'ACCOUNT_INACTIVE'
        ELSE 'REVIEW_ROLE_LINKS'
    END AS review_reason
FROM users u
LEFT JOIN (
    SELECT ur.user_id, GROUP_CONCAT(DISTINCT r.role_code ORDER BY r.role_code) AS linked_roles
    FROM user_roles ur
    JOIN roles r ON r.id = ur.role_id
    GROUP BY ur.user_id
) linked ON linked.user_id = u.id
LEFT JOIN webv9_positions pos ON pos.id = u.position_id
WHERE linked.linked_roles IS NULL
   OR FIND_IN_SET(u.role, COALESCE(linked.linked_roles, '')) = 0
   OR u.position_id IS NULL
   OR pos.id IS NULL
   OR pos.is_active <> 1
   OR (pos.department IS NOT NULL AND TRIM(pos.department) <> ''
       AND COALESCE(UPPER(TRIM(u.department)), '') <> UPPER(TRIM(pos.department)))
   OR (u.access_starts_at IS NOT NULL AND u.access_starts_at > CURRENT_TIMESTAMP)
   OR (u.access_ends_at IS NOT NULL AND u.access_ends_at < CURRENT_TIMESTAMP)
   OR u.is_active <> 1
ORDER BY u.username;

-- 1a) Accounts with more than one linked role. Compare these with the approved
--    job/role matrix; the inspected request-time permission resolver uses the
--    users.role field for its Super Admin check, but does not inherit
--    role_permissions as ordinary effective grants.
SELECT
    u.username,
    u.role AS users_role,
    COUNT(DISTINCT r.id) AS linked_role_count,
    GROUP_CONCAT(DISTINCT r.role_code ORDER BY r.role_code) AS linked_roles
FROM users u
JOIN user_roles ur ON ur.user_id = u.id
JOIN roles r ON r.id = ur.role_id
GROUP BY u.id, u.username, u.role
HAVING COUNT(DISTINCT r.id) > 1
ORDER BY u.username;
