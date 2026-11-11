package com.districtone.webv9.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FinanceReceiptNumberingTest {
  @Test
  void formatsPositiveSequenceAsDoReceipt() {
    assertEquals("DO-000001", FinanceReceiptNumbering.format(1));
    assertEquals("DO-000010", FinanceReceiptNumbering.format(10));
    assertEquals("DO-001000", FinanceReceiptNumbering.format(1000));
  }

  @Test
  void rejectsNonPositiveSequence() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> FinanceReceiptNumbering.format(0));
    assertTrue(ex.getMessage().contains("positive"));
  }
}
