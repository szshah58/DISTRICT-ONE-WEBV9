package com.districtone.webv9;

import com.districtone.webv9.controller.MobileContentController;
import com.districtone.webv9.controller.MobileContentController.AdminContentRequest;
import com.districtone.webv9.security.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MobileContentControllerTest {
  @TempDir Path tempDirectory;

  private JdbcTemplate jdbc;
  private ProjectAccessService projectAccess;
  private MobileContentController controller;
  private Authentication admin;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    projectAccess = mock(ProjectAccessService.class);
    when(projectAccess.isSuperAdmin("admin")).thenReturn(true);
    controller = new MobileContentController(jdbc, projectAccess, tempDirectory.toString());
    admin = new UsernamePasswordAuthenticationToken("admin", "", List.of());
  }

  @Test
  void createsDraftNewsWithFullArticleAndAudit() {
    when(jdbc.queryForObject(contains("SELECT id FROM webv9_mobile_content"), eq(Long.class),
        eq("NEWS"), anyString())).thenReturn(41L);
    AdminContentRequest request = new AdminContentRequest(
        "Road update", "Infrastructure", "A brief update", null, "Full article",
        null, null, LocalDate.of(2026, 11, 1), null, null, null, null,
        null, null, 2, "DRAFT");

    Map<String, Object> result = controller.adminCreate("news", request, admin);

    assertThat(result).containsEntry("id", 41L).containsEntry("publishState", "DRAFT");
    verify(jdbc).update(contains("INSERT INTO webv9_mobile_content"), any(Object[].class));
    verify(jdbc).update(contains("INSERT INTO system_logs"), any(Object[].class));
  }

  @Test
  void editsExistingContentAndPersistsThePublicationState() {
    when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM webv9_mobile_content"),
        eq(Integer.class), eq("PROJECTS"), eq(9L))).thenReturn(1);
    AdminContentRequest request = new AdminContentRequest(
        "Updated project", null, null, "Construction update", null,
        "North", "IN_PROGRESS", LocalDate.of(2026, 10, 30), null, null, null,
        null, null, null, 1, "PUBLISHED");

    Map<String, Object> result = controller.adminUpdate("projects", 9L, request, admin);

    assertThat(result).containsEntry("updated", true).containsEntry("publishState", "PUBLISHED");
    verify(jdbc).update(contains("SET title=?,body=?"), any(Object[].class));
    verify(jdbc).update(contains("INSERT INTO system_logs"), any(Object[].class));
  }

  @Test
  void publishAndArchiveActionsChangeVisibilityAndAuditTheAction() {
    when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM webv9_mobile_content"),
        eq(Integer.class), eq("EVENTS"), eq(7L))).thenReturn(1);

    assertThat(controller.publish("events", 7L, admin)).containsEntry("publishState", "PUBLISHED");
    assertThat(controller.archive("events", 7L, admin)).containsEntry("publishState", "ARCHIVED");

    verify(jdbc).update(contains("SET publish_state=?"), eq("PUBLISHED"), eq(1), eq("PUBLISHED"), eq("EVENTS"), eq(7L));
    verify(jdbc).update(contains("SET publish_state=?"), eq("ARCHIVED"), eq(0), eq("ARCHIVED"), eq("EVENTS"), eq(7L));
    verify(jdbc, times(2)).update(contains("INSERT INTO system_logs"), any(Object[].class));
  }

  @Test
  void nonSuperAdminCannotCreateOrPublishMobileContent() {
    Authentication staff = new UsernamePasswordAuthenticationToken("staff", "", List.of());
    AdminContentRequest request = new AdminContentRequest(
        "Road update", "Infrastructure", "Summary", null, "Article",
        null, null, LocalDate.of(2026, 11, 1), null, null, null, null,
        null, null, 0, "DRAFT");

    assertThatThrownBy(() -> controller.adminCreate("news", request, staff))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Super Admin");
    assertThatThrownBy(() -> controller.adminList("news", "", "ALL", staff))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Super Admin");
    assertThatThrownBy(() -> controller.unpublish("news", 1L, staff))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Super Admin");
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void documentUploadValidatesPdfSignatureAndStoresOnlySafeGeneratedNames() {
    byte[] pdf = "%PDF-1.7\nvisitor guide".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    MockMultipartFile file = new MockMultipartFile(
        "file", "..\\unsafe\\visitor-guide.pdf", MediaType.APPLICATION_PDF_VALUE, pdf);

    Map<String, Object> result = controller.uploadAsset(file, "DOCUMENT", admin);

    assertThat(result.get("id").toString()).matches(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    assertThat(result).containsEntry("fileName", "visitor-guide.pdf")
        .containsEntry("contentType", "application/pdf")
        .containsEntry("size", pdf.length);
    verify(jdbc).update(contains("INSERT INTO webv9_mobile_content_assets"), any(Object[].class));
    verify(jdbc).update(contains("INSERT INTO system_logs"), any(Object[].class));
  }

  @Test
  void documentUploadRejectsMismatchedOrUnsupportedContent() {
    MockMultipartFile file = new MockMultipartFile(
        "file", "visitor-guide.pdf", MediaType.APPLICATION_PDF_VALUE, "not a pdf".getBytes());

    assertThatThrownBy(() -> controller.uploadAsset(file, "DOCUMENT", admin))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Unsupported file");
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void visitorDocumentRecordLinksAValidatedUploadedAsset() {
    String assetId = "22222222-2222-2222-2222-222222222222";
    when(jdbc.queryForObject(contains("webv9_mobile_content_assets WHERE id=?"),
        eq(Integer.class), eq(assetId))).thenReturn(1);
    when(jdbc.queryForObject(contains("SELECT id FROM webv9_mobile_content"),
        eq(Long.class), eq("DOCUMENTS"), anyString())).thenReturn(51L);
    AdminContentRequest request = new AdminContentRequest(
        "Visitor guide", null, null, "Entry and visitor instructions", null,
        null, null, null, null, null, null, null,
        "Visitor Guide", assetId, 0, "PUBLISHED");

    Map<String, Object> result = controller.adminCreate("documents", request, admin);

    assertThat(result).containsEntry("id", 51L).containsEntry("publishState", "PUBLISHED");
    verify(jdbc).update(contains("document_category,file_asset_id,publish_state"), any(Object[].class));
  }

  @Test
  void documentAssetCannotBeUsedAsAnInlineContentImage() {
    String assetId = "33333333-3333-3333-3333-333333333333";
    when(jdbc.queryForObject(contains("SELECT content_type FROM webv9_mobile_content_assets"),
        eq(String.class), eq(assetId))).thenReturn("application/pdf");
    AdminContentRequest request = new AdminContentRequest(
        "Project update", null, null, "Construction progress", null,
        "North", "IN_PROGRESS", LocalDate.of(2026, 10, 30), null, null, null,
        "/api/v1/mobile-content/assets/" + assetId, null, null, 0, "DRAFT");

    assertThatThrownBy(() -> controller.adminCreate("projects", request, admin))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not a valid image");
    verify(jdbc, never()).update(contains("INSERT INTO webv9_mobile_content"), any(Object[].class));
  }

  @Test
  void unpublishedDocumentAssetsAreNotPubliclyReadable() {
    String assetId = "44444444-4444-4444-4444-444444444444";
    when(jdbc.queryForMap(contains("FROM webv9_mobile_content_assets WHERE id=?"), eq(assetId)))
        .thenReturn(Map.of("storageName", assetId + ".pdf", "originalName", "guide.pdf",
            "contentType", "application/pdf", "fileSize", 15L));
    when(jdbc.queryForObject(contains("WHERE publish_state='PUBLISHED'"),
        eq(Integer.class), eq(assetId), eq("/api/v1/mobile-content/assets/" + assetId))).thenReturn(0);

    assertThatThrownBy(() -> controller.readAsset(assetId, null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("asset not found");
  }

  @Test
  void publicMobileFeedReturnsOnlyThePublishedFeedContract() throws Exception {
    when(jdbc.queryForList(contains("publish_state='PUBLISHED'"), any(Object[].class)))
        .thenReturn(List.of(Map.of(
            "id", 12L, "title", "Road update", "body", "Full article",
            "summary", "Brief summary", "publishState", "PUBLISHED")));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    mvc.perform(get("/api/v1/mobile-content/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(12))
        .andExpect(jsonPath("$[0].body").value("Full article"))
        .andExpect(jsonPath("$[0].summary").value("Brief summary"))
        .andExpect(jsonPath("$[0].publishState").value("PUBLISHED"));
  }

  @Test
  void eventValidationRequiresDateTimeAndVenueBeforeSaving() {
    AdminContentRequest request = new AdminContentRequest(
        "Community day", null, null, "Meet the project team", null,
        null, "UPCOMING", null, null, null, null, null,
        null, null, 0, "DRAFT");

    assertThatThrownBy(() -> controller.adminCreate("events", request, admin))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Event venue is required");
    verify(jdbc, never()).update(contains("INSERT INTO webv9_mobile_content"), any(Object[].class));
  }
}
