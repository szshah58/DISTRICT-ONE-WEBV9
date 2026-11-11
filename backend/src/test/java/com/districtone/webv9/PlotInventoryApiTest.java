package com.districtone.webv9;

import com.districtone.webv9.controller.OperationsController;
import com.districtone.webv9.service.PlotInventoryService;
import com.districtone.webv9.service.PaymentReversalService;
import com.districtone.webv9.security.ProjectAccessService;
import com.districtone.webv9.security.UserPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlotInventoryApiTest {
  private PlotInventoryService plots;
  private ProjectAccessService projectAccess;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    plots = Mockito.mock(PlotInventoryService.class);
    projectAccess = Mockito.mock(ProjectAccessService.class);
    when(projectAccess.canAccessProject(anyString(), anyLong())).thenReturn(true);
    when(projectAccess.projectForBlock(anyLong())).thenReturn(3L);
    OperationsController controller = new OperationsController(
        Mockito.mock(JdbcTemplate.class), Mockito.mock(PasswordEncoder.class), plots,
        Mockito.mock(PaymentReversalService.class), projectAccess,
        Mockito.mock(UserPermissionService.class));
    mvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void listsInventoryBySearchAndAvailability() throws Exception {
    when(plots.list("DOL-", null, "AVAILABLE")).thenReturn(List.of(
        Map.of("id", 1L, "projectId", 3L, "plotNo", "DOL-00001", "availability", "AVAILABLE")));

    mvc.perform(get("/api/v1/plots/inventory").param("q", "DOL-").param("availability", "AVAILABLE").principal(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].plotNo").value("DOL-00001"))
        .andExpect(jsonPath("$[0].availability").value("AVAILABLE"));

    verify(plots).list("DOL-", null, "AVAILABLE");
  }

  @Test
  void createsPlotThroughInventoryApi() throws Exception {
    when(plots.create(any())).thenReturn(Map.of(
        "id", 9L, "plotNo", "DOL-00009", "status", "AVAILABLE", "availability", "AVAILABLE"));

    mvc.perform(post("/api/v1/plots/inventory").principal(auth())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"blockId":2,"sizeMarla":5.0,"type":"Residential","category":"Corner"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plotNo").value("DOL-00009"))
        .andExpect(jsonPath("$.status").value("AVAILABLE"));

    verify(plots).create(any());
  }

  private UsernamePasswordAuthenticationToken auth() {
    return new UsernamePasswordAuthenticationToken("staff", "", List.of());
  }
}
