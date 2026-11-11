package com.districtone.webv9.controller;

import com.districtone.webv9.security.ProjectAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.*;

@RestController
public class MobileContentController {
  private static final Logger log = LoggerFactory.getLogger(MobileContentController.class);
  private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
  private static final Set<String> SECTIONS =
      Set.of("PROJECTS", "NEWS", "EVENTS", "ABOUT", "CONTACT", "VISITOR_INFO", "DOCUMENTS");

  private final JdbcTemplate jdbc;
  private final ProjectAccessService projectAccess;
  private final Path uploadDirectory;

  public MobileContentController(
      JdbcTemplate jdbc,
      ProjectAccessService projectAccess,
      @Value("${app.mobile-content.upload-dir:./uploads/mobile-content}") String uploadDirectory) {
    this.jdbc = jdbc;
    this.projectAccess = projectAccess;
    this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
  }

  public record ContentRequest(@NotBlank @Size(max = 255) String title, String body,
      String location, String status, String date, @Size(max = 1000) String image,
      String phone, String email, Integer sortOrder, Boolean active) {}

  public record AdminContentRequest(
      @NotBlank @Size(max = 255) String title,
      @Size(max = 100) String category,
      @Size(max = 2000) String summary,
      @Size(max = 100000) String description,
      @Size(max = 100000) String article,
      @Size(max = 255) String location,
      @Size(max = 80) String status,
      LocalDate publicationDate,
      @Size(max = 64) String eventTime,
      @Size(max = 255) String venue,
      @Size(max = 1000) String registrationInfo,
      @Size(max = 1000) String image,
      @Size(max = 100) String documentCategory,
      String fileAssetId,
      Integer sortOrder,
      String publishState) {}

  /** Public read-only feed consumed by the District One mobile app. */
  @GetMapping("/api/v1/mobile-content/{section}")
  public List<Map<String, Object>> publicContent(@PathVariable String section) {
    String key = section(section);
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT content.id, content.section, content.title,
               CASE WHEN content.section='NEWS' THEN COALESCE(NULLIF(content.full_article,''),content.body) ELSE content.body END AS body,
               content.body AS description, content.category, content.summary,
               content.full_article AS article, content.location, content.status,
               content.content_date AS date, content.content_date AS publicationDate,
               content.image_url AS image, content.phone, content.email,
               content.sort_order AS sortOrder, content.venue, content.event_time AS eventTime,
               content.registration_info AS registrationInfo,
               content.document_category AS documentCategory, content.file_asset_id AS fileAssetId,
               asset.original_name AS fileName, asset.content_type AS contentType,
               asset.file_size AS fileSize, content.publish_state AS publishState
        FROM webv9_mobile_content content
        LEFT JOIN webv9_mobile_content_assets asset ON asset.id=content.file_asset_id
        WHERE content.section=? AND content.is_active=1 AND content.publish_state='PUBLISHED'
        ORDER BY content.sort_order, content.content_date DESC, content.id DESC
        """, key);
    return withAssetUrls(rows);
  }

  @GetMapping("/api/v1/admin/mobile-content/{section}")
  public List<Map<String, Object>> adminList(
      @PathVariable String section,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "ALL") String state,
      Authentication auth) {
    requireSuperAdmin(auth);
    String key = section(section);
    String normalizedState = state.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("ALL", "DRAFT", "PUBLISHED", "ARCHIVED").contains(normalizedState)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown publication-state filter.");
    }

    StringBuilder sql = new StringBuilder("""
        SELECT content.id, content.section, content.title, content.body AS description,
               content.category, content.summary, content.full_article AS article,
               content.location, content.status, content.content_date AS publicationDate,
               content.image_url AS image, content.venue, content.event_time AS eventTime,
               content.registration_info AS registrationInfo,
               content.document_category AS documentCategory, content.file_asset_id AS fileAssetId,
               asset.original_name AS fileName, content.publish_state AS publishState,
               content.is_active AS active, content.sort_order AS sortOrder
        FROM webv9_mobile_content content
        LEFT JOIN webv9_mobile_content_assets asset ON asset.id=content.file_asset_id
        WHERE content.section=?
        """);
    List<Object> args = new ArrayList<>();
    args.add(key);
    if (!q.isBlank()) {
      sql.append(" AND (content.title LIKE ? OR COALESCE(content.summary,'') LIKE ? OR COALESCE(content.body,'') LIKE ? OR COALESCE(content.location,'') LIKE ?)");
      String pattern = "%" + q.trim() + "%";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    if (!"ALL".equals(normalizedState)) {
      sql.append(" AND content.publish_state=?");
      args.add(normalizedState);
    }
    sql.append(" ORDER BY content.sort_order, content.id DESC");
    return withAssetUrls(jdbc.queryForList(sql.toString(), args.toArray()));
  }

  @PostMapping("/api/v1/admin/mobile-content/{section}")
  @Transactional
  public Map<String, Object> adminCreate(
      @PathVariable String section, @Valid @RequestBody AdminContentRequest request, Authentication auth) {
    requireSuperAdmin(auth);
    String key = section(section);
    validateContent(key, request);
    String state = publicationState(request.publishState(), "DRAFT");
    String contentKey = UUID.randomUUID().toString();
    LocalDate publicationDate = contentDate(key, request.publicationDate());
    String image = safeImageUrl(request.image());
    ensureAssetExists(request.fileAssetId());
    ensureImageAssetExists(image);

    jdbc.update("""
        INSERT INTO webv9_mobile_content
          (section,content_key,title,body,location,status,content_date,image_url,phone,email,
           sort_order,is_active,category,summary,full_article,venue,event_time,
           registration_info,document_category,file_asset_id,publish_state,archived_at)
        VALUES(?,?,?,?,?,?,?,?,NULL,NULL,?,?,?,?,?,?,?,?,?,?,?,NULL)
        """, key, contentKey, request.title().trim(), contentBody(key, request),
        blankToNull(request.location()), blankToNull(request.status()), publicationDate, image,
        sortOrder(request.sortOrder()), isPublished(state), blankToNull(request.category()),
        blankToNull(request.summary()), blankToNull(request.article()), blankToNull(request.venue()),
        blankToNull(request.eventTime()), blankToNull(request.registrationInfo()),
        blankToNull(request.documentCategory()), blankToNull(request.fileAssetId()), state);

    Long id = jdbc.queryForObject(
        "SELECT id FROM webv9_mobile_content WHERE section=? AND content_key=?", Long.class, key, contentKey);
    audit(auth, "CREATE", key, id);
    return Map.of("id", Objects.requireNonNull(id), "created", true, "publishState", state);
  }

  @PutMapping("/api/v1/admin/mobile-content/{section}/{id}")
  @Transactional
  public Map<String, Object> adminUpdate(
      @PathVariable String section, @PathVariable Long id,
      @Valid @RequestBody AdminContentRequest request, Authentication auth) {
    requireSuperAdmin(auth);
    String key = section(section);
    validateContent(key, request);
    String state = publicationState(request.publishState(), "DRAFT");
    ensureContentExists(key, id);
    ensureAssetExists(request.fileAssetId());
    String image = safeImageUrl(request.image());
    ensureImageAssetExists(image);

    jdbc.update("""
        UPDATE webv9_mobile_content
        SET title=?,body=?,location=?,status=?,content_date=?,image_url=?,sort_order=?,
            is_active=?,category=?,summary=?,full_article=?,venue=?,event_time=?,
            registration_info=?,document_category=?,file_asset_id=?,publish_state=?,
            archived_at=IF(?='ARCHIVED',COALESCE(archived_at,CURRENT_TIMESTAMP),NULL)
        WHERE section=? AND id=?
        """, request.title().trim(), contentBody(key, request), blankToNull(request.location()),
        blankToNull(request.status()), contentDate(key, request.publicationDate()),
        image, sortOrder(request.sortOrder()), isPublished(state),
        blankToNull(request.category()), blankToNull(request.summary()), blankToNull(request.article()),
        blankToNull(request.venue()), blankToNull(request.eventTime()),
        blankToNull(request.registrationInfo()), blankToNull(request.documentCategory()),
        blankToNull(request.fileAssetId()), state, state, key, id);
    audit(auth, "UPDATE", key, id);
    return Map.of("id", id, "updated", true, "publishState", state);
  }

  @PostMapping("/api/v1/admin/mobile-content/{section}/{id}/publish")
  @Transactional
  public Map<String, Object> publish(@PathVariable String section, @PathVariable Long id, Authentication auth) {
    return changePublicationState(section, id, "PUBLISHED", auth);
  }

  @PostMapping("/api/v1/admin/mobile-content/{section}/{id}/unpublish")
  @Transactional
  public Map<String, Object> unpublish(@PathVariable String section, @PathVariable Long id, Authentication auth) {
    return changePublicationState(section, id, "DRAFT", auth);
  }

  @PostMapping("/api/v1/admin/mobile-content/{section}/{id}/archive")
  @Transactional
  public Map<String, Object> archive(@PathVariable String section, @PathVariable Long id, Authentication auth) {
    return changePublicationState(section, id, "ARCHIVED", auth);
  }

  @PostMapping(path = "/api/v1/admin/mobile-content/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public Map<String, Object> uploadAsset(
      @RequestPart("file") MultipartFile file,
      @RequestParam(defaultValue = "DOCUMENT") String purpose,
      Authentication auth) {
    requireSuperAdmin(auth);
    if (file == null || file.isEmpty() || file.getSize() > MAX_UPLOAD_BYTES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a file up to 10 MB.");
    }

    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded file could not be read.", exception);
    }
    if (bytes.length == 0 || bytes.length > MAX_UPLOAD_BYTES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a file up to 10 MB.");
    }
    DetectedFile detected = detectFile(bytes, file.getOriginalFilename());
    String normalizedPurpose = purpose == null ? "DOCUMENT" : purpose.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("IMAGE", "DOCUMENT").contains(normalizedPurpose)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown mobile-content upload purpose.");
    }
    if (normalizedPurpose.equals("IMAGE") && !detected.contentType().startsWith("image/")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Choose a JPG, PNG, or WebP image for image content.");
    }
    String id = UUID.randomUUID().toString();
    String storageName = id + detected.extension();
    Path storedFile = uploadDirectory.resolve(storageName).normalize();
    if (!storedFile.startsWith(uploadDirectory)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded file name is invalid.");
    }

    try {
      Files.createDirectories(uploadDirectory);
      Files.write(storedFile, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (IOException exception) {
      log.error("Unable to store a mobile-content asset.", exception);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The uploaded file could not be stored.");
    }

    try {
      jdbc.update("""
          INSERT INTO webv9_mobile_content_assets
            (id,storage_name,original_name,content_type,file_size,uploaded_by)
          VALUES(?,?,?,?,?,?)
          """, id, storageName, safeOriginalName(file.getOriginalFilename()), detected.contentType(),
          (long) bytes.length, auth.getName());
    } catch (RuntimeException exception) {
      try {
        Files.deleteIfExists(storedFile);
      } catch (IOException cleanupException) {
        exception.addSuppressed(cleanupException);
      }
      throw exception;
    }
    audit(auth, "UPLOAD", "ASSETS", id);
    return Map.of("id", id, "url", "/api/v1/mobile-content/assets/" + id,
        "fileName", safeOriginalName(file.getOriginalFilename()),
        "contentType", detected.contentType(), "size", bytes.length);
  }

  @GetMapping("/api/v1/mobile-content/assets/{id}")
  public ResponseEntity<ByteArrayResource> readAsset(@PathVariable String id, Authentication auth) {
    if (!isUuid(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mobile-content asset not found.");
    Map<String, Object> asset;
    try {
      asset = jdbc.queryForMap("""
          SELECT storage_name AS storageName, original_name AS originalName,
                 content_type AS contentType, file_size AS fileSize
          FROM webv9_mobile_content_assets WHERE id=?
          """, id);
    } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mobile-content asset not found.");
    }
    boolean admin = authenticatedSuperAdmin(auth);
    if (!admin && !isPublishedAsset(id)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mobile-content asset not found.");
    }

    String storageName = String.valueOf(asset.get("storageName"));
    Path storedFile = uploadDirectory.resolve(storageName).normalize();
    if (!storedFile.startsWith(uploadDirectory) || !Files.isRegularFile(storedFile)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mobile-content asset not found.");
    }
    try {
      byte[] bytes = Files.readAllBytes(storedFile);
      String contentType = String.valueOf(asset.get("contentType"));
      String disposition = contentType.startsWith("image/") ? "inline" : "attachment";
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.parseMediaType(contentType));
      headers.setContentLength(bytes.length);
      headers.setContentDisposition(ContentDisposition.builder(disposition)
          .filename(String.valueOf(asset.get("originalName")), StandardCharsets.UTF_8).build());
      headers.set("X-Content-Type-Options", "nosniff");
      return new ResponseEntity<>(new ByteArrayResource(bytes), headers, HttpStatus.OK);
    } catch (IOException exception) {
      log.error("Unable to read a mobile-content asset {}.", id, exception);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The mobile-content file could not be read.");
    }
  }

  @GetMapping("/api/v1/customer-care/mobile-content/{section}")
  public List<Map<String, Object>> manageList(@PathVariable String section) {
    return jdbc.queryForList("""
        SELECT id, section, title, body, location, status, content_date AS date,
               image_url AS image, phone, email, sort_order AS sortOrder, is_active AS active
        FROM webv9_mobile_content WHERE section=? ORDER BY sort_order, id
        """, section(section));
  }

  @PostMapping("/api/v1/customer-care/mobile-content/{section}")
  @Transactional
  public Map<String, Object> create(@PathVariable String section, @Valid @RequestBody ContentRequest request,
                                    Authentication auth) {
    String key = section(section);
    String contentKey = singleton(key) ? "page" : UUID.randomUUID().toString();
    boolean active = active(request.active());
    if (singleton(key)) {
      jdbc.update("""
          INSERT INTO webv9_mobile_content
            (section,content_key,title,body,location,status,content_date,image_url,phone,email,
             sort_order,is_active,publish_state)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
          ON DUPLICATE KEY UPDATE title=VALUES(title),body=VALUES(body),location=VALUES(location),
            status=VALUES(status),content_date=VALUES(content_date),image_url=VALUES(image_url),
            phone=VALUES(phone),email=VALUES(email),sort_order=VALUES(sort_order),
            is_active=VALUES(is_active),publish_state=VALUES(publish_state)
          """, key, contentKey, request.title().trim(), request.body(), request.location(),
          request.status(), request.date(), request.image(), request.phone(), request.email(),
          value(request.sortOrder()), active ? 1 : 0, active ? "PUBLISHED" : "DRAFT");
    } else {
      jdbc.update("""
          INSERT INTO webv9_mobile_content
            (section,content_key,title,body,location,status,content_date,image_url,phone,email,
             sort_order,is_active,publish_state)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
          """, key, contentKey, request.title().trim(), request.body(), request.location(),
          request.status(), request.date(), request.image(), request.phone(), request.email(),
          value(request.sortOrder()), active ? 1 : 0, active ? "PUBLISHED" : "DRAFT");
    }
    Long id = jdbc.queryForObject(
        "SELECT id FROM webv9_mobile_content WHERE section=? AND content_key=?", Long.class, key, contentKey);
    audit(auth, "CREATE", key, id);
    return Map.of("id", Objects.requireNonNull(id), "created", true);
  }

  @PutMapping("/api/v1/customer-care/mobile-content/{section}/{id}")
  @Transactional
  public Map<String, Object> update(@PathVariable String section, @PathVariable Long id,
                                    @Valid @RequestBody ContentRequest request, Authentication auth) {
    String key = section(section);
    int changed = jdbc.update("""
        UPDATE webv9_mobile_content
        SET title=?,body=?,location=?,status=?,content_date=?,image_url=?,phone=?,email=?,
            sort_order=?,is_active=?,publish_state=?,archived_at=NULL
        WHERE section=? AND id=?
        """, request.title().trim(), request.body(), request.location(), request.status(),
        request.date(), request.image(), request.phone(), request.email(), value(request.sortOrder()),
        active(request.active()) ? 1 : 0, active(request.active()) ? "PUBLISHED" : "DRAFT", key, id);
    if (changed == 0) ensureContentExists(key, id);
    audit(auth, "UPDATE", key, id);
    return Map.of("updated", true, "id", id);
  }

  @DeleteMapping("/api/v1/customer-care/mobile-content/{section}/{id}")
  @Transactional
  public Map<String, Object> delete(@PathVariable String section, @PathVariable Long id, Authentication auth) {
    String key = section(section);
    if (singleton(key)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "About and Contact pages cannot be deleted");
    int changed = jdbc.update("DELETE FROM webv9_mobile_content WHERE section=? AND id=?", key, id);
    if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mobile content record not found");
    audit(auth, "DELETE", key, id);
    return Map.of("deleted", true, "id", id);
  }

  private Map<String, Object> changePublicationState(
      String section, Long id, String state, Authentication auth) {
    requireSuperAdmin(auth);
    String key = section(section);
    ensureContentExists(key, id);
    jdbc.update("""
        UPDATE webv9_mobile_content SET publish_state=?,is_active=?,
          archived_at=IF(?='ARCHIVED',CURRENT_TIMESTAMP,NULL)
        WHERE section=? AND id=?
        """, state, isPublished(state), state, key, id);
    audit(auth, state, key, id);
    return Map.of("id", id, "publishState", state);
  }

  private void validateContent(String section, AdminContentRequest request) {
    String body = blankToNull(request.description());
    String summary = blankToNull(request.summary());
    String article = blankToNull(request.article());
    switch (section) {
      case "PROJECTS" -> {
        requireText(body, "Project description is required.");
        requireText(blankToNull(request.status()), "Project status is required.");
        requireText(request.publicationDate() == null ? null : "date", "Project publication date is required.");
      }
      case "NEWS" -> {
        requireText(blankToNull(request.category()), "News category is required.");
        requireText(summary, "News summary is required.");
        requireText(article, "The full news article is required.");
        requireText(request.publicationDate() == null ? null : "date", "News publication date is required.");
      }
      case "EVENTS" -> {
        requireText(body, "Event description is required.");
        requireText(blankToNull(request.venue()), "Event venue is required.");
        requireText(blankToNull(request.eventTime()), "Event time is required.");
        requireText(request.publicationDate() == null ? null : "date", "Event date is required.");
        String status = Optional.ofNullable(blankToNull(request.status())).orElse("UPCOMING").toUpperCase(Locale.ROOT);
        if (!Set.of("UPCOMING", "COMPLETED", "CANCELLED").contains(status)) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
              "Event status must be Upcoming, Completed, or Cancelled.");
        }
      }
      case "VISITOR_INFO" -> requireText(body, "Visitor instructions are required.");
      case "DOCUMENTS" -> {
        requireText(body, "Document description is required.");
        requireText(blankToNull(request.documentCategory()), "Document category is required.");
        requireText(blankToNull(request.fileAssetId()), "Upload a document or visitor-information file.");
      }
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "This content workspace does not support About or Contact pages.");
    }
    safeImageUrl(request.image());
  }

  private void requireText(String value, String message) {
    if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private LocalDate contentDate(String section, LocalDate requested) {
    return requested == null && (section.equals("DOCUMENTS") || section.equals("VISITOR_INFO"))
        ? LocalDate.now() : requested;
  }

  private String contentBody(String section, AdminContentRequest request) {
    return section.equals("NEWS") ? blankToNull(request.article()) : blankToNull(request.description());
  }

  private String publicationState(String requested, String defaultValue) {
    String state = requested == null || requested.isBlank()
        ? defaultValue : requested.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("DRAFT", "PUBLISHED").contains(state)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Publication state must be Draft or Published. Use Archive to archive content.");
    }
    return state;
  }

  private String safeImageUrl(String image) {
    String value = blankToNull(image);
    if (value == null) return null;
    if (value.startsWith("/api/v1/mobile-content/assets/")
        && isUuid(value.substring("/api/v1/mobile-content/assets/".length()))) return value;
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "Upload an image before attaching it to mobile app content.");
  }

  private void ensureImageAssetExists(String image) {
    if (image == null) return;
    String id = image.substring("/api/v1/mobile-content/assets/".length());
    String contentType = jdbc.queryForObject(
        "SELECT content_type FROM webv9_mobile_content_assets WHERE id=?", String.class, id);
    if (contentType == null || !contentType.startsWith("image/")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected file is not a valid image.");
    }
  }

  private void ensureAssetExists(String id) {
    if (id == null || id.isBlank()) return;
    if (!isUuid(id)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded file reference is invalid.");
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM webv9_mobile_content_assets WHERE id=?", Integer.class, id);
    if (count == null || count == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded file could not be found. Upload it again.");
    }
  }

  private void ensureContentExists(String section, Long id) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM webv9_mobile_content WHERE section=? AND id=?", Integer.class, section, id);
    if (count == null || count == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mobile content record not found.");
    }
  }

  private boolean isPublishedAsset(String id) {
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*) FROM webv9_mobile_content
        WHERE publish_state='PUBLISHED' AND is_active=1
          AND (file_asset_id=? OR image_url=?)
        """, Integer.class, id, "/api/v1/mobile-content/assets/" + id);
    return count != null && count > 0;
  }

  private DetectedFile detectFile(byte[] bytes, String originalName) {
    String name = safeOriginalName(originalName).toLowerCase(Locale.ROOT);
    boolean pdf = startsWith(bytes, new byte[] {'%', 'P', 'D', 'F', '-'});
    boolean png = startsWith(bytes, new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
    boolean jpeg = startsWith(bytes, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
    boolean webp = bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
        && bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W'
        && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';

    if (pdf && name.endsWith(".pdf")) return new DetectedFile("application/pdf", ".pdf");
    if (png && name.endsWith(".png")) return new DetectedFile("image/png", ".png");
    if (jpeg && (name.endsWith(".jpg") || name.endsWith(".jpeg"))) {
      return new DetectedFile("image/jpeg", name.endsWith(".jpeg") ? ".jpeg" : ".jpg");
    }
    if (webp && name.endsWith(".webp")) return new DetectedFile("image/webp", ".webp");
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "Unsupported file. Upload a valid PDF, JPG, PNG, or WebP file.");
  }

  private boolean startsWith(byte[] bytes, byte[] prefix) {
    if (bytes.length < prefix.length) return false;
    for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
    return true;
  }

  private String safeOriginalName(String originalName) {
    if (originalName == null || originalName.isBlank()) return "mobile-content-file";
    String normalized = originalName.replace('\\', '/');
    String baseName = normalized.substring(normalized.lastIndexOf('/') + 1)
        .replaceAll("[\\p{Cntrl}]", "").trim();
    if (baseName.isBlank()) return "mobile-content-file";
    return baseName.length() > 255 ? baseName.substring(baseName.length() - 255) : baseName;
  }

  private List<Map<String, Object>> withAssetUrls(List<Map<String, Object>> records) {
    return records.stream().map(source -> {
      Map<String, Object> record = new LinkedHashMap<>(source);
      Object fileAssetId = record.get("fileAssetId");
      if (fileAssetId != null) record.put("fileUrl", "/api/v1/mobile-content/assets/" + fileAssetId);
      return record;
    }).toList();
  }

  private void requireSuperAdmin(Authentication auth) {
    if (!authenticatedSuperAdmin(auth)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "Mobile app content administration requires Super Admin access.");
    }
  }

  private boolean authenticatedSuperAdmin(Authentication auth) {
    return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())
        && projectAccess.isSuperAdmin(auth.getName());
  }

  private String section(String value) {
    String key = value == null ? "" : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    if (!SECTIONS.contains(key)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown mobile content section.");
    }
    return key;
  }

  private boolean singleton(String section) {
    return section.equals("ABOUT") || section.equals("CONTACT");
  }

  private int value(Integer value) { return value == null ? 0 : value; }
  private int sortOrder(Integer value) { return value == null ? 0 : value; }
  private boolean active(Boolean value) { return value == null || value; }
  private int isPublished(String state) { return "PUBLISHED".equals(state) ? 1 : 0; }
  private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private boolean isUuid(String value) {
    return value != null && value.matches(
        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  private void audit(Authentication auth, String action, String section, Long id) {
    audit(auth, action, section, String.valueOf(id));
  }

  private void audit(Authentication auth, String action, String section, String id) {
    jdbc.update("INSERT INTO system_logs(username,action,module,details) VALUES(?,?,?,?)",
        auth.getName(), action, "MOBILE_CONTENT_" + section, id);
  }

  private record DetectedFile(String contentType, String extension) {}
}
