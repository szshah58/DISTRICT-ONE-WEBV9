package com.districtone.webv9;

import com.districtone.webv9.controller.ChartOfAccountsController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class ChartOfAccountsControllerTest {

  private JdbcTemplate jdbc;
  private ChartOfAccountsController controller;

  @BeforeEach
  void setUp() {
    jdbc = Mockito.mock(JdbcTemplate.class);
    controller = new ChartOfAccountsController(jdbc);
  }

  @Test
  void listAccountsReturnsJdbcResult() {
    when(jdbc.queryForList(anyString())).thenReturn(List.of(
        Map.of("id", 1L, "accountCode", "1000", "accountName", "Assets", "accountType", "ASSET")
    ));

    List<Map<String, Object>> result = controller.list();
    assertThat(result).hasSize(1);
    assertThat(result.get(0)).containsEntry("accountCode", "1000");
  }

  @Test
  void createAccountRejectsInvalidType() {
    ChartOfAccountsController.AccountRequest request = new ChartOfAccountsController.AccountRequest(
        "1000", "Assets", "INVALID_TYPE", null, "Desc", "DEBIT", 0, true
    );

    assertThatThrownBy(() -> controller.create(request, null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Invalid account type");
  }

  @Test
  void createAccountRejectsDuplicateCode() {
    when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM webv9_chart_accounts WHERE account_code = ?"), eq(Integer.class), eq("1000")))
        .thenReturn(1);

    ChartOfAccountsController.AccountRequest request = new ChartOfAccountsController.AccountRequest(
        "1000", "Assets", "ASSET", null, "Desc", "DEBIT", 0, true
    );

    assertThatThrownBy(() -> controller.create(request, null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("already exists");
  }
}
