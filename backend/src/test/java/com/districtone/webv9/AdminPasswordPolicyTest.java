package com.districtone.webv9;

import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import com.districtone.webv9.security.AdminPasswordPolicy;

class AdminPasswordPolicyTest {
  @Test
  void acceptsPasswordMeetingAllRequirements() {
    AdminPasswordPolicy.validate("Riv3r!Stone-Cedar");
  }

  @Test
  void rejectsCommonPasswordEvenWhenItMeetsCharacterRequirements() {
    assertThrows(ResponseStatusException.class,
        () -> AdminPasswordPolicy.validate("Welcome!Secure2026"));
  }

  @Test
  void rejectsPasswordWithoutUppercase() {
    assertThrows(ResponseStatusException.class,
        () -> AdminPasswordPolicy.validate("secureriver2026!"));
  }

  @Test
  void rejectsPasswordWithoutLowercase() {
    assertThrows(ResponseStatusException.class,
        () -> AdminPasswordPolicy.validate("SECURERIVER2026!"));
  }

  @Test
  void rejectsPasswordWithoutDigit() {
    assertThrows(ResponseStatusException.class,
        () -> AdminPasswordPolicy.validate("SecureRiver!Stone"));
  }

  @Test
  void rejectsPasswordWithoutSpecialCharacter() {
    assertThrows(ResponseStatusException.class,
        () -> AdminPasswordPolicy.validate("SecureRiverStone2026"));
  }

  @Test
  void rejectsPasswordBelowMinimumLength() {
    assertThrows(ResponseStatusException.class,
        () -> AdminPasswordPolicy.validate("Abcdef1!"));
  }
}
