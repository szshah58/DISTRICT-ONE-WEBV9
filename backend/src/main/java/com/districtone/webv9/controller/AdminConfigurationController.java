package com.districtone.webv9.controller;

import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/config")
public class AdminConfigurationController {
  private final JdbcTemplate jdbc;
  private final Set<String> kinds = Set.of("charges", "banks", "cash-sites", "plans", "surcharges", "settings");
  public AdminConfigurationController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @GetMapping("/{kind}")
  public List<Map<String,Object>> list(@PathVariable String kind) {
    return switch (kind) {
      case "charges" -> jdbc.queryForList("SELECT id, code, name, description, amount, calculation_method AS calculationMethod, is_active AS active FROM webv9_charge_types ORDER BY name");
      case "banks" -> jdbc.queryForList("SELECT id, bank_name AS bankName, account_title AS accountTitle, account_no AS accountNo, branch_name AS branch, bank_code AS bankCode, is_active AS active FROM webv9_banks ORDER BY bank_name");
      case "cash-sites" -> jdbc.queryForList("SELECT id, site_name AS siteName, location, contact_details AS contactDetails, is_active AS active FROM webv9_cash_sites ORDER BY site_name");
      case "plans" -> jdbc.queryForList("SELECT id, plan_code AS planCode, name, category, size_marla AS sizeMarla, number_of_instalments AS numberOfInstalments, frequency, advance_percentage AS advancePercentage, instalment_amount AS instalmentAmount, interval_days AS intervalDays, start_rule AS startRule, status = 'ACTIVE' AS active FROM webv9_instalment_plans ORDER BY name");
      case "surcharges" -> jdbc.queryForList("SELECT s.id, s.name, s.charge_type_id AS chargeTypeId, c.name AS chargeName, s.method, s.rate, s.fixed_amount AS fixedAmount, s.grace_days AS graceDays, s.effective_from AS effectiveFrom, s.is_active AS active FROM webv9_surcharge_rules s LEFT JOIN webv9_charge_types c ON c.id=s.charge_type_id ORDER BY s.name");
      case "settings" -> jdbc.queryForList("SELECT setting_key AS settingKey, setting_value AS settingValue, description, is_active AS active, updated_at AS updatedAt FROM webv9_system_settings ORDER BY setting_key");
      default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown configuration module");
    };
  }

  /** Existing schedules are booking-specific rows; plan configuration controls the
   * schedule parameters, while this read-only feed exposes schedules already generated. */
  @GetMapping("/plans/schedules")
  public List<Map<String,Object>> planSchedules() {
    return jdbc.queryForList("""
        SELECT s.id, s.booking_id AS bookingId,
               CONCAT('SB-', LPAD(sb.sale_booking_id, 6, '0')) AS bookingNo,
               m.mbr_no AS memberNo, m.name AS memberName,
               s.instalment_no AS instalmentNo, s.due_date AS dueDate, s.amount, s.status
        FROM webv9_instalment_schedules s
        JOIN sales_bookings sb ON sb.sale_booking_id = s.booking_id
        JOIN registrations r ON r.registration_id = sb.registration_id
        JOIN members m ON m.member_id = r.member_id
        ORDER BY s.due_date, s.booking_id, s.instalment_no
        LIMIT 1000
        """);
  }

  @GetMapping("/audit/log")
  public List<Map<String,Object>> audit() {
    return jdbc.queryForList("SELECT id, username, action, module, details, occurred_at AS occurredAt FROM system_logs ORDER BY occurred_at DESC LIMIT 500");
  }

  @PostMapping("/{kind}")
  @Transactional
  public Map<String,Object> create(@PathVariable String kind, @RequestBody Map<String,Object> body, Authentication auth) {
    require(kind);
    Map<String,Object> result = switch (kind) {
      case "charges" -> charge(body);
      case "banks" -> bank(body);
      case "cash-sites" -> cashSite(body);
      case "plans" -> plan(body);
      case "surcharges" -> surcharge(body);
      case "settings" -> setting(body);
      default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    };
    audit(auth, "CREATE", kind, String.valueOf(result.get("id")));
    return result;
  }

  @PatchMapping("/{kind}/{id}/status")
  @Transactional
  public Map<String,Object> status(@PathVariable String kind, @PathVariable String id, @RequestBody Map<String,Object> body, Authentication auth) {
    require(kind);
    boolean active = Boolean.parseBoolean(String.valueOf(body.getOrDefault("active", "false")));
    if ("settings".equals(kind)) {
      ensureSettingExists(id);
      jdbc.update("UPDATE webv9_system_settings SET is_active=? WHERE setting_key=?", active, id);
      audit(auth, active ? "ACTIVATE" : "DEACTIVATE", kind, id);
      return Map.of("updated", true, "settingKey", id, "active", active);
    }
    long numericId = numericId(id);
    ensureRecordExists(kind, numericId);
    int changed = switch (kind) {
      case "charges" -> jdbc.update("UPDATE webv9_charge_types SET is_active=? WHERE id=?", active, numericId);
      case "banks" -> jdbc.update("UPDATE webv9_banks SET is_active=? WHERE id=?", active, numericId);
      case "cash-sites" -> jdbc.update("UPDATE webv9_cash_sites SET is_active=? WHERE id=?", active, numericId);
      case "plans" -> jdbc.update("UPDATE webv9_instalment_plans SET status=? WHERE id=?", active ? "ACTIVE" : "INACTIVE", numericId);
      case "surcharges" -> jdbc.update("UPDATE webv9_surcharge_rules SET is_active=? WHERE id=?", active, numericId);
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This module does not support status changes");
    };
    audit(auth, active ? "ACTIVATE" : "DEACTIVATE", kind, String.valueOf(numericId));
    return Map.of("updated", true, "active", active);
  }

  @PutMapping("/{kind}/{id}")
  @Transactional
  public Map<String,Object> update(@PathVariable String kind, @PathVariable String id, @RequestBody Map<String,Object> b, Authentication auth) {
    require(kind);
    if ("settings".equals(kind)) {
      ensureSettingExists(id);
      requireText(b, "settingValue");
      jdbc.update("UPDATE webv9_system_settings SET setting_value=?,description=?,is_active=? WHERE setting_key=?",
          b.get("settingValue"), b.get("description"), b.getOrDefault("active", true), id);
      audit(auth, "UPDATE", kind, id);
      return Map.of("updated", true, "settingKey", id);
    }
    long numericId = numericId(id);
    ensureRecordExists(kind, numericId);
    int changed = switch (kind) {
      case "charges" -> jdbc.update("UPDATE webv9_charge_types SET code=?,name=?,description=?,amount=?,calculation_method=? WHERE id=?", b.get("code"),b.get("name"),b.get("description"),b.get("amount"),b.getOrDefault("calculationMethod","FIXED"),numericId);
      case "banks" -> jdbc.update("UPDATE webv9_banks SET bank_name=?,account_title=?,account_no=?,branch_name=?,bank_code=? WHERE id=?", b.get("bankName"),b.get("accountTitle"),b.get("accountNo"),b.get("branch"),b.get("bankCode"),numericId);
      case "cash-sites" -> jdbc.update("UPDATE webv9_cash_sites SET site_name=?,location=?,contact_details=? WHERE id=?", b.get("siteName"),b.get("location"),b.get("contactDetails"),numericId);
      case "plans" -> jdbc.update("UPDATE webv9_instalment_plans SET name=?,category=?,size_marla=?,number_of_instalments=?,frequency=?,advance_percentage=?,instalment_amount=?,interval_days=?,start_rule=? WHERE id=?", b.get("name"),b.get("category"),b.get("sizeMarla"),b.get("numberOfInstalments"),b.get("frequency"),b.get("advancePercentage"),b.get("instalmentAmount"),b.get("intervalDays"),b.get("startRule"),numericId);
      case "surcharges" -> jdbc.update("UPDATE webv9_surcharge_rules SET name=?,charge_type_id=?,method=?,rate=?,fixed_amount=?,grace_days=?,effective_from=? WHERE id=?", b.get("name"),b.get("chargeTypeId"),b.get("method"),b.get("rate"),b.get("fixedAmount"),b.get("graceDays"),b.get("effectiveFrom"),numericId);
      default -> 0;
    };
    audit(auth, "UPDATE", kind, String.valueOf(numericId));
    return Map.of("updated", true, "id", numericId);
  }

  private Map<String,Object> charge(Map<String,Object> b) {
    requireText(b, "code"); requireText(b, "name");
    jdbc.update("INSERT INTO webv9_charge_types(code,name,description,amount,calculation_method,is_active) VALUES(?,?,?,?,?,?)",
        b.get("code"), b.get("name"), b.get("description"), b.get("amount"), b.getOrDefault("calculationMethod","FIXED"), b.getOrDefault("active",true));
    return id("webv9_charge_types");
  }
  private Map<String,Object> bank(Map<String,Object> b) {
    requireText(b, "bankName");
    jdbc.update("INSERT INTO webv9_banks(bank_name,account_title,account_no,branch_name,bank_code,is_active) VALUES(?,?,?,?,?,?)",
        b.get("bankName"), b.get("accountTitle"), b.get("accountNo"), b.get("branch"), b.get("bankCode"), b.getOrDefault("active",true));
    return id("webv9_banks");
  }
  private Map<String,Object> cashSite(Map<String,Object> b) {
    requireText(b, "siteName");
    jdbc.update("INSERT INTO webv9_cash_sites(site_name,location,contact_details,is_active) VALUES(?,?,?,?)",
        b.get("siteName"), b.get("location"), b.get("contactDetails"), b.getOrDefault("active",true));
    return id("webv9_cash_sites");
  }
  private Map<String,Object> plan(Map<String,Object> b) {
    requireText(b, "name"); requireText(b, "frequency");
    Integer nextNumber = jdbc.queryForObject(
        "SELECT COALESCE(MAX(CAST(SUBSTRING(plan_code, 4) AS UNSIGNED)), 0) + 1 FROM webv9_instalment_plans WHERE plan_code REGEXP '^IP-[0-9]{5}$'",
        Integer.class);
    String planCode = String.format("IP-%05d", nextNumber == null ? 1 : nextNumber);
    jdbc.update("INSERT INTO webv9_instalment_plans(plan_code,name,category,size_marla,number_of_instalments,frequency,advance_percentage,instalment_amount,interval_days,start_rule,status) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        planCode, b.get("name"), b.get("category"), b.get("sizeMarla"), b.get("numberOfInstalments"), b.get("frequency"), b.get("advancePercentage"), b.get("instalmentAmount"), b.get("intervalDays"), b.get("startRule"), Boolean.parseBoolean(String.valueOf(b.getOrDefault("active",true))) ? "ACTIVE" : "INACTIVE");
    Map<String,Object> result = new LinkedHashMap<>(id("webv9_instalment_plans"));
    result.put("planCode", planCode);
    return result;
  }
  private Map<String,Object> surcharge(Map<String,Object> b) {
    requireText(b, "name"); requireText(b, "method");
    jdbc.update("INSERT INTO webv9_surcharge_rules(charge_type_id,name,grace_days,method,rate,fixed_amount,effective_from,is_active) VALUES(?,?,?,?,?,?,?,?)",
        b.get("chargeTypeId"), b.get("name"), b.getOrDefault("graceDays",0), b.get("method"), b.get("rate"), b.get("fixedAmount"), b.get("effectiveFrom"), b.getOrDefault("active",true));
    return id("webv9_surcharge_rules");
  }
  private Map<String,Object> setting(Map<String,Object> b) {
    requireText(b, "settingKey"); requireText(b, "settingValue");
    jdbc.update("INSERT INTO webv9_system_settings(setting_key,setting_value,description,is_active) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value), description=VALUES(description), is_active=VALUES(is_active)",
        b.get("settingKey"), b.get("settingValue"), b.get("description"), b.getOrDefault("active",true));
    return Map.of("settingKey", b.get("settingKey"), "updated", true);
  }
  private Map<String,Object> id(String table) { return Map.of("id", jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class), "created", true); }
  private long numericId(String value) {
    try { return Long.parseLong(value); }
    catch (NumberFormatException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configuration record id must be numeric"); }
  }
  private void ensureSettingExists(String key) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM webv9_system_settings WHERE setting_key=?", Integer.class, key);
    if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "System setting not found");
  }
  private void ensureRecordExists(String kind, long id) {
    String table = switch (kind) {
      case "charges" -> "webv9_charge_types";
      case "banks" -> "webv9_banks";
      case "cash-sites" -> "webv9_cash_sites";
      case "plans" -> "webv9_instalment_plans";
      case "surcharges" -> "webv9_surcharge_rules";
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This module does not support record ids");
    };
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=?", Integer.class, id);
    if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Configuration record not found");
  }
  private void require(String kind) { if (!kinds.contains(kind)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown configuration module"); }
  private void requireText(Map<String,Object> b, String key) { if (b.get(key) == null || String.valueOf(b.get(key)).isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required"); }
  private void audit(Authentication a, String action, String module, String details) { jdbc.update("INSERT INTO system_logs(username,action,module,details) VALUES(?,?,?,?)", a.getName(), action, "ADMIN_" + module.toUpperCase(), details); }
}
