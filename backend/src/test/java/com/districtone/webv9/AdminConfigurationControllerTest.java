package com.districtone.webv9;

import com.districtone.webv9.controller.AdminConfigurationController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminConfigurationControllerTest {
  private JdbcTemplate jdbc;
  private AdminConfigurationController controller;

  @BeforeEach
  void setUp() {
    jdbc = Mockito.mock(JdbcTemplate.class);
    controller = new AdminConfigurationController(jdbc);
  }

  @Test
  void updatesSystemSettingByItsStringKey() {
    when(jdbc.queryForObject("SELECT COUNT(*) FROM webv9_system_settings WHERE setting_key=?", Integer.class, "ui.theme"))
        .thenReturn(1);
    Authentication authentication = Mockito.mock(Authentication.class);
    when(authentication.getName()).thenReturn("config-admin");

    Map<String, Object> result = controller.update("settings", "ui.theme",
        Map.of("settingValue", "dark", "active", true), authentication);

    assertThat(result).containsEntry("updated", true).containsEntry("settingKey", "ui.theme");
    verify(jdbc).update("UPDATE webv9_system_settings SET setting_value=?,description=?,is_active=? WHERE setting_key=?",
        "dark", null, true, "ui.theme");
    verify(jdbc).update("INSERT INTO system_logs(username,action,module,details) VALUES(?,?,?,?)",
        "config-admin", "UPDATE", "ADMIN_SETTINGS", "ui.theme");
  }

  @Test
  void returnsNotFoundForUnknownSettingWithoutUpdating() {
    when(jdbc.queryForObject("SELECT COUNT(*) FROM webv9_system_settings WHERE setting_key=?", Integer.class, "missing.key"))
        .thenReturn(0);

    assertThatThrownBy(() -> controller.update("settings", "missing.key",
        Map.of("settingValue", "value"), null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("System setting not found");
    verify(jdbc, Mockito.never()).update(startsWith("UPDATE webv9_system_settings"), any(Object[].class));
  }

  @Test
  void exposesExistingBookingSchedulesReadOnly() {
    List<Map<String, Object>> schedules = List.of(Map.of("id", 1L, "bookingNo", "SB-000001"));
    when(jdbc.queryForList(contains("FROM webv9_instalment_schedules"))).thenReturn(schedules);

    assertThat(controller.planSchedules()).isSameAs(schedules);
    verify(jdbc).queryForList(contains("ORDER BY s.due_date"));
  }
}
