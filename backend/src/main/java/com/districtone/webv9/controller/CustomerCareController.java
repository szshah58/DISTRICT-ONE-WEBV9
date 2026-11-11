package com.districtone.webv9.controller;

import com.districtone.webv9.service.ComplaintApiDtos.AddComplaintHistoryRequest;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintCreated;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintDetail;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintHistoryCreated;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintHistoryEntry;
import com.districtone.webv9.service.ComplaintApiDtos.ComplaintPage;
import com.districtone.webv9.service.ComplaintApiDtos.CreateComplaintRequest;
import com.districtone.webv9.service.CustomerComplaintService;
import com.districtone.webv9.security.ProjectAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/v1/customer-care")
public class CustomerCareController {
  private final JdbcTemplate jdbc;
  private final CustomerComplaintService complaints;
  private final ProjectAccessService projectAccess;
  public CustomerCareController(JdbcTemplate jdbc, CustomerComplaintService complaints,
                                ProjectAccessService projectAccess) {
    this.jdbc = jdbc;
    this.complaints = complaints;
    this.projectAccess = projectAccess;
  }

  public record EnquiryRequest(@NotNull Long memberId, String prospectName, String contactNo,
      @NotBlank String subject, String enquiryType, String description, Long assignedTo,
      java.time.LocalDate followUpDate, String remarks) {}
  public record TicketRequest(@NotNull Long memberId, @NotBlank String subject, String category,
      String priority, String description, Long assignedTo, java.time.LocalDate dueDate) {}
  public record TicketStatusRequest(@NotBlank String status, String resolution) {}
  public record FollowupRequest(@NotNull Long memberId, Long ticketId, Long enquiryId,
      @NotNull java.time.LocalDate followUpDate, Long assignedTo, @NotBlank String purpose, String notes) {}
  public record FeedbackRequest(@NotNull Long memberId, Long ticketId, @NotBlank String feedbackText,
      Integer rating, String remarks) {}
  public record InteractionRequest(@NotNull Long memberId, @NotBlank String interactionType,
      @NotBlank String subject, String notes, String relatedEntityType, Long relatedEntityId) {}
  public record NoteRequest(@NotNull Long memberId, @NotBlank String note) {}
  public record AnnouncementRequest(@NotBlank String title, @NotBlank String body, String venue,
      String audience, String eventDate, String status) {}

  @GetMapping("/customers")
  public List<Map<String, Object>> searchCustomers(@RequestParam(required = false) String q, Authentication auth) {
    StringBuilder sql = new StringBuilder("""
        SELECT m.member_id AS id, m.mbr_no AS mbrNo, m.name, m.contact1,
               m.doc_status AS status,
               (SELECT r.reg_no FROM registrations r WHERE r.member_id = m.member_id
                ORDER BY r.registration_id DESC LIMIT 1) AS regNo
        FROM members m
        WHERE 1=1
        """);
    List<Object> args = new ArrayList<>();
    if (q != null && !q.isBlank()) {
      String term = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      sql.append("""
           AND (m.name LIKE ? OR m.mbr_no LIKE ? OR m.contact1 LIKE ?
                OR EXISTS (SELECT 1 FROM registrations r WHERE r.member_id = m.member_id
                           AND (r.reg_no LIKE ? OR r.form_no LIKE ?)))
          """);
      Collections.addAll(args, term, term, term, term, term);
    }
    sql.append(q == null || q.isBlank()
        ? " ORDER BY m.member_id DESC LIMIT 500"
        : " ORDER BY m.member_id DESC LIMIT 50");
    return scopedRows(jdbc.queryForList(sql.toString(), args.toArray()), auth);
  }
  public List<Map<String, Object>> searchCustomers(String query) {
    return searchCustomers(query, SecurityContextHolder.getContext().getAuthentication());
  }

  @GetMapping("/customers/{memberId}")
  public Map<String, Object> getCustomerProfile(@PathVariable Long memberId, Authentication auth) {
    requireMemberScope(auth, memberId);
    Map<String, Object> customer;
    try {
      customer = jdbc.queryForMap("""
          SELECT m.member_id AS id, m.mbr_no AS mbrNo, m.name, m.cnic_no AS cnicNo,
                 m.contact1, m.contact2, m.landline1, m.landline2,
                 m.email1, m.email2, m.address1, m.address2, m.city, m.country,
                 m.doc_status AS status
          FROM members m WHERE m.member_id = ?
          """, memberId);
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
    }

    List<Map<String, Object>> registrations = jdbc.queryForList("""
        SELECT r.registration_id AS registrationId, r.reg_no AS registrationNo,
               r.form_no AS formNo, r.category, r.status AS registrationStatus,
               sb.sale_booking_id AS bookingId, sb.sale_status AS bookingStatus,
               sb.category_snapshot AS bookingCategory,
               p.plot_id AS plotId, p.plot_no AS plotNo, p.plot_size_marla AS plotSizeMarla,
               p.plot_type AS plotType, p.category AS plotCategory, p.status AS plotStatus,
               b.block_id AS blockId, b.block_name AS blockName
        FROM registrations r
        LEFT JOIN sales_bookings sb ON sb.registration_id = r.registration_id
        LEFT JOIN plots p ON p.plot_id = sb.plot_id
        LEFT JOIN blocks b ON b.block_id = p.block_id
        WHERE r.member_id = ?
        ORDER BY r.registration_id DESC, sb.sale_booking_id DESC
        """, memberId);
    List<Map<String, Object>> documents = jdbc.queryForList("""
        SELECT DISTINCT d.id, d.document_type AS documentType,
               d.original_name AS originalName, d.document_status AS status,
               d.uploaded_at AS uploadedAt, d.booking_id AS bookingId, d.plot_id AS plotId
        FROM webv9_documents d
        WHERE d.document_status = 'ACTIVE'
          AND (d.member_id = ?
               OR d.booking_id IN (
                 SELECT sb.sale_booking_id FROM sales_bookings sb
                 JOIN registrations r ON r.registration_id = sb.registration_id
                 WHERE r.member_id = ?
               )
               OR d.plot_id IN (
                 SELECT sb.plot_id FROM sales_bookings sb
                 JOIN registrations r ON r.registration_id = sb.registration_id
                 WHERE r.member_id = ?
               ))
        ORDER BY d.uploaded_at DESC
        """, memberId, memberId, memberId);
    return Map.of("customer", customer, "registrations", registrations, "documents", documents);
  }
  public Map<String, Object> getCustomerProfile(Long memberId) {
    return getCustomerProfile(memberId, SecurityContextHolder.getContext().getAuthentication());
  }

  @GetMapping("/summary")
  public Map<String, Object> summary(Authentication auth) {
    List<Map<String, Object>> visible = scopedRows(jdbc.queryForList("""
        SELECT member_id AS memberId, case_type AS caseType, status
        FROM webv9_customer_care_cases
        """), auth);
    return Map.of(
        "openCases", visible.stream().filter(row -> !Set.of("RESOLVED", "CLOSED").contains(String.valueOf(row.get("status")))).count(),
        "openTickets", visible.stream().filter(row -> "TICKET".equals(row.get("caseType"))
            && !Set.of("RESOLVED", "CLOSED").contains(String.valueOf(row.get("status")))).count(),
        "pendingFollowups", visible.stream().filter(row -> "FOLLOWUP".equals(row.get("caseType"))
            && "PENDING".equals(row.get("status"))).count(),
        "totalEnquiries", visible.stream().filter(row -> "ENQUIRY".equals(row.get("caseType"))).count());
  }

  @GetMapping("/complaints")
  public ComplaintPage listComplaints(@RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "25") int pageSize,
                                      Authentication auth) {
    if (page < 0 || pageSize < 1 || pageSize > 100)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be non-negative and page size must be between 1 and 100.");
    Authentication currentAuth = currentAuthentication(auth);
    if (currentAuth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
    ComplaintPage first = complaints.list(q, 0, 100);
    List<com.districtone.webv9.service.ComplaintApiDtos.ComplaintListItem> visible = new ArrayList<>();
    for (int current = 0; current * 100L < first.totalElements(); current++) {
      ComplaintPage chunk = current == 0 ? first : complaints.list(q, current, 100);
      chunk.items().stream().filter(item -> item.customer() != null
          && projectAccess.canAccessMember(currentAuth.getName(), item.customer().memberId())).forEach(visible::add);
    }
    int start = Math.min(page * pageSize, visible.size());
    int end = Math.min(start + pageSize, visible.size());
    return new ComplaintPage(visible.subList(start, end), page, pageSize, visible.size());
  }

  @GetMapping("/complaints/{id}")
  public ComplaintDetail getComplaint(@PathVariable Long id, Authentication auth) {
    ComplaintDetail detail = complaints.get(id);
    requireMemberScope(auth, detail.customer().memberId());
    return detail;
  }
  public ComplaintDetail getComplaint(Long id) {
    return getComplaint(id, SecurityContextHolder.getContext().getAuthentication());
  }

  @PostMapping("/complaints")
  public ComplaintCreated createComplaint(@Valid @RequestBody CreateComplaintRequest req,
                                           Authentication auth) {
    Authentication currentAuth = currentAuthentication(auth);
    requireMemberScope(currentAuth, req.memberId());
    return complaints.create(req, currentAuth);
  }

  @GetMapping("/complaints/{id}/history")
  public List<ComplaintHistoryEntry> getComplaintHistory(@PathVariable Long id, Authentication auth) {
    requireComplaintScope(auth, id);
    return complaints.history(id);
  }

  @PostMapping({"/complaints/{id}/history", "/complaints/{id}/notes"})
  public ComplaintHistoryCreated addComplaintNote(@PathVariable Long id,
                                                    @Valid @RequestBody AddComplaintHistoryRequest req,
                                                    Authentication auth) {
    Authentication currentAuth = currentAuthentication(auth);
    requireComplaintScope(currentAuth, id);
    return complaints.addHistory(id, req, currentAuth);
  }

  @GetMapping("/enquiries")
  public List<Map<String, Object>> listEnquiries(@RequestParam(required=false) String q, Authentication auth) {
    StringBuilder sql = new StringBuilder(caseSelect("ENQUIRY")).append(" AND (1=1) ");
    List<Object> args = new ArrayList<>();
    if (q != null && !q.isBlank()) { String like = "%" + q.trim() + "%"; sql.append(" AND (c.subject LIKE ? OR c.description LIKE ? OR m.mbr_no LIKE ? OR m.name LIKE ?)"); Collections.addAll(args, like, like, like, like); }
    sql.append(" ORDER BY c.opened_at DESC LIMIT 300");
    return scopedRows(jdbc.queryForList(sql.toString(), args.toArray()), auth);
  }

  @PostMapping("/enquiries") @Transactional
  public Map<String,Object> createEnquiry(@Valid @RequestBody EnquiryRequest req, Authentication auth) {
    requireMemberScope(auth, req.memberId());
    ensureMember(req.memberId());
    String desc = joinText(req.description(), req.prospectName() == null ? null : "Prospect: " + req.prospectName(),
        req.contactNo() == null ? null : "Contact: " + req.contactNo(), req.enquiryType() == null ? null : "Type: " + req.enquiryType(),
        req.followUpDate() == null ? null : "Follow-up: " + req.followUpDate(), req.remarks());
    Long id = createCase(req.memberId(), "ENQUIRY", req.subject(), desc, "OPEN", req.assignedTo());
    audit(auth,"CREATE_ENQUIRY","CUSTOMER_CARE","Created enquiry case " + id);
    return Map.of("id",id,"status","OPEN");
  }

  @GetMapping("/tickets")
  public List<Map<String,Object>> listTickets(@RequestParam(required=false) String q,@RequestParam(required=false) String status,
                                              Authentication auth) {
    StringBuilder sql = new StringBuilder("""
        SELECT c.id, CONCAT('TKT-', LPAD(c.id,5,'0')) AS ticketNo, c.subject,
               CASE WHEN LOCATE('Category: ',COALESCE(c.description,''))>0
                    THEN SUBSTRING_INDEX(SUBSTRING(c.description,LOCATE('Category: ',c.description)+10),CHAR(10),1) ELSE 'GENERAL' END AS category,
               CASE WHEN LOCATE('Priority: ',COALESCE(c.description,''))>0
                    THEN SUBSTRING_INDEX(SUBSTRING(c.description,LOCATE('Priority: ',c.description)+10),CHAR(10),1) ELSE 'MEDIUM' END AS priority,
               c.description, c.status,
               CASE WHEN LOCATE('Due: ',COALESCE(c.description,''))>0
                    THEN STR_TO_DATE(SUBSTRING(c.description,LOCATE('Due: ',c.description)+5,10),'%Y-%m-%d') ELSE NULL END AS dueDate,
               c.resolution, c.resolved_at AS closedAt, c.opened_at AS createdAt,
               m.member_id AS memberId, m.mbr_no AS mbrNo, m.name AS memberName, u.username AS assignedToName
        FROM webv9_customer_care_cases c JOIN members m ON m.member_id=c.member_id
        LEFT JOIN users u ON u.id=c.assigned_to WHERE c.case_type='TICKET'
        """);
    List<Object> args = new ArrayList<>();
    if(status!=null&&!status.isBlank()&& !"ALL".equalsIgnoreCase(status)){sql.append(" AND c.status=?");args.add(status.trim().toUpperCase(Locale.ROOT));}
    if(q!=null&&!q.isBlank()){String like="%"+q.trim()+"%";sql.append(" AND (c.subject LIKE ? OR c.description LIKE ? OR m.mbr_no LIKE ? OR m.name LIKE ? OR CAST(c.id AS CHAR) LIKE ?)");Collections.addAll(args,like,like,like,like,like);}
    sql.append(" ORDER BY c.opened_at DESC LIMIT 300");
    return scopedRows(jdbc.queryForList(sql.toString(),args.toArray()), auth);
  }

  @PostMapping("/tickets") @Transactional
  public Map<String,Object> createTicket(@Valid @RequestBody TicketRequest req,Authentication auth){
    requireMemberScope(auth, req.memberId());
    ensureMember(req.memberId());
    Long id=createCase(req.memberId(),"TICKET",req.subject(),joinText(req.description(),"Category: "+or(req.category(),"GENERAL"),"Priority: "+or(req.priority(),"MEDIUM"),req.dueDate()==null?null:"Due: "+req.dueDate()),"OPEN",req.assignedTo());
    audit(auth,"CREATE_TICKET","CUSTOMER_CARE","Created ticket case "+id);
    return Map.of("id",id,"ticketNo",ticketNo(id),"status","OPEN");
  }

  @PutMapping("/tickets/{id}/status") @Transactional
  public Map<String,Object> updateTicketStatus(@PathVariable Long id,@Valid @RequestBody TicketStatusRequest req,Authentication auth){
    requireCaseScope(auth, id, "TICKET");
    String status=req.status().trim().toUpperCase(Locale.ROOT); String resolved=(Set.of("RESOLVED","CLOSED").contains(status))?"CURRENT_TIMESTAMP":"NULL";
    int changed=jdbc.update("UPDATE webv9_customer_care_cases SET status=?, resolution=?, resolved_at="+resolved+" WHERE id=? AND case_type='TICKET'",status,blankToNull(req.resolution()),id);
    if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Service ticket not found");
    audit(auth,"UPDATE_TICKET_STATUS","CUSTOMER_CARE","Updated ticket case "+id+" to "+status); return Map.of("id",id,"status",status,"updated",true);
  }

  @GetMapping("/followups")
  public List<Map<String,Object>> listFollowups(@RequestParam(required=false) String status, Authentication auth){
    StringBuilder sql=new StringBuilder("""
        SELECT c.id, STR_TO_DATE(SUBSTRING(c.description,16,10),'%Y-%m-%d') AS followUpDate,
               c.subject AS purpose, c.description AS notes, NULL AS outcome, NULL AS nextFollowUpDate,
               c.status, c.opened_at AS createdAt, m.member_id AS memberId, m.mbr_no AS mbrNo,
               m.name AS memberName, u.username AS assignedToName, NULL AS ticketNo
        FROM webv9_customer_care_cases c JOIN members m ON m.member_id=c.member_id
        LEFT JOIN users u ON u.id=c.assigned_to WHERE c.case_type='FOLLOWUP'
        """); List<Object> args=new ArrayList<>();
    if(status!=null&&!status.isBlank()&& !"ALL".equalsIgnoreCase(status)){sql.append(" AND c.status=?");args.add(status.trim().toUpperCase(Locale.ROOT));}
    sql.append(" ORDER BY followUpDate ASC, c.id DESC LIMIT 300");
    return scopedRows(jdbc.queryForList(sql.toString(),args.toArray()), auth);
  }

  @PostMapping("/followups") @Transactional
  public Map<String,Object> createFollowup(@Valid @RequestBody FollowupRequest req,Authentication auth){
    requireMemberScope(auth, req.memberId());
    ensureMember(req.memberId());
    Long id=createCase(req.memberId(),"FOLLOWUP",req.purpose(),"FOLLOW_UP_DATE="+req.followUpDate()+"\n"+or(req.notes(),""),"PENDING",req.assignedTo());
    audit(auth,"CREATE_FOLLOWUP","CUSTOMER_CARE","Created follow-up case "+id);return Map.of("id",id,"status","PENDING");
  }

  @GetMapping("/feedback")
  public List<Map<String,Object>> listFeedback(Authentication auth){return scopedRows(jdbc.queryForList("""
      SELECT c.id, SUBSTRING_INDEX(SUBSTRING(c.description,LOCATE(CHAR(10),c.description)+1),CONCAT(CHAR(10),'Remarks: '),1) AS feedbackText,
             NULLIF(SUBSTRING_INDEX(SUBSTRING_INDEX(c.description,CHAR(10),1),'=',-1),'') AS rating,
             NULLIF(SUBSTRING_INDEX(c.description,CONCAT(CHAR(10),'Remarks: '),-1),c.description) AS remarks,
             c.opened_at AS createdAt,m.member_id AS memberId,m.mbr_no AS mbrNo,m.name AS memberName,NULL AS ticketNo
      FROM webv9_customer_care_cases c JOIN members m ON m.member_id=c.member_id WHERE c.case_type='FEEDBACK'
      ORDER BY c.opened_at DESC LIMIT 300
      """), auth);}

  @PostMapping("/feedback") @Transactional
  public Map<String,Object> createFeedback(@Valid @RequestBody FeedbackRequest req,Authentication auth){
    requireMemberScope(auth, req.memberId());
    ensureMember(req.memberId());String details="RATING="+(req.rating()==null?"":req.rating())+"\n"+req.feedbackText().trim()+(blankToNull(req.remarks())==null?"":"\nRemarks: "+req.remarks().trim());
    Long id=createCase(req.memberId(),"FEEDBACK","Customer feedback",details,"OPEN",null);audit(auth,"CREATE_FEEDBACK","CUSTOMER_CARE","Recorded feedback case "+id);return Map.of("id",id,"created",true);
  }

  @GetMapping("/interactions/{memberId}")
  public List<Map<String,Object>> getInteractions(@PathVariable Long memberId, Authentication auth){requireMemberScope(auth, memberId);ensureMember(memberId);return jdbc.queryForList("""
      SELECT c.id,c.case_type AS type,c.subject,c.description AS notes,c.opened_at AS createdAt,u.username AS userName
      FROM webv9_customer_care_cases c LEFT JOIN users u ON u.id=c.assigned_to
      WHERE c.member_id=? AND c.case_type='INTERACTION' ORDER BY c.opened_at DESC
      """,memberId);}

  @PostMapping("/interactions") @Transactional
  public Map<String,Object> createInteraction(@Valid @RequestBody InteractionRequest req,Authentication auth){
    requireMemberScope(auth, req.memberId());
    ensureMember(req.memberId());String notes=joinText(req.notes(),req.relatedEntityType()==null?null:"Related type: "+req.relatedEntityType(),req.relatedEntityId()==null?null:"Related ID: "+req.relatedEntityId());
    Long id=createCase(req.memberId(),"INTERACTION",req.interactionType().trim().toUpperCase(Locale.ROOT)+": "+req.subject().trim(),notes,"OPEN",findUserId(auth));return Map.of("id",id,"created",true);
  }

  @GetMapping("/notes/{memberId}")
  public List<Map<String,Object>> getNotes(@PathVariable Long memberId, Authentication auth){requireMemberScope(auth, memberId);ensureMember(memberId);return jdbc.queryForList("""
      SELECT n.id,n.note,n.created_at AS createdAt,u.username AS userName
      FROM webv9_case_notes n JOIN webv9_customer_care_cases c ON c.id=n.case_id
      LEFT JOIN users u ON u.id=n.created_by WHERE c.member_id=? ORDER BY n.created_at DESC
      """,memberId);}

  @PostMapping("/notes") @Transactional
  public Map<String,Object> createNote(@Valid @RequestBody NoteRequest req,Authentication auth){
    requireMemberScope(auth, req.memberId());
    ensureMember(req.memberId());Long caseId=createCase(req.memberId(),"NOTE","Member note",null,"OPEN",null);
    jdbc.update("INSERT INTO webv9_case_notes(case_id,note,created_by) VALUES(?,?,?)",caseId,req.note().trim(),findUserId(auth));
    Long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);return Map.of("id",id,"created",true);
  }

  @GetMapping("/announcements")
  public List<Map<String,Object>> listAnnouncements(Authentication auth){requireSystemWideCareAccess(auth);return jdbc.queryForList("""
      SELECT n.id,n.title,n.body,
             CASE WHEN LOCATE(CONCAT(CHAR(10),'Venue: '),n.body)>0
                  THEN SUBSTRING_INDEX(SUBSTRING(n.body,LOCATE(CONCAT(CHAR(10),'Venue: '),n.body)+CHAR_LENGTH(CONCAT(CHAR(10),'Venue: '))),CHAR(10),1) ELSE NULL END AS venue,
             CASE WHEN LOCATE(CONCAT(CHAR(10),'Audience: '),n.body)>0
                  THEN SUBSTRING_INDEX(SUBSTRING(n.body,LOCATE(CONCAT(CHAR(10),'Audience: '),n.body)+CHAR_LENGTH(CONCAT(CHAR(10),'Audience: '))),CHAR(10),1) ELSE 'ALL' END AS audience,
             n.status,n.starts_at AS eventDate,
             n.created_at AS createdAt,u.username AS createdBy FROM webv9_notices n
      LEFT JOIN users u ON u.id=n.created_by ORDER BY n.created_at DESC
      """);}

  @PostMapping("/announcements") @Transactional
  public Map<String,Object> createAnnouncement(@Valid @RequestBody AnnouncementRequest req,Authentication auth){
    requireSystemWideCareAccess(auth);
    String body=joinText(req.body().trim(),blankToNull(req.venue())==null?null:"Venue: "+req.venue().trim(),req.audience()==null?null:"Audience: "+req.audience().trim());
    jdbc.update("""
        INSERT INTO webv9_notices(title,body,starts_at,status,created_by) VALUES(?,?,STR_TO_DATE(NULLIF(?,''),'%Y-%m-%d'),?,?)
        """,req.title().trim(),body,req.eventDate()==null?"":req.eventDate(),or(req.status(),"PUBLISHED").toUpperCase(Locale.ROOT),findUserId(auth));
    Long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);audit(auth,"CREATE_ANNOUNCEMENT","CUSTOMER_CARE","Created notice "+id);return Map.of("id",id,"status",or(req.status(),"PUBLISHED").toUpperCase(Locale.ROOT));
  }

  private String caseSelect(String type){return """
      SELECT c.id,c.subject,c.description,
             CASE WHEN LOCATE('Type: ',COALESCE(c.description,''))>0 THEN SUBSTRING_INDEX(SUBSTRING(c.description,LOCATE('Type: ',c.description)+6),CHAR(10),1) ELSE 'GENERAL' END AS enquiryType,
             c.status,c.opened_at AS createdAt,c.resolved_at AS resolvedAt,
             CASE WHEN LOCATE('Prospect: ',COALESCE(c.description,''))>0 THEN SUBSTRING_INDEX(SUBSTRING(c.description,LOCATE('Prospect: ',c.description)+10),CHAR(10),1) ELSE NULL END AS prospectName,
             CASE WHEN LOCATE('Contact: ',COALESCE(c.description,''))>0 THEN SUBSTRING_INDEX(SUBSTRING(c.description,LOCATE('Contact: ',c.description)+9),CHAR(10),1) ELSE NULL END AS contactNo,
             CASE WHEN LOCATE('Follow-up: ',COALESCE(c.description,''))>0 THEN STR_TO_DATE(SUBSTRING(c.description,LOCATE('Follow-up: ',c.description)+11,10),'%Y-%m-%d') ELSE NULL END AS followUpDate,
             CASE WHEN LOCATE('Remarks: ',COALESCE(c.description,''))>0 THEN SUBSTRING_INDEX(SUBSTRING(c.description,LOCATE('Remarks: ',c.description)+9),CHAR(10),1) ELSE NULL END AS remarks,
             c.assigned_to AS assignedTo,m.member_id AS memberId,m.mbr_no AS mbrNo,m.name AS memberName,
             u.username AS assignedToName FROM webv9_customer_care_cases c
      JOIN members m ON m.member_id=c.member_id LEFT JOIN users u ON u.id=c.assigned_to
      WHERE c.case_type='"""+type+"' ";}
  private List<Map<String, Object>> scopedRows(List<Map<String, Object>> rows, Authentication auth) {
    Authentication currentAuth = currentAuthentication(auth);
    if (currentAuth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
    return rows.stream().filter(row -> {
      Object memberId = row.containsKey("memberId") ? row.get("memberId") : row.get("id");
      return memberId instanceof Number number
          && projectAccess.canAccessMember(currentAuth.getName(), number.longValue());
    }).toList();
  }
  private void requireMemberScope(Authentication auth, Long memberId) {
    auth = currentAuthentication(auth);
    if (auth == null || memberId == null || !projectAccess.canAccessMember(auth.getName(), memberId))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The customer is outside the account's project/location scope.");
  }
  private void requireComplaintScope(Authentication auth, Long complaintId) {
    ComplaintDetail detail = complaints.get(complaintId);
    requireMemberScope(auth, detail.customer().memberId());
  }
  private void requireCaseScope(Authentication auth, Long caseId, String expectedType) {
    auth = currentAuthentication(auth);
    if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
    Map<String, Object> caseRow;
    try {
      caseRow = jdbc.queryForMap("""
          SELECT member_id AS memberId, case_type AS caseType
          FROM webv9_customer_care_cases WHERE id = ?
          """, caseId);
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer-care record not found.");
    }
    if (expectedType != null && !expectedType.equals(caseRow.get("caseType")))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer-care record not found.");
    requireMemberScope(auth, ((Number) caseRow.get("memberId")).longValue());
  }
  private void requireSystemWideCareAccess(Authentication auth) {
    auth = currentAuthentication(auth);
    if (auth == null || !projectAccess.isSuperAdmin(auth.getName()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System-wide customer-care content requires Super Admin access.");
  }
  private Authentication currentAuthentication(Authentication authentication) {
    return authentication == null ? SecurityContextHolder.getContext().getAuthentication() : authentication;
  }
  private Long createCase(Long memberId,String type,String subject,String description,String status,Long assignedTo){
    jdbc.update("INSERT INTO webv9_customer_care_cases(member_id,case_type,subject,description,status,assigned_to) VALUES(?,?,?,?,?,?)",memberId,type,subject,description,status,assignedTo);
    return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
  }
  private String ticketNo(Long id){return String.format(Locale.ROOT,"TKT-%05d",id);}
  private void ensureMember(Long id){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE member_id=?",Integer.class,id);if(count==null||count==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Member not found");}
  private Long findUserId(Authentication auth){if(auth==null)return null;try{return jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,auth.getName());}catch(EmptyResultDataAccessException e){return null;}}
  private String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
  private String or(String value,String fallback){return value==null||value.isBlank()?fallback:value.trim();}
  private String joinText(String... values){return Arrays.stream(values).filter(v->v!=null&&!v.isBlank()).reduce((a,b)->a+"\n"+b).orElse(null);}
  private void audit(Authentication auth,String action,String module,String details){jdbc.update("INSERT INTO system_logs(username,action,module,details) VALUES(?,?,?,?)",auth==null?"SYSTEM":auth.getName(),action,module,details);}
}
