package com.districtone.webv9.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Header-only collection totals based on Finance posting timestamps. */
@Repository
public class PostedCollectionQueryService {
  private final JdbcTemplate jdbc;

  public PostedCollectionQueryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Sum posted receipt headers in a half-open posting-time window; no allocation joins. */
  public BigDecimal sumBetween(LocalDate startInclusive, LocalDate endExclusive) {
    BigDecimal amount = jdbc.queryForObject("""
        SELECT COALESCE(SUM(amount), 0)
        FROM payments_receipts
        WHERE status = 'POSTED' AND posted_at >= ? AND posted_at < ?
        """, BigDecimal.class, startInclusive, endExclusive);
    return amount == null ? BigDecimal.ZERO : amount;
  }

  public BigDecimal sumAllPosted() {
    BigDecimal amount = jdbc.queryForObject("""
        SELECT COALESCE(SUM(amount), 0)
        FROM payments_receipts
        WHERE status = 'POSTED'
        """, BigDecimal.class);
    return amount == null ? BigDecimal.ZERO : amount;
  }
}
