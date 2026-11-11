package com.districtone.webv9.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.districtone.webv9.service.PlotInventoryRequest;
import com.districtone.webv9.service.PlotInventoryService;
import com.districtone.webv9.service.PaymentReversalService;
import com.districtone.webv9.security.AdminPasswordPolicy;
import com.districtone.webv9.security.ProjectAccessService;
import com.districtone.webv9.security.UserPermissionService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1")
public class OperationsController {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwordEncoder;
  private final PlotInventoryService plotInventory;
  private final PaymentReversalService paymentReversals;
  private final ProjectAccessService projectAccess;
  private final UserPermissionService userPermissions;
  public OperationsController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, PlotInventoryService plotInventory,
                              PaymentReversalService paymentReversals, ProjectAccessService projectAccess,
                              UserPermissionService userPermissions) {
    this.jdbc = jdbc;
    this.passwordEncoder = passwordEncoder;
    this.plotInventory = plotInventory;
    this.paymentReversals = paymentReversals;
    this.projectAccess = projectAccess;
    this.userPermissions = userPermissions;
  }

  @GetMapping("/plots/inventory")
  public List<Map<String, Object>> plots(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String availability,
                                         Authentication authentication) {
    return plotInventory.list(q, status, availability).stream().filter(row -> {
      Object projectId = row.get("projectId");
      return projectId instanceof Number n
          ? projectAccess.canAccessProject(authentication.getName(), n.longValue())
          : projectAccess.isSuperAdmin(authentication.getName());
    }).toList();
  }

  @GetMapping("/plots/inventory/blocks")
  public List<Map<String, Object>> plotInventoryBlocks(Authentication authentication) {
    return plotInventory.blocks().stream().filter(row -> row.get("projectId") instanceof Number n
        ? projectAccess.canAccessProject(authentication.getName(), n.longValue())
        : projectAccess.isSuperAdmin(authentication.getName())).toList();
  }

  @PostMapping("/plots/inventory")
  public Map<String, Object> createPlot(@Valid @RequestBody PlotInventoryRequest request, Authentication authentication) {
    projectAccess.requireProject(authentication.getName(), projectAccess.projectForBlock(request.blockId()));
    return plotInventory.create(request);
  }

  @PutMapping("/plots/inventory/{plotId}")
  public Map<String, Object> updatePlot(@PathVariable Long plotId,
                                        @Valid @RequestBody PlotInventoryRequest request, Authentication authentication) {
    projectAccess.requirePlot(authentication.getName(), plotId);
    projectAccess.requireProject(authentication.getName(), projectAccess.projectForBlock(request.blockId()));
    return plotInventory.update(plotId, request);
  }

  @GetMapping("/finance/receivables")
  public List<Map<String,Object>> receivables() {
    String username = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
    return jdbc.queryForList("SELECT r.id,r.booking_id AS bookingId,r.member_id AS memberId,m.mbr_no AS mbrNo,m.name,r.due_date AS dueDate,r.original_amount AS originalAmount,r.paid_amount AS paidAmount,r.surcharge_amount AS surcharge,r.adjustment_amount AS adjustments,(r.original_amount+r.surcharge_amount-r.paid_amount-r.adjustment_amount) AS outstanding,r.status,r.description FROM webv9_receivables r JOIN members m ON m.member_id=r.member_id ORDER BY r.due_date")
        .stream().filter(row -> row.get("bookingId") instanceof Number n && projectAccess.canAccessBooking(username, n.longValue())).toList();
  }

  @PostMapping("/finance/adjustments")
  @Transactional
  public Map<String,Object> adjustment(@RequestBody Map<String,Object> body, Authentication auth) {
    requireText(body, "reason");
    Object receivableId = body.get("receivableId");
    if (receivableId instanceof Number n) {
      Long bookingId = jdbc.queryForObject("SELECT booking_id FROM webv9_receivables WHERE id=?", Long.class, n.longValue());
      if (bookingId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Receivable is not tied to a project booking");
      projectAccess.requireBooking(auth.getName(), bookingId);
    }
    String no = nextCode("ADJ", "webv9_finance_adjustments", "adjustment_no");
    Long user = findUserId(auth.getName());
    jdbc.update("INSERT INTO webv9_finance_adjustments(adjustment_no,member_id,receivable_id,amount,direction,reason,created_by) VALUES(?,?,?,?,?,?,?)", no, body.get("memberId"), body.get("receivableId"), body.get("amount"), body.getOrDefault("direction","CREDIT"), body.get("reason"), user);
    audit(auth, "CREATE", "FINANCE_ADJUSTMENT", no);
    return Map.of("adjustmentNo", no, "status", "PENDING");
  }

  @PostMapping("/finance/payments/{id}/reverse")
  public Map<String,Object> reverse(@PathVariable Long id, @RequestBody Map<String,Object> body, Authentication auth) {
    projectAccess.requirePayment(auth.getName(), id);
    requireText(body, "reason");
    String no = nextCode("REV", "webv9_finance_reversals", "reversal_no");
    Long userId = findUserId(auth.getName());
    return paymentReversals.reverse(id, no, String.valueOf(body.get("reason")), userId, auth.getName());
  }

  @GetMapping("/audit")
  public List<Map<String, Object>> audit() {
    String username = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
    if (!projectAccess.isSuperAdmin(username)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System-wide audit requires Super Admin access");
    return jdbc.queryForList("""
        SELECT id, occurred_at AS occurredAt, username, action, module, details
        FROM system_logs ORDER BY occurred_at DESC LIMIT 250
        """);
  }

  @GetMapping("/reports/summary")
  public Map<String, Object> reportSummary() {
    String username = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
    if (!projectAccess.isSuperAdmin(username)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System-wide summary requires Super Admin access");
    return Map.of(
        "members", jdbc.queryForObject("SELECT COUNT(*) FROM members", Long.class),
        "registrations", jdbc.queryForObject("SELECT COUNT(*) FROM registrations", Long.class),
        "plots", jdbc.queryForObject("SELECT COUNT(*) FROM plots", Long.class),
        "bookings", jdbc.queryForObject("SELECT COUNT(*) FROM sales_bookings", Long.class),
        "recoveryRows", jdbc.queryForObject("SELECT COUNT(*) FROM recovery_staging", Long.class),
        "paymentReceipts", jdbc.queryForObject("SELECT COUNT(*) FROM payments_receipts", Long.class));
  }

  @GetMapping("/admin/users")
  public List<Map<String, Object>> users(Authentication authentication) {
    return jdbc.queryForList("""
        SELECT u.id, u.username, u.display_name AS displayName, u.email, u.mobile, u.role,
               p.position_code AS positionCode, p.position_name AS positionName, u.department,
               u.is_active AS active, u.access_starts_at AS accessStartsAt,
               u.access_ends_at AS accessEndsAt, u.last_login_at AS lastLogin
        FROM users u LEFT JOIN webv9_positions p ON p.id=u.position_id ORDER BY u.username
        """).stream().filter(row -> projectAccess.canManageUser(authentication.getName(),
            ((Number) row.get("id")).longValue())).toList();
  }

  @GetMapping("/admin/users/{id}")
  public Map<String, Object> user(@PathVariable Long id, Authentication authentication) {
    if (!projectAccess.canManageUser(authentication.getName(), id))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user is outside your department/project/location scope");
    Map<String, Object> user;
    try {
      user = jdbc.queryForMap("""
          SELECT u.id, u.username, u.display_name AS displayName, u.email, u.mobile AS contact,
                 u.department, u.role, u.is_active AS active, u.access_starts_at AS accessStartsAt,
                 u.access_ends_at AS accessEndsAt, p.id AS positionId, p.position_code AS positionCode,
                 p.position_name AS positionName
          FROM users u LEFT JOIN webv9_positions p ON p.id=u.position_id WHERE u.id = ?
          """, id);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }
    user.put("permissions", userPermissions.effectivePermissionCodes(String.valueOf(user.get("username"))));
    user.put("projectScopes", jdbc.queryForList("""
        SELECT project_id AS projectId, location, city FROM webv9_user_project_scopes
        WHERE user_id=? ORDER BY project_id, location, city
        """, id));
    return user;
  }

  public record AdminRoleOption(String code, String name, List<String> permissions) {}
  public record AdminPermissionOption(String code, String module, String action) {}
  public record JobTitleRequest(@NotBlank @Size(max = 50) String code,
                               @NotBlank @Size(max = 100) String name,
                               @Size(max = 100) String department) {}

  @GetMapping("/admin/positions")
  public List<Map<String, Object>> positions() {
    List<Map<String, Object>> positions = jdbc.queryForList("""
        SELECT id, position_code AS code, position_name AS name, department, is_active AS active
        FROM webv9_positions ORDER BY department, position_name
        """);
    for (Map<String, Object> position : positions) {
      position.put("permissions", jdbc.query("""
          SELECT permission.permission_code
          FROM webv9_position_permissions positionGrant
          JOIN permissions permission ON permission.id = positionGrant.permission_id
          WHERE positionGrant.position_id = ?
          ORDER BY permission.module, permission.action, permission.permission_code
          """, (rs, row) -> rs.getString(1), ((Number) position.get("id")).longValue()));
    }
    return positions;
  }

  @PostMapping("/admin/positions")
  @Transactional
  public Map<String, Object> createPosition(@Valid @RequestBody JobTitleRequest request,
                                           Authentication authentication) {
    String code = request.code().trim().toUpperCase(Locale.ROOT);
    String name = request.name().trim();
    if (!code.matches("[A-Z][A-Z0-9_]{1,49}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Job-title code must start with a letter and contain only letters, numbers, and underscores.");
    Integer exists = jdbc.queryForObject("""
        SELECT COUNT(*) FROM webv9_positions
        WHERE UPPER(position_code) = ? OR LOWER(position_name) = LOWER(?)
        """, Integer.class, code, name);
    if (exists != null && exists > 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "That job-title code or name is already configured.");
    jdbc.update("""
        INSERT INTO webv9_positions(position_code, position_name, department, is_active)
        VALUES (?, ?, ?, 1)
        """, code, name, request.department() == null || request.department().isBlank()
            ? null : request.department().trim());
    Long id = jdbc.queryForObject("SELECT id FROM webv9_positions WHERE position_code = ?", Long.class, code);
    audit(authentication, "CREATE_JOB_TITLE", "USERS", code);
    return Map.of("id", id, "code", code, "name", name);
  }

  @PutMapping("/admin/positions/{positionId}/permissions")
  @Transactional
  public Map<String, Object> setPositionPermissions(@PathVariable Long positionId,
      @RequestBody Set<String> permissionCodes, Authentication authentication) {
    Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM webv9_positions WHERE id=?", Integer.class, positionId);
    if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job title not found");
    Set<String> requested = permissionCodes == null ? Set.of() : new HashSet<>(permissionCodes);
    Set<String> known = new HashSet<>(jdbc.queryForList("SELECT permission_code FROM permissions", String.class));
    if (!known.containsAll(requested))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more selected action rights are not configured");
    jdbc.update("DELETE FROM webv9_position_permissions WHERE position_id=?", positionId);
    for (String code : requested) jdbc.update("""
        INSERT INTO webv9_position_permissions(position_id, permission_id, granted_by)
        SELECT ?, id, ? FROM permissions WHERE permission_code=?
        """, positionId, findUserId(authentication.getName()), code);
    audit(authentication, "SET_POSITION_RIGHTS", "USERS", String.valueOf(positionId));
    return Map.of("positionId", positionId, "permissionCount", requested.size());
  }

  @GetMapping("/admin/users/options")
  public Map<String, Object> userOptions(Authentication authentication) {
    boolean mayAssignSuperAdmin = projectAccess.isSuperAdmin(authentication.getName());
    List<Map<String, Object>> roleRows = jdbc.queryForList("""
        SELECT id, role_code AS code, role_name AS name FROM roles
        WHERE role_code <> 'SUPER_ADMIN' OR ? = 1 ORDER BY id
        """, mayAssignSuperAdmin ? 1 : 0);
    List<AdminRoleOption> roles = roleRows.stream().map(row -> {
      Long id = ((Number) row.get("id")).longValue();
      List<String> rolePermissions = jdbc.query("""
          SELECT p.permission_code FROM permissions p
          JOIN role_permissions rp ON rp.permission_id = p.id
          WHERE rp.role_id = ? ORDER BY p.module, p.action, p.permission_code
          """, (rs, index) -> rs.getString(1), id);
      return new AdminRoleOption((String) row.get("code"), (String) row.get("name"), rolePermissions);
    }).toList();
    List<AdminPermissionOption> permissions = jdbc.query("""
        SELECT permission_code, module, action FROM permissions ORDER BY module, action, permission_code
        """, (rs, index) -> new AdminPermissionOption(
            rs.getString("permission_code"), rs.getString("module"), rs.getString("action")));
    List<Map<String, Object>> projects = jdbc.queryForList("""
            SELECT id, project_name AS projectName, location, city
            FROM webv9_projects WHERE is_active = 1 ORDER BY project_name
            """).stream().filter(project -> projectAccess.isSuperAdmin(authentication.getName())
            || projectAccess.canAccessProject(authentication.getName(),
                ((Number) project.get("id")).longValue())).toList();
    return Map.of("roles", roles, "permissions", permissions, "positions", positions(), "projects", projects);
  }

  public record AdminUserRequest(
      @NotBlank @Size(max = 80) String username,
      @NotBlank @Size(max = 150) String displayName,
      @NotBlank @Email @Size(max = 180) String email,
      @Size(max = 40) String contact,
      @Size(max = 100) String department,
      @NotBlank @Size(max = 50) String role,
      @NotBlank @Size(min = 8, max = 72) String password,
      @NotBlank @Size(min = 8, max = 72) String confirmPassword,
      boolean active,
      Set<String> permissions,
      Long positionId,
      LocalDateTime accessStartsAt,
      LocalDateTime accessEndsAt,
      List<ProjectScopeAssignment> projectScopes) {}

  public record AdminUserUpdateRequest(
      @NotBlank @Size(max = 80) String username,
      @NotBlank @Size(max = 150) String displayName,
      @NotBlank @Email @Size(max = 180) String email,
      @Size(max = 40) String contact,
      @Size(max = 100) String department,
      @NotBlank @Size(max = 50) String role,
      @Size(min = 8, max = 72) String password,
      @Size(max = 72) String confirmPassword,
      boolean active,
      Set<String> permissions,
      Long positionId,
      LocalDateTime accessStartsAt,
      LocalDateTime accessEndsAt,
      List<ProjectScopeAssignment> projectScopes) {}

  public record UserStatusRequest(boolean active) {}

  public record ProjectScopeAssignment(@NotNull Long projectId, String location, String city) {}
  public record UserAccessAssignment(@NotNull Long positionId, String department,
                                     LocalDateTime startsAt, LocalDateTime endsAt,
                                     List<ProjectScopeAssignment> projectScopes, Set<String> permissions) {}

  @PutMapping("/admin/users/{id}/access")
  @Transactional
  public Map<String, Object> assignUserAccess(@PathVariable Long id,
      @Valid @RequestBody UserAccessAssignment request, Authentication authentication) {
    String actor = authentication.getName();
    boolean superAdmin = projectAccess.isSuperAdmin(actor);
    if (id.equals(findUserId(actor)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot change your own access assignment");
    Map<String, Object> target;
    try {
      target = jdbc.queryForMap("""
          SELECT role, department, is_super_admin AS isSuperAdmin
          FROM users WHERE id = ?
          """, id);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }
    if (!superAdmin) {
      if (Boolean.TRUE.equals(target.get("isSuperAdmin"))
          || Set.of("SUPER_ADMIN", "ADMIN").contains(String.valueOf(target.get("role")).toUpperCase(Locale.ROOT)))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Super Admin may change administrator assignments");
      if (!projectAccess.canManageUser(actor, id))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user is outside your project/location scope");
      if (isDepartmentManager(actor)) {
        String actorDepartment = jdbc.queryForObject("SELECT department FROM users WHERE username = ?", String.class, actor);
        if (!Objects.equals(actorDepartment, target.get("department")))
          throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Department Managers may manage staff only in their department");
      }
    }
    Map<String, Object> position;
    try {
      position = jdbc.queryForMap("SELECT position_code, department FROM webv9_positions WHERE id = ? AND is_active = 1", request.positionId());
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an active configured job title");
    }
    if (!superAdmin && Set.of("SUPER_ADMIN", "ADMIN").contains(String.valueOf(position.get("position_code"))))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Super Admin may assign administrator job titles");
    validatePositionRights(actor, request.positionId(), superAdmin);
    Object positionDepartment = position.get("department");
    String assignedDepartment = request.department() == null
        ? (String) target.get("department") : request.department().trim();
    if (positionDepartment != null && !String.valueOf(positionDepartment).equalsIgnoreCase(assignedDepartment))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The user's department must match the selected job title's department");
    if (request.startsAt() != null && request.endsAt() != null && request.endsAt().isBefore(request.startsAt()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Access end must be on or after access start");

    List<ProjectScopeAssignment> scopes = request.projectScopes() == null ? List.of() : request.projectScopes();
    if (!superAdmin && scopes.isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "At least one authorized project/location must remain assigned.");
    Set<String> uniqueScopes = new HashSet<>();
    for (ProjectScopeAssignment scope : scopes) {
      Map<String, Object> project = jdbc.queryForMap("""
          SELECT location, city FROM webv9_projects WHERE id = ? AND is_active = 1
          """, scope.projectId());
      String location = scope.location() == null ? (String) project.get("location") : scope.location().trim();
      String city = scope.city() == null ? (String) project.get("city") : scope.city().trim();
      if (!Objects.equals(project.get("location"), location) || !Objects.equals(project.get("city"), city))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project location and city must match the configured project.");
      if (!superAdmin && !projectAccess.canManageScope(actor, scope.projectId(), location, city))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A requested project/location assignment exceeds your scope");
      String key = scope.projectId() + "|" + location + "|" + city;
      if (!uniqueScopes.add(key)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate project/location assignment");
    }

    Set<String> selected = request.permissions() == null ? Set.of() : request.permissions();
    Map<String, String> permissionKeys = new java.util.HashMap<>();
    for (Map<String, Object> row : jdbc.queryForList("SELECT permission_code, module, action FROM permissions"))
      permissionKeys.put(String.valueOf(row.get("permission_code")), row.get("module") + "|" + row.get("action"));
    if (!permissionKeys.keySet().containsAll(selected))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more selected action rights are not configured");
    if (!superAdmin) for (String code : selected) {
      String[] key = permissionKeys.get(code).split("\\|", 2);
      if (!userPermissions.isGranted(actor, key[0], permissionAction(code, key[1])))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot grant an action right you do not hold: " + code);
    }

    Long assignedBy = findUserId(actor);
    jdbc.update("UPDATE users SET position_id=?, department=?, access_starts_at=?, access_ends_at=? WHERE id=?",
        request.positionId(), assignedDepartment, request.startsAt(), request.endsAt(), id);
    jdbc.update("DELETE FROM webv9_user_project_scopes WHERE user_id=?", id);
    for (ProjectScopeAssignment scope : scopes) {
      Map<String, Object> project = jdbc.queryForMap("SELECT location, city FROM webv9_projects WHERE id = ?", scope.projectId());
      jdbc.update("""
          INSERT INTO webv9_user_project_scopes(user_id, project_id, location, city, assigned_by)
          VALUES (?,?,?,?,?)
          """, id, scope.projectId(), scope.location() == null ? project.get("location") : scope.location().trim(),
          scope.city() == null ? project.get("city") : scope.city().trim(), assignedBy);
    }
    jdbc.update("DELETE FROM user_permission_overrides WHERE user_id=?", id);
    List<Map<String, Object>> catalog = jdbc.queryForList("""
        SELECT permission.id, permission.permission_code,
               CASE WHEN positionGrant.permission_id IS NULL THEN 0 ELSE 1 END AS position_granted
        FROM permissions permission
        LEFT JOIN webv9_position_permissions positionGrant
          ON positionGrant.permission_id = permission.id AND positionGrant.position_id = ?
        """, request.positionId());
    for (Map<String, Object> permission : catalog) {
      String code = String.valueOf(permission.get("permission_code"));
      boolean requested = selected.contains(code);
      boolean positionGranted = ((Number) permission.get("position_granted")).intValue() == 1;
      if (requested != positionGranted) jdbc.update("""
          INSERT INTO user_permission_overrides(user_id, permission_id, is_granted, updated_by)
          VALUES (?, ?, ?, ?)
          """, id, ((Number) permission.get("id")).longValue(), requested ? 1 : 0, assignedBy);
    }
    audit(authentication, "ASSIGN_ACCESS", "USERS", String.valueOf(target.get("role")) + ":" + id);
    return Map.of("id", id, "positionCode", position.get("position_code"), "projectScopeCount", scopes.size(),
        "explicitPermissionCount", selected.size(), "startsAt", request.startsAt() == null ? "" : request.startsAt(),
        "endsAt", request.endsAt() == null ? "" : request.endsAt());
  }

  @PostMapping("/admin/users")
  @Transactional
  public Map<String, Object> createUser(@Valid @RequestBody AdminUserRequest request, Authentication authentication) {
    requirePasswordConfirmation(request.password(), request.confirmPassword(), true);
    AdminPasswordPolicy.validate(request.password());
    String username = request.username().trim();
    String displayName = request.displayName().trim();
    String email = request.email().trim();
    String role = request.role().trim().toUpperCase(Locale.ROOT);
    boolean superAdmin = projectAccess.isSuperAdmin(authentication.getName());
    if (!superAdmin && Set.of("ADMIN", "SUPER_ADMIN").contains(role))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Super Admin may create administrator accounts");
    if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username) > 0)
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Username is already registered");
    if ("SUPER_ADMIN".equals(role) && !projectAccess.isSuperAdmin(authentication.getName()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a super administrator can assign the super administrator role");
    Long roleId;
    try {
      roleId = jdbc.queryForObject("SELECT id FROM roles WHERE role_code = ?", Long.class, role);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a role from the configured role list");
    }
    Set<String> selectedPermissions = request.permissions() == null
        ? Set.of() : new HashSet<>(request.permissions());
    Set<String> knownPermissions = new HashSet<>(jdbc.queryForList(
        "SELECT permission_code FROM permissions", String.class));
    if (!knownPermissions.containsAll(selectedPermissions))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more selected permissions are not configured");
    Map<String, Object> selectedPosition = null;
    if (!superAdmin && (request.positionId() == null || request.projectScopes() == null || request.projectScopes().isEmpty()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A configured job title and at least one authorized project/location are required");
    if (request.positionId() != null) {
      try { selectedPosition = jdbc.queryForMap("SELECT position_code, department FROM webv9_positions WHERE id=? AND is_active=1", request.positionId()); }
      catch (EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an active configured job title"); }
      if (!superAdmin && Set.of("ADMIN", "SUPER_ADMIN").contains(String.valueOf(selectedPosition.get("position_code"))))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Super Admin may create administrator job titles");
      validatePositionRights(authentication.getName(), request.positionId(), superAdmin);
      if (selectedPosition.get("department") != null && request.department() != null
          && !String.valueOf(selectedPosition.get("department")).equalsIgnoreCase(request.department().trim()))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The department must match the selected job title");
    }
    if (request.accessStartsAt() != null && request.accessEndsAt() != null
        && request.accessEndsAt().isBefore(request.accessStartsAt()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Access end must be on or after access start");
    if (!superAdmin) {
      Integer isDepartmentManager = jdbc.queryForObject("""
          SELECT COUNT(*) FROM users u JOIN webv9_positions p ON p.id=u.position_id
          WHERE u.username=? AND p.position_code='DEPARTMENT_MANAGER'
          """, Integer.class, authentication.getName());
      if (isDepartmentManager != null && isDepartmentManager > 0) {
        String actorDepartment = jdbc.queryForObject("SELECT department FROM users WHERE username=?", String.class, authentication.getName());
        if (!Objects.equals(actorDepartment, request.department()))
          throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Department Managers may create staff only in their department");
      }
      if (request.accessStartsAt() != null && request.accessEndsAt() != null && request.accessEndsAt().isBefore(request.accessStartsAt()))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Access end must be on or after access start");
      for (String code : selectedPermissions) {
        Map<String, Object> permission = jdbc.queryForMap("SELECT module, action FROM permissions WHERE permission_code=?", code);
        if (!userPermissions.isGranted(authentication.getName(), String.valueOf(permission.get("module")),
            permissionAction(code, String.valueOf(permission.get("action")))))
          throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot grant an action right you do not hold: " + code);
      }
      for (ProjectScopeAssignment scope : request.projectScopes()) {
        Map<String, Object> project = jdbc.queryForMap("""
            SELECT location, city FROM webv9_projects WHERE id = ? AND is_active = 1
            """, scope.projectId());
        String location = scope.location() == null ? (String) project.get("location") : scope.location().trim();
        String city = scope.city() == null ? (String) project.get("city") : scope.city().trim();
        if (!Objects.equals(project.get("location"), location) || !Objects.equals(project.get("city"), city))
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project location and city must match the configured project.");
        if (!projectAccess.canManageScope(authentication.getName(), scope.projectId(), location, city))
          throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A requested project/location assignment exceeds your scope");
      }
    }
    Long assignedBy = findUserId(authentication.getName());
    String hash = passwordEncoder.encode(request.password());
    jdbc.update("""
      INSERT INTO users
      (username,password_hash,display_name,email,mobile,department,role,is_active,is_super_admin,position_id,access_starts_at,access_ends_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", username, hash, displayName, email,
      request.contact(), request.department(), role, request.active() ? 1 : 0,
      "SUPER_ADMIN".equals(role) ? 1 : 0, request.positionId(), request.accessStartsAt(), request.accessEndsAt());
    Long userId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    jdbc.update("INSERT INTO user_roles(user_id, role_id, assigned_by) VALUES (?, ?, ?)", userId, roleId, assignedBy);
    for (ProjectScopeAssignment scope : request.projectScopes() == null ? List.<ProjectScopeAssignment>of() : request.projectScopes()) {
      Map<String, Object> project = jdbc.queryForMap("""
          SELECT location, city FROM webv9_projects WHERE id = ? AND is_active = 1
          """, scope.projectId());
      jdbc.update("""
          INSERT INTO webv9_user_project_scopes(user_id, project_id, location, city, assigned_by)
          VALUES (?, ?, ?, ?, ?)
          """, userId, scope.projectId(),
          scope.location() == null ? project.get("location") : scope.location().trim(),
          scope.city() == null ? project.get("city") : scope.city().trim(), assignedBy);
    }
    List<Map<String, Object>> catalog = jdbc.queryForList("""
        SELECT p.id, p.permission_code,
               CASE WHEN pp.permission_id IS NOT NULL THEN 1 ELSE 0 END AS position_granted
        FROM permissions p JOIN users u ON u.id=?
        LEFT JOIN webv9_position_permissions pp ON pp.permission_id=p.id AND pp.position_id=u.position_id
        """, userId);
    for (Map<String, Object> permission : catalog) {
      String code = (String) permission.get("permission_code");
      boolean granted = selectedPermissions.contains(code);
      boolean positionGranted = ((Number) permission.get("position_granted")).intValue() == 1;
      if (granted != positionGranted) {
        jdbc.update("""
            INSERT INTO user_permission_overrides(user_id, permission_id, is_granted, updated_by)
            VALUES (?, ?, ?, ?)
            """, userId, ((Number) permission.get("id")).longValue(), granted ? 1 : 0, assignedBy);
      }
    }
    audit(authentication, "CREATE_USER", "USERS", username + "; role=" + role
        + "; positionId=" + request.positionId() + "; scopeCount="
        + (request.projectScopes() == null ? 0 : request.projectScopes().size()));
    return Map.of("created", true, "id", userId, "username", username, "role", role, "active", request.active());
  }

  @PutMapping("/admin/users/{id}")
  @Transactional
  public Map<String, Object> updateUser(@PathVariable Long id,
      @Valid @RequestBody AdminUserUpdateRequest request, Authentication authentication) {
    Map<String, Object> existing;
    try {
      existing = jdbc.queryForMap("""
          SELECT u.username, u.role, u.department, u.position_id AS positionId,
                 p.department AS positionDepartment
          FROM users u LEFT JOIN webv9_positions p ON p.id=u.position_id WHERE u.id=?
          """, id);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }
    boolean superAdmin = projectAccess.isSuperAdmin(authentication.getName());
    if (!superAdmin && (id.equals(findUserId(authentication.getName())) || !projectAccess.canManageUser(authentication.getName(), id)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot manage your own access or a user outside your scope");
    requirePasswordConfirmation(request.password(), request.confirmPassword(), false);
    if (request.password() != null && !request.password().isBlank()) {
      AdminPasswordPolicy.validate(request.password());
      if (Set.of("ADMIN", "SUPER_ADMIN").contains(String.valueOf(existing.get("role")).toUpperCase(Locale.ROOT))
          && !projectAccess.isSuperAdmin(authentication.getName())) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Only a super administrator can reset an administrator password");
      }
    }
    String username = request.username().trim();
    String displayName = request.displayName().trim();
    String email = request.email().trim();
    String role = request.role().trim().toUpperCase(Locale.ROOT);
    if (!superAdmin && isDepartmentManager(authentication.getName())) {
      String actorDepartment = jdbc.queryForObject("SELECT department FROM users WHERE username=?", String.class, authentication.getName());
      if (!Objects.equals(actorDepartment, request.department()))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Department Managers may manage staff only in their department");
    }
    Object selectedPositionDepartment = existing.get("positionDepartment");
    if (request.positionId() != null) {
      try {
        Map<String, Object> position = jdbc.queryForMap("""
            SELECT department FROM webv9_positions WHERE id = ? AND is_active = 1
            """, request.positionId());
        selectedPositionDepartment = position.get("department");
      } catch (EmptyResultDataAccessException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an active configured job title");
      }
    }
    if (selectedPositionDepartment != null && request.department() != null
        && !String.valueOf(selectedPositionDepartment).equalsIgnoreCase(request.department()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The user's department must match the selected job title's department");
    if (!superAdmin && (Set.of("ADMIN", "SUPER_ADMIN").contains(role)
        || Set.of("ADMIN", "SUPER_ADMIN").contains(String.valueOf(existing.get("role")).toUpperCase(Locale.ROOT))))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Super Admin may manage administrator accounts");
    if ("SUPER_ADMIN".equals(existing.get("role")) && !"SUPER_ADMIN".equals(role)
        && !projectAccess.isSuperAdmin(authentication.getName()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a super administrator can change a super administrator account");
    if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ? AND id <> ?", Integer.class, username, id) > 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already registered");
    if ("SUPER_ADMIN".equals(role) && !projectAccess.isSuperAdmin(authentication.getName()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a super administrator can assign the super administrator role");
    Long roleId;
    try {
      roleId = jdbc.queryForObject("SELECT id FROM roles WHERE role_code = ?", Long.class, role);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a role from the configured role list");
    }
    Set<String> selectedPermissions = request.permissions() == null
        ? Set.of() : new HashSet<>(request.permissions());
    Set<String> knownPermissions = new HashSet<>(jdbc.queryForList(
        "SELECT permission_code FROM permissions", String.class));
    if (!knownPermissions.containsAll(selectedPermissions))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more selected permissions are not configured");
    if (!superAdmin) for (String code : selectedPermissions) {
      Map<String, Object> permission = jdbc.queryForMap("SELECT module, action FROM permissions WHERE permission_code=?", code);
      if (!userPermissions.isGranted(authentication.getName(), String.valueOf(permission.get("module")),
          permissionAction(code, String.valueOf(permission.get("action")))))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot grant an action right you do not hold: " + code);
    }

    Long assignedBy = findUserId(authentication.getName());
    if (request.password() == null || request.password().isBlank()) {
      jdbc.update("""
          UPDATE users SET username=?, display_name=?, email=?, mobile=?, department=?, role=?, is_active=?, is_super_admin=?
          WHERE id=?
          """, username, displayName, email, request.contact(), request.department(), role,
          request.active() ? 1 : 0, "SUPER_ADMIN".equals(role) ? 1 : 0, id);
    } else {
      jdbc.update("""
          UPDATE users SET username=?, password_hash=?, display_name=?, email=?, mobile=?, department=?, role=?, is_active=?, is_super_admin=?
          WHERE id=?
          """, username, passwordEncoder.encode(request.password()), displayName, email, request.contact(),
          request.department(), role, request.active() ? 1 : 0, "SUPER_ADMIN".equals(role) ? 1 : 0, id);
    }
    Integer roleRows = jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id=?", Integer.class, id);
    if (roleRows != null && roleRows > 0)
      jdbc.update("UPDATE user_roles SET role_id=?, assigned_by=? WHERE user_id=?", roleId, assignedBy, id);
    else
      jdbc.update("INSERT INTO user_roles(user_id, role_id, assigned_by) VALUES (?, ?, ?)", id, roleId, assignedBy);

    jdbc.update("DELETE FROM user_permission_overrides WHERE user_id=?", id);
    List<Map<String, Object>> catalog = jdbc.queryForList("""
        SELECT p.id, p.permission_code,
               CASE WHEN pp.permission_id IS NOT NULL THEN 1 ELSE 0 END AS position_granted
        FROM permissions p JOIN users u ON u.id=?
        LEFT JOIN webv9_position_permissions pp ON pp.permission_id=p.id AND pp.position_id=u.position_id
        """, id);
    for (Map<String, Object> permission : catalog) {
      String code = (String) permission.get("permission_code");
      boolean granted = selectedPermissions.contains(code);
      boolean positionGranted = ((Number) permission.get("position_granted")).intValue() == 1;
      if (granted != positionGranted) {
        jdbc.update("""
            INSERT INTO user_permission_overrides(user_id, permission_id, is_granted, updated_by)
            VALUES (?, ?, ?, ?)
            """, id, ((Number) permission.get("id")).longValue(), granted ? 1 : 0, assignedBy);
      }
    }
    if (request.positionId() != null) {
      assignUserAccess(id, new UserAccessAssignment(request.positionId(), request.department(),
          request.accessStartsAt(), request.accessEndsAt(), request.projectScopes(), selectedPermissions),
          authentication);
    }
    audit(authentication, "UPDATE", "USERS", username);
    return Map.of("updated", true, "id", id, "username", username, "role", role, "active", request.active());
  }

  @PatchMapping("/admin/users/{id}/status")
  @Transactional
  public Map<String, Object> updateUserStatus(@PathVariable Long id,
      @RequestBody UserStatusRequest request, Authentication authentication) {
    Map<String, Object> existing;
    try {
      existing = jdbc.queryForMap("SELECT username, role FROM users WHERE id = ?", id);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }
    boolean superAdmin = projectAccess.isSuperAdmin(authentication.getName());
    if (!superAdmin && Set.of("SUPER_ADMIN", "ADMIN").contains(String.valueOf(existing.get("role")).toUpperCase(Locale.ROOT)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Super Admin may manage administrator accounts");
    if (!superAdmin && (id.equals(findUserId(authentication.getName())) || !projectAccess.canManageUser(authentication.getName(), id)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot manage your own access or a user outside your scope");
    if (!request.active() && id.equals(findUserId(authentication.getName())))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot deactivate your own account");
    jdbc.update("UPDATE users SET is_active=? WHERE id=?", request.active() ? 1 : 0, id);
    String action = request.active() ? "ACTIVATE" : "DEACTIVATE";
    audit(authentication, action, "USERS", String.valueOf(existing.get("username")));
    return Map.of("id", id, "username", existing.get("username"), "active", request.active());
  }

  @GetMapping("/customer-care")
  public List<Map<String, Object>> cases(Authentication authentication) {
    return jdbc.queryForList("""
        SELECT c.id, c.member_id AS memberId, c.case_type AS caseType, c.subject, c.status,
               c.opened_at AS openedAt, m.mbr_no AS mbrNo, m.name
        FROM webv9_customer_care_cases c JOIN members m ON m.member_id = c.member_id
        ORDER BY c.opened_at DESC LIMIT 250
        """).stream().filter(row -> projectAccess.canAccessMember(authentication.getName(), ((Number) row.get("memberId")).longValue())).toList();
  }

  @GetMapping("/documents")
  public List<Map<String, Object>> documents(Authentication authentication) {
    return jdbc.queryForList("""
        SELECT d.id, d.payment_id AS paymentId, d.member_id AS memberId,
               d.document_type AS documentType, d.original_name AS originalName,
               d.document_status AS status, d.uploaded_at AS uploadedAt,
               m.mbr_no AS mbrNo, m.name
        FROM webv9_documents d LEFT JOIN members m ON m.member_id = d.member_id
        ORDER BY d.uploaded_at DESC LIMIT 250
        """).stream().filter(row -> {
          if (row.get("paymentId") instanceof Number paymentId)
            return projectAccess.canAccessPayment(authentication.getName(), paymentId.longValue());
          if (row.get("memberId") instanceof Number memberId)
            return projectAccess.canAccessMember(authentication.getName(), memberId.longValue());
          return projectAccess.isSuperAdmin(authentication.getName());
        }).toList();
  }

  private void validatePositionRights(String actor, Long positionId, boolean superAdmin) {
    if (superAdmin || positionId == null) return;
    List<Map<String, Object>> rights = jdbc.queryForList("""
        SELECT p.permission_code, p.module, p.action FROM webv9_position_permissions pp
        JOIN permissions p ON p.id=pp.permission_id WHERE pp.position_id=?
        """, positionId);
    for (Map<String, Object> right : rights) {
      if (!userPermissions.isGranted(actor, String.valueOf(right.get("module")),
          permissionAction(String.valueOf(right.get("permission_code")), String.valueOf(right.get("action")))))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "The selected job title includes action rights you do not hold");
    }
  }

  private boolean memberExists(Long memberId) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE member_id = ?", Integer.class, memberId);
    return count != null && count > 0;
  }

  private boolean isDepartmentManager(String username) {
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*)
        FROM users u JOIN webv9_positions p ON p.id = u.position_id
        WHERE u.username = ? AND u.is_active = 1 AND p.is_active = 1
          AND UPPER(p.position_code) = 'DEPARTMENT_MANAGER'
          AND (u.access_starts_at IS NULL OR u.access_starts_at <= CURRENT_TIMESTAMP)
          AND (u.access_ends_at IS NULL OR u.access_ends_at >= CURRENT_TIMESTAMP)
        """, Integer.class, username);
    return count != null && count > 0;
  }

  private String permissionAction(String permissionCode, String action) {
    return Set.of("RECEIPT_CREATE", "RECEIPT_VERIFY", "RECEIPT_POST", "RECEIPT_REVERSE")
        .contains(permissionCode) ? permissionCode : action;
  }

  private Long findUserId(String username) {
    Long userId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    if (userId == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user is not configured");
    }
    return userId;
  }

  private BigDecimal valueOrZero(String sql, Object... args) {
    try {
      BigDecimal total = jdbc.queryForObject(sql, BigDecimal.class, args);
      return total == null ? BigDecimal.ZERO : total;
    } catch (EmptyResultDataAccessException e) {
      return BigDecimal.ZERO;
    }
  }

  private void requireText(Map<String,Object> body, String key) { if (body.get(key) == null || String.valueOf(body.get(key)).isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required"); }
  private String nextCode(String prefix, String table, String column) {
    Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(CAST(SUBSTRING(" + column + "," + (prefix.length()+1) + ") AS UNSIGNED)),0)+1 FROM " + table + " WHERE " + column + " REGEXP '^" + prefix + "-[0-9]{5}$'", Integer.class);
    return String.format(prefix + "-%05d", next == null ? 1 : next);
  }
  private static void requirePasswordConfirmation(String password, String confirmation, boolean required) {
    if (password == null || password.isBlank()) {
      if (required || (confirmation != null && !confirmation.isBlank())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password and confirmation must both be supplied.");
      }
      return;
    }
    if (!password.equals(confirmation)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password and confirmation do not match.");
    }
  }
  private void audit(Authentication a, String action, String module, String details) { jdbc.update("INSERT INTO system_logs(username,action,module,details) VALUES(?,?,?,?)", a.getName(), action, module, details); }
}
