package com.districtone.webv9.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Service
public class PasswordVerifier {
  private final PasswordEncoder bcrypt;
  public PasswordVerifier(PasswordEncoder bcrypt) { this.bcrypt = bcrypt; }

  public boolean matches(String raw, String stored) {
    if (stored == null || stored.isBlank()) return false;
    if (stored.startsWith("pbkdf2$")) return matchesPbkdf2(raw, stored);
    if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) return bcrypt.matches(raw, stored);
    return matchesSha256(raw, stored);
  }

  private boolean matchesPbkdf2(String raw, String stored) {
    try {
      String[] parts = stored.split("\\$", -1);
      if (parts.length != 4) return false;
      int iterations = Integer.parseInt(parts[1]);
      byte[] salt = Base64.getDecoder().decode(parts[2]);
      byte[] expected = Base64.getDecoder().decode(parts[3]);
      PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, expected.length * 8);
      byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
      return MessageDigest.isEqual(expected, actual);
    } catch (RuntimeException | java.security.GeneralSecurityException ex) {
      return false;
    }
  }

  private boolean matchesSha256(String raw, String stored) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return MessageDigest.isEqual(digest, hex(stored));
    } catch (java.security.GeneralSecurityException | IllegalArgumentException ex) {
      return false;
    }
  }

  private byte[] hex(String value) {
    if (value.length() != 64) throw new IllegalArgumentException("Invalid SHA-256 hash");
    byte[] result = new byte[32];
    for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
    return result;
  }
}
