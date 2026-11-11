package com.districtone.webv9;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import com.districtone.webv9.controller.InitialAdminSetupController;

class InitialAdminSetupControllerTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final InitialAdminSetupController controller =
      new InitialAdminSetupController(jdbc, new BCryptPasswordEncoder(), "test-only-setup-token");

  @Test
  void bootstrapAndResetRequirePasswordConfirmation() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    var body = new InitialAdminSetupController.SetupRequest("A-Strong-Password2026!", "Another-Password2026!", true, null);

    assertThrows(ResponseStatusException.class,
        () -> controller.setup("test-only-setup-token", body, request));
    verifyNoInteractions(jdbc);
  }

  @Test
  void bootstrapAndResetEnforceTheComplexityPolicy() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    var body = new InitialAdminSetupController.SetupRequest("Welcome!Secure2026", "Welcome!Secure2026", true, null);

    assertThrows(ResponseStatusException.class,
        () -> controller.setup("test-only-setup-token", body, request));
    verifyNoInteractions(jdbc);
  }
}

