package com.districtone.webv9.util;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class FinanceReceiptNumbering {
  private FinanceReceiptNumbering() {}

  public static String format(long sequence) {
    if (sequence <= 0) {
      throw new IllegalArgumentException("Receipt sequence must be positive");
    }
    return "DO-" + String.format("%06d", sequence);
  }

  public static void acquire(JdbcTemplate jdbc) {
    Integer acquired = jdbc.queryForObject("SELECT GET_LOCK('webv9_do_receipt_number', 10)", Integer.class);
    if (acquired == null || acquired != 1) {
      throw new IllegalStateException("Could not acquire the receipt-number lock");
    }
  }

  public static String next(JdbcTemplate jdbc) {
    Long last = jdbc.queryForObject("""
        SELECT COALESCE(MAX(CAST(SUBSTRING(receipt_no, 4) AS UNSIGNED)), 0)
        FROM payments_receipts
        WHERE receipt_no REGEXP '^DO-[0-9]+$'
        """, Long.class);
    return format((last == null ? 0L : last) + 1L);
  }

  public static void release(JdbcTemplate jdbc) {
    jdbc.queryForObject("SELECT RELEASE_LOCK('webv9_do_receipt_number')", Integer.class);
  }

  public static void releaseAfterTransaction(JdbcTemplate jdbc) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      release(jdbc);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCompletion(int status) { release(jdbc); }
    });
  }
}
