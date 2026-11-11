package com.districtone.webv9.service;

import com.districtone.webv9.service.ComplaintApiDtos.AddComplaintHistoryRequest;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintCreated;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintDetail;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintHistoryCreated;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintHistoryEntry;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintListItem;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintPage;
import com.districtone.webv9.service.ComplaintApiDtos.CreateComplaintRequest;
import com.districtone.webv9.service.ComplaintApiDtos.CustomerReference;
import com.districtone.webv9.service.ComplaintApiDtos.SaleReference;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CustomerComplaintService {
  private static final int MAX_PAGE_SIZE = 100;
  private final JdbcTemplate jdbc;

  public CustomerComplaintService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public ComplaintPage list(String query, int page, int pageSize) {
    validatePage(page, pageSize);
    StringBuilder where = new StringBuilder(" WHERE c.case_type = 'COMPLAINT' ");
    List<Object> args = new ArrayList<>();
    if (query != null && !query.isBlank()) {
      String term = "%" + escapeLike(query.trim()) + "%";
      where.append("""
           AND (c.subject LIKE ? OR c.description LIKE ? OR m.mbr_no LIKE ?
                OR m.name LIKE ? OR m.contact1 LIKE ? OR CAST(c.id AS CHAR) LIKE ?)
          """);
      for (int i = 0; i < 6; i++) args.add(term);
    }

    Long total = jdbc.queryForObject("""
        SELECT COUNT(*) FROM webv9_customer_care_cases c
        JOIN members m ON m.member_id = c.member_id
        """ + where, Long.class, args.toArray());
    if (total == null) total = 0L;

    args.add(pageSize);
    args.add((long) page * pageSize);
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT c.id AS complaintId, c.subject, c.description, c.status,
               c.opened_at AS createdAt, m.member_id AS memberId, m.mbr_no AS memberNo,
               m.name AS memberName, m.contact1 AS contactNo,
               (SELECT COUNT(*) FROM webv9_case_notes n WHERE n.case_id = c.id) AS historyCount
        FROM webv9_customer_care_cases c
        JOIN members m ON m.member_id = c.member_id
        """ + where + " ORDER BY c.opened_at DESC, c.id DESC LIMIT ? OFFSET ?", args.toArray());
    List<ComplaintListItem> items = rows.stream().map(row -> new ComplaintListItem(
        longValue(row.get("complaintId")), stringValue(row.get("subject")),
        stringValue(row.get("description")), stringValue(row.get("status")),
        dateTimeValue(row.get("createdAt")), customer(row), intValue(row.get("historyCount")))).toList();
    return new ComplaintPage(items, page, pageSize, total);
  }

  @Transactional(readOnly = true)
  public ComplaintDetail get(Long complaintId) {
    Map<String, Object> row = findComplaint(complaintId);
    Long memberId = longValue(row.get("memberId"));
    List<SaleReference> saleReferences = jdbc.queryForList("""
        SELECT r.registration_id AS registrationId, r.reg_no AS registrationNo,
               r.form_no AS formNo, r.status AS registrationStatus,
               sb.sale_booking_id AS bookingId, sb.sale_status AS bookingStatus,
               p.plot_id AS plotId, p.plot_no AS plotNo, b.block_name AS blockName,
               p.status AS plotStatus
        FROM registrations r
        LEFT JOIN sales_bookings sb ON sb.registration_id = r.registration_id
        LEFT JOIN plots p ON p.plot_id = sb.plot_id
        LEFT JOIN blocks b ON b.block_id = p.block_id
        WHERE r.member_id = ?
        ORDER BY r.registration_id DESC, sb.sale_booking_id DESC
        """, memberId).stream().map(sale -> new SaleReference(
        longValue(sale.get("registrationId")), stringValue(sale.get("registrationNo")),
        stringValue(sale.get("formNo")), stringValue(sale.get("registrationStatus")),
        longValue(sale.get("bookingId")), stringValue(sale.get("bookingStatus")),
        longValue(sale.get("plotId")), stringValue(sale.get("plotNo")),
        stringValue(sale.get("blockName")), stringValue(sale.get("plotStatus")))).toList();
    return new ComplaintDetail(longValue(row.get("complaintId")), stringValue(row.get("subject")),
        stringValue(row.get("description")), stringValue(row.get("status")),
        dateTimeValue(row.get("createdAt")), customer(row), saleReferences, historyRows(complaintId));
  }

  @Transactional(readOnly = true)
  public List<ComplaintHistoryEntry> history(Long complaintId) {
    ensureComplaintExists(complaintId);
    return historyRows(complaintId);
  }

  @Transactional
  public ComplaintCreated create(CreateComplaintRequest request, Authentication authentication) {
    if (request == null || request.memberId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer/member is required.");
    }
    String subject = request.subject() == null ? "" : request.subject().trim();
    if (subject.isEmpty() || subject.length() > 255) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Complaint subject is required and must be 255 characters or fewer.");
    }
    ensureMemberExists(request.memberId());

    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO webv9_customer_care_cases
            (member_id, case_type, subject, description, status, assigned_to)
          VALUES (?, 'COMPLAINT', ?, ?, 'OPEN', NULL)
          """, new String[] {"id"});
      statement.setLong(1, request.memberId());
      statement.setString(2, subject);
      statement.setString(3, blankToNull(request.description()));
      return statement;
    }, keys);
    Number generatedId = keys.getKey();
    if (generatedId == null) throw new IllegalStateException("Could not retrieve the new complaint ID.");
    Long complaintId = generatedId.longValue();
    audit(authentication, "CREATE_COMPLAINT", "Created complaint case " + complaintId);
    Map<String, Object> created = findComplaint(complaintId);
    return new ComplaintCreated(complaintId, stringValue(created.get("status")),
        dateTimeValue(created.get("createdAt")));
  }

  @Transactional
  public ComplaintHistoryCreated addHistory(Long complaintId, AddComplaintHistoryRequest request,
                                             Authentication authentication) {
    if (request == null || request.note() == null || request.note().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Complaint history note is required.");
    }
    ensureComplaintExists(complaintId);
    jdbc.update("INSERT INTO webv9_case_notes(case_id,note,created_by) VALUES(?,?,?)",
        complaintId, request.note().trim(), userId(authentication));
    Long historyId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    if (historyId == null) throw new IllegalStateException("Could not retrieve the new complaint history ID.");
    Timestamp createdAt = jdbc.queryForObject(
        "SELECT created_at FROM webv9_case_notes WHERE id = ?", Timestamp.class, historyId);
    audit(authentication, "ADD_COMPLAINT_NOTE", "Added history note to complaint case " + complaintId);
    return new ComplaintHistoryCreated(historyId, complaintId, dateTimeValue(createdAt));
  }

  private Map<String, Object> findComplaint(Long complaintId) {
    try {
      return jdbc.queryForMap("""
          SELECT c.id AS complaintId, c.subject, c.description, c.status,
                 c.opened_at AS createdAt, m.member_id AS memberId, m.mbr_no AS memberNo,
                 m.name AS memberName, m.contact1 AS contactNo
          FROM webv9_customer_care_cases c
          JOIN members m ON m.member_id = c.member_id
          WHERE c.id = ? AND c.case_type = 'COMPLAINT'
          """, complaintId);
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Complaint not found.");
    }
  }

  private void ensureComplaintExists(Long complaintId) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM webv9_customer_care_cases WHERE id = ? AND case_type = 'COMPLAINT'",
        Integer.class, complaintId);
    if (count == null || count == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Complaint not found.");
    }
  }

  private void ensureMemberExists(Long memberId) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE member_id = ?",
        Integer.class, memberId);
    if (count == null || count == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer/member not found.");
    }
  }

  private List<ComplaintHistoryEntry> historyRows(Long complaintId) {
    return jdbc.queryForList("""
        SELECT n.id AS historyId, n.note, n.created_at AS createdAt, u.username AS recordedBy
        FROM webv9_case_notes n
        LEFT JOIN users u ON u.id = n.created_by
        WHERE n.case_id = ?
        ORDER BY n.created_at, n.id
        """, complaintId).stream().map(row -> new ComplaintHistoryEntry(
        longValue(row.get("historyId")), stringValue(row.get("note")),
        dateTimeValue(row.get("createdAt")), stringValue(row.get("recordedBy")))).toList();
  }

  private Long userId(Authentication authentication) {
    if (authentication == null || authentication.getName() == null) return null;
    try {
      return jdbc.queryForObject("SELECT id FROM users WHERE username = ? AND is_active = 1",
          Long.class, authentication.getName());
    } catch (EmptyResultDataAccessException ex) {
      return null;
    }
  }

  private void audit(Authentication authentication, String action, String details) {
    jdbc.update("INSERT INTO system_logs(username, action, module, details) VALUES (?, ?, 'CUSTOMER_CARE', ?)",
        authentication == null ? "SYSTEM" : authentication.getName(), action, details);
  }

  private void validatePage(int page, int pageSize) {
    if (page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Page must be zero or greater and pageSize must be between 1 and " + MAX_PAGE_SIZE + ".");
    }
  }

  private String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private CustomerReference customer(Map<String, Object> row) {
    return new CustomerReference(longValue(row.get("memberId")), stringValue(row.get("memberNo")),
        stringValue(row.get("memberName")), stringValue(row.get("contactNo")));
  }

  private Long longValue(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  private int intValue(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private LocalDateTime dateTimeValue(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
    if (value instanceof LocalDateTime dateTime) return dateTime;
    return null;
  }
}
