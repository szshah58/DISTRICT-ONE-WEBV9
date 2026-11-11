package com.districtone.webv9.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlotInventoryServiceTest {
  private JdbcTemplate jdbc;
  private PlotInventoryService service;

  @BeforeEach
  void setUp() {
    jdbc = Mockito.mock(JdbcTemplate.class);
    service = new PlotInventoryService(jdbc);
  }

  @Test
  void createsNewPlotsWithGeneratedDistrictOneNumbersAndAvailableStatus() {
    when(jdbc.queryForObject(contains("ORDER BY block_id LIMIT 1 FOR UPDATE"), eq(Long.class))).thenReturn(1L);
    when(jdbc.queryForObject(contains("is_active = 1"), eq(Integer.class), eq(7L))).thenReturn(1);
    when(jdbc.queryForObject(contains("MAX(CAST(SUBSTRING(plot_no, 5)"), eq(Integer.class))).thenReturn(41);
    when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM plots WHERE plot_no = ?"), eq(Integer.class), eq("DOL-00042")))
        .thenReturn(0);
    when(jdbc.queryForObject(eq("SELECT LAST_INSERT_ID()"), eq(Long.class))).thenReturn(109L);

    Map<String, Object> result = service.create(new PlotInventoryRequest(
        7L, new BigDecimal("5.00"), "Residential", "Corner", null));

    assertThat(result).containsEntry("id", 109L)
        .containsEntry("plotNo", "DOL-00042")
        .containsEntry("status", "AVAILABLE")
        .containsEntry("availability", "AVAILABLE");
    verify(jdbc).update(contains("INSERT INTO plots"), eq(7L), eq("DOL-00042"),
        eq(new BigDecimal("5.00")), eq("Residential"), eq("Corner"));
  }

  @Test
  void skipsAnExistingGeneratedNumberBeforeInserting() {
    when(jdbc.queryForObject(contains("ORDER BY block_id LIMIT 1 FOR UPDATE"), eq(Long.class))).thenReturn(1L);
    when(jdbc.queryForObject(contains("is_active = 1"), eq(Integer.class), eq(7L))).thenReturn(1);
    when(jdbc.queryForObject(contains("MAX(CAST(SUBSTRING(plot_no, 5)"), eq(Integer.class))).thenReturn(6);
    when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM plots WHERE plot_no = ?"), eq(Integer.class), eq("DOL-00007")))
        .thenReturn(1);
    when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM plots WHERE plot_no = ?"), eq(Integer.class), eq("DOL-00008")))
        .thenReturn(0);
    when(jdbc.queryForObject(eq("SELECT LAST_INSERT_ID()"), eq(Long.class))).thenReturn(110L);

    Map<String, Object> result = service.create(new PlotInventoryRequest(
        7L, null, "Commercial", null, null));

    assertThat(result).containsEntry("plotNo", "DOL-00008");
    verify(jdbc).update(contains("INSERT INTO plots"), eq(7L), eq("DOL-00008"),
        isNull(), eq("Commercial"), isNull());
  }

  @Test
  void doesNotAllowCommittedPlotsToBeMadeAvailable() {
    when(jdbc.queryForMap(contains("FROM plots WHERE plot_id = ? FOR UPDATE"), eq(5L)))
        .thenReturn(Map.of("plot_id", 5L, "block_id", 7L, "plot_no", "DOL-00005"));
    when(jdbc.queryForObject(contains("is_active = 1"), eq(Integer.class), eq(7L))).thenReturn(1);
    when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM sales_bookings sb"), eq(Integer.class), eq(5L), eq(5L)))
        .thenReturn(1);
    when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM webv9_plot_merges"), eq(Integer.class), eq(5L), eq(5L)))
        .thenReturn(0);

    assertThatThrownBy(() -> service.update(5L, new PlotInventoryRequest(
        7L, new BigDecimal("5.00"), "Residential", "General", "AVAILABLE")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("committed or merged");
    verify(jdbc, never()).update(startsWith("UPDATE plots"), any(Object[].class));
  }

  @Test
  void updatesStatusAndInventoryDetailsWithoutChangingTheGeneratedNumber() {
    when(jdbc.queryForMap(contains("FROM plots WHERE plot_id = ? FOR UPDATE"), eq(5L)))
        .thenReturn(Map.of("plot_id", 5L, "block_id", 7L, "plot_no", "DOL-00005"));
    when(jdbc.queryForObject(contains("is_active = 1"), eq(Integer.class), eq(7L))).thenReturn(1);

    Map<String, Object> result = service.update(5L, new PlotInventoryRequest(
        7L, new BigDecimal("7.50"), "Commercial", "Boulevard", "SOLD"));

    assertThat(result).containsEntry("plotNo", "DOL-00005")
        .containsEntry("plotStatus", "SOLD")
        .containsEntry("availability", "UNAVAILABLE");
    verify(jdbc).update(contains("UPDATE plots"), eq(7L), eq(new BigDecimal("7.50")),
        eq("Commercial"), eq("Boulevard"), eq("SOLD"), eq(5L));
  }

  @Test
  void inventoryQueryExposesEffectiveStatusAndAvailability() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
    service.list("DOL-", "BOOKED", "COMMITTED");

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), any(Object[].class));
    assertThat(sql.getValue()).contains("AS availability", "AS plotStatus", "AS status",
        "latest_booking", "UNAVAILABLE", "COMMITTED", "CANCELLED", "NOT IN ('CANCELLED','MERGED')");
  }

  @Test
  void projectInventoryScopesExistingStatusQueryToAssignedBlocks() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());

    service.list(null, null, null, 17L);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForList(sql.capture(), params.capture());
    assertThat(sql.getValue()).contains("b.project_id = ?");
    assertThat(params.getValue()).containsExactly(17L);
  }
}
