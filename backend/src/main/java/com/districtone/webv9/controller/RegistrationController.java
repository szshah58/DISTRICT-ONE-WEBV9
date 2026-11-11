package com.districtone.webv9.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/registration")
public class RegistrationController {
  private final JdbcTemplate jdbc;

  public RegistrationController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record MemberRequest(
      String mbrNo, @NotBlank String regNo, String formNo, String category,
      @NotBlank String fullName, @NotBlank String cnicNo, String contact1, String contact2,
      String address, String city, @Email String email, @NotBlank String status) {}

  @PostMapping("/member")
  @Transactional
  public ResponseEntity<?> registerMember(@Valid @RequestBody MemberRequest request) {
    String mbrNo = request.mbrNo() == null || request.mbrNo().isBlank()
        ? "PENDING-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
        : request.mbrNo().trim();
    if (exists("SELECT COUNT(*) FROM members WHERE mbr_no = ?", mbrNo))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "MBR No. is already registered");
    if (exists("SELECT COUNT(*) FROM registrations WHERE reg_no = ?", request.regNo().trim()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "REG No. is already registered");
    jdbc.update("""
        INSERT INTO members (mbr_no, name, cnic_no, address1, city, contact1, contact2, email1, doc_status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, mbrNo, request.fullName().trim(), blankToNull(request.cnicNo()), blankToNull(request.address()),
        blankToNull(request.city()), blankToNull(request.contact1()), blankToNull(request.contact2()),
        blankToNull(request.email()), request.status().trim().toUpperCase());
    Long memberId = jdbc.queryForObject("SELECT member_id FROM members WHERE mbr_no = ?", Long.class, mbrNo);
    jdbc.update("""
        INSERT INTO registrations (member_id, reg_no, form_no, category, status)
        VALUES (?, ?, ?, ?, ?)
        """, memberId, request.regNo().trim(), blankToNull(request.formNo()), blankToNull(request.category()),
        request.status().trim().toUpperCase());
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
        "registered", true, "mbrNo", mbrNo, "regNo", request.regNo().trim(),
        "message", "Member registration submitted successfully"));
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private boolean exists(String sql, Object value) {
    Integer count = jdbc.queryForObject(sql, Integer.class, value);
    return count != null && count > 0;
  }
}
