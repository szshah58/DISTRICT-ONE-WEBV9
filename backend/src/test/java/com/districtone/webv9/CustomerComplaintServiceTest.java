package com.districtone.webv9;

import com.districtone.webv9.service.ComplaintApiDtos.ComplaintDetail;
import com.districtone.webv9.service.ComplaintApiDtos.AddComplaintHistoryRequest;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintHistoryCreated;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintPage;
import com.districtone.webv9.service.ComplaintApiDtos.CreateComplaintRequest;
import com.districtone.webv9.service.CustomerComplaintService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerComplaintServiceTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final CustomerComplaintService service = new CustomerComplaintService(jdbc);

  @Test
  void complaintListReturnsTypedPageAndHasNoClientMemberScope() {
    LocalDateTime opened = LocalDateTime.of(2026, 10, 3, 11, 0);
    when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.queryForList(contains("LIMIT ? OFFSET ?"), any(Object[].class)))
        .thenReturn(List.of(Map.of(
            "complaintId", 41L, "subject", "Road concern", "status", "OPEN",
            "createdAt", Timestamp.valueOf(opened), "memberId", 7L, "memberNo", "MBR-7",
            "memberName", "Example Customer", "contactNo", "03000000000", "historyCount", 2L)));

    ComplaintPage result = service.list(null, 0, 25);

    assertThat(result.page()).isZero();
    assertThat(result.pageSize()).isEqualTo(25);
    assertThat(result.totalElements()).isEqualTo(1);
    assertThat(result.items()).singleElement().satisfies(item -> {
      assertThat(item.complaintId()).isEqualTo(41L);
      assertThat(item.customer().memberId()).isEqualTo(7L);
      assertThat(item.status()).isEqualTo("OPEN");
      assertThat(item.historyCount()).isEqualTo(2);
    });
    verify(jdbc).queryForList(argThat(sql -> sql.contains("c.case_type = 'COMPLAINT'")
        && !sql.contains("member_id = ?")), any(Object[].class));
  }

  @Test
  void complaintDetailDerivesCustomerAndSalesReferencesFromTheComplaintRecord() {
    LocalDateTime opened = LocalDateTime.of(2026, 10, 3, 11, 0);
    when(jdbc.queryForMap(contains("WHERE c.id = ? AND c.case_type = 'COMPLAINT'"), eq(41L)))
        .thenReturn(Map.of("complaintId", 41L, "subject", "Road concern", "status", "OPEN",
            "createdAt", Timestamp.valueOf(opened), "memberId", 7L, "memberNo", "MBR-7",
            "memberName", "Example Customer", "contactNo", "03000000000"));
    when(jdbc.queryForList(contains("FROM registrations r"), eq(7L)))
        .thenReturn(List.of(Map.of("registrationId", 70L, "registrationNo", "REG-70",
            "bookingId", 71L, "bookingStatus", "RUNNING", "plotId", 72L,
            "plotNo", "DOL-00072", "blockName", "A", "registrationStatus", "ACTIVE",
            "plotStatus", "BOOKED")));
    when(jdbc.queryForList(contains("FROM webv9_case_notes n"), eq(41L)))
        .thenReturn(List.of(Map.of("historyId", 8L, "note", "Called customer",
            "createdAt", Timestamp.valueOf(opened), "recordedBy", "care-user")));

    ComplaintDetail result = service.get(41L);

    assertThat(result.complaintId()).isEqualTo(41L);
    assertThat(result.customer().memberId()).isEqualTo(7L);
    assertThat(result.saleReferences()).singleElement().satisfies(sale -> {
      assertThat(sale.registrationId()).isEqualTo(70L);
      assertThat(sale.bookingId()).isEqualTo(71L);
      assertThat(sale.plotNo()).isEqualTo("DOL-00072");
    });
    assertThat(result.history()).singleElement().satisfies(entry ->
        assertThat(entry.recordedBy()).isEqualTo("care-user"));
    verify(jdbc, never()).queryForMap(contains("payments_receipts"), any(Object[].class));
    verify(jdbc, never()).queryForList(contains("webv9_customer_ledger"), any(Object[].class));
  }

  @Test
  void invalidCustomerReferenceCannotCreateComplaint() {
    when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM members WHERE member_id = ?"),
        eq(Integer.class), eq(999L))).thenReturn(0);

    assertThatThrownBy(() -> service.create(new CreateComplaintRequest(999L, "Concern", "Details"), null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));

    verify(jdbc, never()).update(contains("INSERT INTO webv9_customer_care_cases"), any(Object[].class));
  }

  @Test
  void createReturnsStableIdentifierAndSchemaStatusAndTimestamp() {
    LocalDateTime opened = LocalDateTime.of(2026, 10, 3, 11, 0);
    Authentication auth = new UsernamePasswordAuthenticationToken("care-user", "");
    when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM members WHERE member_id = ?"),
        eq(Integer.class), eq(7L))).thenReturn(1);
    doAnswer(invocation -> {
      KeyHolder keys = invocation.getArgument(1);
      keys.getKeyList().add(Map.of("id", 41L));
      return 1;
    }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    when(jdbc.queryForMap(contains("WHERE c.id = ? AND c.case_type = 'COMPLAINT'"), eq(41L)))
        .thenReturn(Map.of("complaintId", 41L, "status", "OPEN", "createdAt", Timestamp.valueOf(opened)));

    var result = service.create(new CreateComplaintRequest(7L, "  Road concern  ", "  Details  "), auth);

    assertThat(result.complaintId()).isEqualTo(41L);
    assertThat(result.status()).isEqualTo("OPEN");
    assertThat(result.createdAt()).isEqualTo(opened);
    verify(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    verify(jdbc).update(contains("INSERT INTO system_logs"), eq("care-user"),
        eq("CREATE_COMPLAINT"), contains("41"));
  }

  @Test
  void historyNoteUsesTheExistingCaseNoteRelationAndRecordsAuthor() {
    LocalDateTime created = LocalDateTime.of(2026, 10, 3, 12, 0);
    Authentication auth = new UsernamePasswordAuthenticationToken("care-user", "");
    when(jdbc.queryForObject(contains("case_type = 'COMPLAINT'"), eq(Integer.class), eq(41L))).thenReturn(1);
    when(jdbc.queryForObject(eq("SELECT id FROM users WHERE username = ? AND is_active = 1"),
        eq(Long.class), eq("care-user"))).thenReturn(9L);
    when(jdbc.queryForObject(eq("SELECT LAST_INSERT_ID()"), eq(Long.class))).thenReturn(81L);
    when(jdbc.queryForObject(eq("SELECT created_at FROM webv9_case_notes WHERE id = ?"),
        eq(Timestamp.class), eq(81L))).thenReturn(Timestamp.valueOf(created));

    ComplaintHistoryCreated result = service.addHistory(41L,
        new AddComplaintHistoryRequest("  Contacted customer  "), auth);

    assertThat(result.historyId()).isEqualTo(81L);
    assertThat(result.complaintId()).isEqualTo(41L);
    assertThat(result.createdAt()).isEqualTo(created);
    verify(jdbc).update("INSERT INTO webv9_case_notes(case_id,note,created_by) VALUES(?,?,?)",
        41L, "Contacted customer", 9L);
  }

  @Test
  void invalidComplaintReferenceReturnsNotFound() {
    when(jdbc.queryForMap(contains("WHERE c.id = ? AND c.case_type = 'COMPLAINT'"), eq(999L)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertThatThrownBy(() -> service.get(999L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));

    verify(jdbc).queryForMap(contains("WHERE c.id = ? AND c.case_type = 'COMPLAINT'"), eq(999L));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void invalidPageParametersAreRejected() {
    assertThatThrownBy(() -> service.list(null, -1, 25))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
    assertThatThrownBy(() -> service.list(null, 0, 101))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
    verifyNoInteractions(jdbc);
  }
}
