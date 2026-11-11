package com.districtone.webv9.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared policy for Admin account creation and every Admin password reset. */
public final class AdminPasswordPolicy {
  private static final List<String> WEAK_FRAGMENTS = List.of(
      "password", "admin", "districtone", "welcome", "letmein", "qwerty",
      "changeme", "default", "iloveyou", "sunshine", "abc123", "123456",
      "passw0rd", "administrator", "football", "baseball", "dragon", "monkey",
      "shadow", "master", "secret", "login", "freedom", "trustno1", "starwars",
      "summer", "winter", "spring", "autumn", "computer", "manager", "company");

  private AdminPasswordPolicy() {}

  public static void validate(String password) {
    if (password == null || password.length() < 8) {
      reject("Password must contain at least 8 characters.");
    }
    if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
      reject("Password must be no longer than 72 UTF-8 bytes for BCrypt.");
    }

    boolean letter = false;
    boolean digit = false;
    boolean special = false;
    for (int i = 0; i < password.length(); i++) {
      char c = password.charAt(i);
      letter |= (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
      digit |= c >= '0' && c <= '9';
      special |= !(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9');
    }
    if (!letter || !digit || !special) {
      reject("Password must include a letter, a number, and a special character.");
    }

    String normalized = password.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    if (WEAK_FRAGMENTS.stream().anyMatch(normalized::contains)
        || normalized.matches(".*(.)\\1{5,}.*")
        || List.of("012345", "123456", "234567", "345678", "456789", "abcdef",
            "bcdefg", "cdefgh", "qwerty", "asdfgh", "zxcvbn")
            .stream().anyMatch(normalized::contains)) {
      reject("Choose a less common password without predictable words or sequences.");
    }
  }

  private static void reject(String message) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
