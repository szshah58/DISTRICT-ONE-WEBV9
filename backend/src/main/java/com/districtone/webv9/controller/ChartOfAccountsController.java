package com.districtone.webv9.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/v1/finance/accounts")
public class ChartOfAccountsController {

  private static final Set<String> ALLOWED_ACCOUNT_TYPES = Set.of(
      "ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE", "RECEIVABLE", "CASH", "BANK"
  );

  private final JdbcTemplate jdbc;

  public ChartOfAccountsController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record AccountRequest(
      String accountCode,
      @NotBlank(message = "Account name is required") String accountName,
      @NotBlank(message = "Account type is required") String accountType,
      Long parentId,
      String description,
      String normalBalance,
      Integer displayOrder,
      Boolean active
  ) {}

  @GetMapping
  public List<Map<String, Object>> list() {
    return jdbc.queryForList("""
        SELECT a.id, a.account_code AS accountCode, a.account_name AS accountName,
               a.account_type AS accountType, a.parent_id AS parentId,
               p.account_code AS parentCode, p.account_name AS parentName,
               a.description, a.normal_balance AS normalBalance,
               a.display_order AS displayOrder, a.is_active AS active
        FROM webv9_chart_accounts a
        LEFT JOIN webv9_chart_accounts p ON p.id = a.parent_id
        ORDER BY a.account_code, a.display_order, a.account_name
        """);
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable Long id) {
    try {
      return jdbc.queryForMap("""
          SELECT a.id, a.account_code AS accountCode, a.account_name AS accountName,
                 a.account_type AS accountType, a.parent_id AS parentId,
                 p.account_code AS parentCode, p.account_name AS parentName,
                 a.description, a.normal_balance AS normalBalance,
                 a.display_order AS displayOrder, a.is_active AS active
          FROM webv9_chart_accounts a
          LEFT JOIN webv9_chart_accounts p ON p.id = a.parent_id
          WHERE a.id = ?
          """, id);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
    }
  }

  @PostMapping
  @Transactional
  public Map<String, Object> create(@Valid @RequestBody AccountRequest req, Authentication auth) {
    String type = validateAndFormatType(req.accountType());

    String code = req.accountCode() != null ? req.accountCode().trim() : "";
    if (code.isBlank()) {
      code = autoGenerateAccountCode(type);
    } else {
      if (accountCodeExists(code, null)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Account code '" + code + "' already exists");
      }
    }

    if (req.parentId() != null) {
      ensureAccountExists(req.parentId(), "Parent account not found");
    }

    String balance = resolveNormalBalance(req.normalBalance(), type);
    int order = req.displayOrder() != null ? req.displayOrder() : 0;
    boolean active = req.active() != null ? req.active() : true;

    jdbc.update("""
        INSERT INTO webv9_chart_accounts
          (account_code, account_name, account_type, parent_id, description, normal_balance, display_order, is_active)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, code, req.accountName().trim(), type, req.parentId(),
        blankToNull(req.description()), balance, order, active);

    Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    audit(auth, "CREATE", "FINANCE_ACCOUNT", "Created account Code=" + code + ", Name=" + req.accountName().trim());

    return get(id);
  }

  @PutMapping("/{id}")
  @Transactional
  public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody AccountRequest req, Authentication auth) {
    ensureAccountExists(id, "Account not found");
    String type = validateAndFormatType(req.accountType());

    String code = req.accountCode() != null ? req.accountCode().trim() : "";
    if (code.isBlank()) {
      code = autoGenerateAccountCode(type);
    } else {
      if (accountCodeExists(code, id)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Account code '" + code + "' is used by another account");
      }
    }

    if (req.parentId() != null) {
      if (req.parentId().equals(id)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An account cannot be its own parent");
      }
      ensureAccountExists(req.parentId(), "Parent account not found");
      if (isDescendantOf(req.parentId(), id)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Circular parent relationship detected");
      }
    }

    String balance = resolveNormalBalance(req.normalBalance(), type);
    int order = req.displayOrder() != null ? req.displayOrder() : 0;
    boolean active = req.active() != null ? req.active() : true;

    jdbc.update("""
        UPDATE webv9_chart_accounts
        SET account_code = ?, account_name = ?, account_type = ?, parent_id = ?,
            description = ?, normal_balance = ?, display_order = ?, is_active = ?
        WHERE id = ?
        """, code, req.accountName().trim(), type, req.parentId(),
        blankToNull(req.description()), balance, order, active, id);

    audit(auth, "UPDATE", "FINANCE_ACCOUNT", "Updated account ID=" + id + ", Code=" + code);
    return get(id);
  }

  @PatchMapping("/{id}/status")
  @Transactional
  public Map<String, Object> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
    ensureAccountExists(id, "Account not found");
    boolean active = Boolean.parseBoolean(String.valueOf(body.getOrDefault("active", "false")));

    if (!active) {
      Integer activeChildren = jdbc.queryForObject(
          "SELECT COUNT(*) FROM webv9_chart_accounts WHERE parent_id = ? AND is_active = true",
          Integer.class, id);
      if (activeChildren != null && activeChildren > 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Cannot deactivate an account that has active child accounts. Please deactivate child accounts first.");
      }
    }

    jdbc.update("UPDATE webv9_chart_accounts SET is_active = ? WHERE id = ?", active, id);
    audit(auth, active ? "ACTIVATE" : "DEACTIVATE", "FINANCE_ACCOUNT", "Account ID=" + id + " active=" + active);

    return Map.of("id", id, "active", active, "updated", true);
  }

  @DeleteMapping("/{id}")
  @Transactional
  public Map<String, Object> delete(@PathVariable Long id, Authentication auth) {
    ensureAccountExists(id, "Account not found");

    Integer childCount = jdbc.queryForObject(
        "SELECT COUNT(*) FROM webv9_chart_accounts WHERE parent_id = ?",
        Integer.class, id);
    if (childCount != null && childCount > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Cannot delete account because it has child accounts. Set status to inactive instead.");
    }

    Integer paymentRefs = jdbc.queryForObject(
        "SELECT COUNT(*) FROM payments_receipts WHERE charge_type_id = ? OR bank_id = ? OR cash_site_id = ?",
        Integer.class, id, id, id);
    Integer receivableRefs = jdbc.queryForObject(
        "SELECT COUNT(*) FROM webv9_receivables WHERE charge_type_id = ?",
        Integer.class, id);

    if ((paymentRefs != null && paymentRefs > 0) || (receivableRefs != null && receivableRefs > 0)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Cannot delete account because it is referenced by financial transactions. Set status to inactive instead.");
    }

    jdbc.update("DELETE FROM webv9_chart_accounts WHERE id = ?", id);
    audit(auth, "DELETE", "FINANCE_ACCOUNT", "Deleted account ID=" + id);

    return Map.of("id", id, "deleted", true);
  }

  private String validateAndFormatType(String type) {
    if (type == null || type.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account type is required");
    }
    String upper = type.trim().toUpperCase();
    if (!ALLOWED_ACCOUNT_TYPES.contains(upper)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Invalid account type. Allowed types: " + ALLOWED_ACCOUNT_TYPES);
    }
    return upper;
  }

  private String resolveNormalBalance(String provided, String type) {
    if (provided != null && !provided.isBlank()) {
      String upper = provided.trim().toUpperCase();
      if ("DEBIT".equals(upper) || "CREDIT".equals(upper)) {
        return upper;
      }
    }
    return switch (type) {
      case "ASSET", "EXPENSE", "RECEIVABLE", "CASH", "BANK" -> "DEBIT";
      case "LIABILITY", "EQUITY", "INCOME" -> "CREDIT";
      default -> "DEBIT";
    };
  }

  private boolean accountCodeExists(String code, Long excludeId) {
    if (excludeId == null) {
      Integer count = jdbc.queryForObject(
          "SELECT COUNT(*) FROM webv9_chart_accounts WHERE account_code = ?",
          Integer.class, code);
      return count != null && count > 0;
    } else {
      Integer count = jdbc.queryForObject(
          "SELECT COUNT(*) FROM webv9_chart_accounts WHERE account_code = ? AND id != ?",
          Integer.class, code, excludeId);
      return count != null && count > 0;
    }
  }

  private String autoGenerateAccountCode(String type) {
    String prefix = switch (type) {
      case "ASSET", "CASH", "BANK", "RECEIVABLE" -> "1";
      case "LIABILITY" -> "2";
      case "EQUITY" -> "3";
      case "INCOME" -> "4";
      case "EXPENSE" -> "5";
      default -> "9";
    };
    Integer max = jdbc.queryForObject(
        "SELECT COALESCE(MAX(CAST(account_code AS UNSIGNED)), 0) FROM webv9_chart_accounts WHERE account_code REGEXP '^[0-9]+$'",
        Integer.class);
    int next = (max == null || max == 0) ? Integer.parseInt(prefix + "000") + 10 : max + 10;
    return String.valueOf(next);
  }

  private void ensureAccountExists(Long id, String errorMsg) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM webv9_chart_accounts WHERE id = ?",
        Integer.class, id);
    if (count == null || count == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, errorMsg);
    }
  }

  private boolean isDescendantOf(Long candidateParentId, Long ancestorId) {
    Long current = candidateParentId;
    while (current != null) {
      if (current.equals(ancestorId)) {
        return true;
      }
      try {
        current = jdbc.queryForObject(
            "SELECT parent_id FROM webv9_chart_accounts WHERE id = ?",
            Long.class, current);
      } catch (EmptyResultDataAccessException e) {
        break;
      }
    }
    return false;
  }

  private String blankToNull(String val) {
    return val == null || val.isBlank() ? null : val.trim();
  }

  private void audit(Authentication auth, String action, String module, String details) {
    String username = auth != null ? auth.getName() : "SYSTEM";
    jdbc.update("INSERT INTO system_logs(username, action, module, details) VALUES (?, ?, ?, ?)",
        username, action, module, details);
  }
}
