package com.districtone.webv9.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.districtone.webv9.security.ProjectAccessService;

import java.util.*;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
  private final JdbcTemplate jdbc;
  private final ProjectAccessService projectAccess;

  public CustomerController(JdbcTemplate jdbc, ProjectAccessService projectAccess) {
    this.jdbc = jdbc; this.projectAccess = projectAccess;
  }

  public record MemberRequest(String mbrNo, @NotBlank String name, @NotBlank String regNo, String formNo,
                              String category, @NotBlank String cnicNo, String contact1, String city,
                              String address, String status, Long projectId) {}

  @GetMapping
  public List<Map<String, Object>> list(@RequestParam(required = false) String q, Authentication authentication) {
    StringBuilder sql = new StringBuilder("""
        SELECT m.member_id AS id, m.mbr_no AS mbrNo, r.reg_no AS regNo, r.form_no AS formNo,
               r.category, m.name, m.cnic_no AS cnicNo, m.contact1, m.city, m.doc_status AS status
        FROM members m
        LEFT JOIN registrations r ON r.registration_id = (
          SELECT MAX(r2.registration_id) FROM registrations r2 WHERE r2.member_id = m.member_id
        )
        WHERE 1=1
        """);
    List<Object> params = new ArrayList<>();
    if (q != null && !q.isBlank()) {
      String term = "%" + q.trim() + "%";
      sql.append(" AND (m.name LIKE ? OR m.mbr_no LIKE ? OR r.reg_no LIKE ?)");
      params.add(term); params.add(term); params.add(term);
    }
    sql.append(" ORDER BY m.member_id DESC LIMIT 500");
    return jdbc.queryForList(sql.toString(), params.toArray()).stream().filter(row ->
        projectAccess.canAccessMember(authentication.getName(), ((Number) row.get("id")).longValue())).toList();
  }

  @PostMapping
  @Transactional
  public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody MemberRequest req, Authentication authentication) {
    if (!projectAccess.isSuperAdmin(authentication.getName())) {
      if (req.projectId() == null)
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Select a project within your assigned scope before adding a customer");
      projectAccess.requireProject(authentication.getName(), req.projectId());
    }
    String mbrNo = blankToNull(req.mbrNo());
    String regNo = blankToNull(req.regNo());
    if (regNo == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Registration number is required");
    }
    if (mbrNo == null) mbrNo = "PENDING-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    if (exists("SELECT COUNT(*) FROM members WHERE mbr_no = ?", mbrNo))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Member number is already registered");
    if (exists("SELECT COUNT(*) FROM registrations WHERE reg_no = ?", regNo))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Registration number is already registered");

    jdbc.update("""
        INSERT INTO members (mbr_no, name, cnic_no, address1, city, contact1, doc_status)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, mbrNo, req.name().trim(), blankToNull(req.cnicNo()), blankToNull(req.address()),
        blankToNull(req.city()), blankToNull(req.contact1()), statusOrDefault(req.status()));
    Long memberId = jdbc.queryForObject("SELECT member_id FROM members WHERE mbr_no = ?", Long.class, mbrNo);
    jdbc.update("""
        INSERT INTO registrations (member_id, reg_no, form_no, category, status)
        VALUES (?, ?, ?, ?, ?)
        """, memberId, regNo, blankToNull(req.formNo()), blankToNull(req.category()), statusOrDefault(req.status()));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", memberId); result.put("mbrNo", mbrNo); result.put("regNo", regNo);
    result.put("formNo", blankToNull(req.formNo())); result.put("name", req.name().trim());
    result.put("cnicNo", blankToNull(req.cnicNo())); result.put("contact1", blankToNull(req.contact1()));
    result.put("city", blankToNull(req.city())); result.put("status", statusOrDefault(req.status()));
    return ResponseEntity.status(HttpStatus.CREATED).body(result);
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable Long id, Authentication authentication) {
    if (!projectAccess.canAccessMember(authentication.getName(), id))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The customer is outside your project/location scope");
    try {
      return jdbc.queryForMap("""
          SELECT m.member_id AS id, m.mbr_no AS mbrNo, m.name, m.cnic_no AS cnicNo,
                 m.contact1, m.city, m.doc_status AS status
          FROM members m WHERE m.member_id = ?
          """, id);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
    }
  }

  private boolean exists(String sql, Object value) {
    Integer count = jdbc.queryForObject(sql, Integer.class, value);
    return count != null && count > 0;
  }

  private String statusOrDefault(String status) {
    return status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase(Locale.ROOT);
  }

  private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
