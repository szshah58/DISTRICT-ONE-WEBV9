package com.districtone.webv9.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceVerificationAuditService {
  private final JdbcTemplate jdbc;

  public FinanceVerificationAuditService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailedVerification(Long paymentId, String username, String details) {
    jdbc.update("INSERT INTO system_logs(username, action, module, details) VALUES (?, ?, ?, ?)",
        username == null ? "SYSTEM" : username, "VERIFY_REJECTED", "FINANCE",
        limit("Payment " + paymentId + ": " + details));
  }

  private String limit(String value) {
    return value.length() <= 500 ? value : value.substring(0, 500);
  }
}
