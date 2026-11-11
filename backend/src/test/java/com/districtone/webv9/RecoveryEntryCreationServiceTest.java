package com.districtone.webv9;

import com.districtone.webv9.service.PaymentAllocationReservationService;
import com.districtone.webv9.service.AllocationDraft;
import com.districtone.webv9.service.RecoveryEntryCreationService;
import com.districtone.webv9.service.RecoveryEntryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RecoveryEntryCreationServiceTest {
  private JdbcTemplate jdbc;
  private PaymentAllocationReservationService reservations;
  private RecoveryEntryCreationService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    reservations = mock(PaymentAllocationReservationService.class);
    service = new RecoveryEntryCreationService(jdbc, reservations);
  }

  @Test
  void bankTransferRequiresBankDocumentOrTransactionReferenceBeforeAnyWrites() {
    RecoveryEntryRequest request = new RecoveryEntryRequest(
        10L, new BigDecimal("25.00"), LocalDate.now(), "BANK_TRANSFER",
        2L, "Bank", null, " ", LocalDate.now(), null, null, null, null);

    assertThatThrownBy(() -> service.create(request, "recovery-user"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("requires a bank document number");

    verifyNoInteractions(jdbc, reservations);
  }

  @Test
  void bankTransferRequiresReceivingBankAndDepositDateBeforeAnyWrites() {
    RecoveryEntryRequest missingBank = new RecoveryEntryRequest(
        10L, new BigDecimal("25.00"), LocalDate.now(), "ONLINE",
        null, null, null, "TX-1", LocalDate.now(), null, null, null, null);
    assertThatThrownBy(() -> service.create(missingBank, "recovery-user"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("requires a receiving bank");

    RecoveryEntryRequest missingDate = new RecoveryEntryRequest(
        10L, new BigDecimal("25.00"), LocalDate.now(), "CHEQUE",
        2L, null, null, "CHQ-1", null, null, null, null, null);
    assertThatThrownBy(() -> service.create(missingDate, "recovery-user"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("requires a deposit or instrument date");

    verifyNoInteractions(jdbc, reservations);
  }

  @Test
  void cashRequiresCollectionSiteAndUnknownMethodIsRejectedBeforeAnyWrites() {
    RecoveryEntryRequest missingSite = new RecoveryEntryRequest(
        10L, new BigDecimal("25.00"), LocalDate.now(), "CASH",
        null, null, null, null, null, null, null, null, null);
    assertThatThrownBy(() -> service.create(missingSite, "recovery-user"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("requires a collection site");

    RecoveryEntryRequest unknownMethod = new RecoveryEntryRequest(
        10L, new BigDecimal("25.00"), LocalDate.now(), "OTHER",
        null, null, null, null, null, null, null, null, null);
    assertThatThrownBy(() -> service.create(unknownMethod, "recovery-user"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Unsupported payment method");

    verifyNoInteractions(jdbc, reservations);
  }

  @Test
  void receiptEntryAllocationsMustStayInTheSelectedBookingAndCannotRepeatAChargeHead() {
    AllocationDraft selectedBookingLine = new AllocationDraft(1, 20L, 30L, 40L, 5L, 60L,
        null, new BigDecimal("10.00"), null);
    AllocationDraft otherBookingLine = new AllocationDraft(2, 20L, 31L, 41L, 6L, 61L,
        null, new BigDecimal("15.00"), null);
    RecoveryEntryRequest crossBooking = new RecoveryEntryRequest(10L, new BigDecimal("25.00"),
        LocalDate.now(), "CASH", null, null, null, null, null, 1L, null, null,
        List.of(selectedBookingLine, otherBookingLine), 30L, "Residential", "Sales", null, null);

    assertThatThrownBy(() -> service.create(crossBooking, "recovery-user"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("selected sale/booking");

    AllocationDraft repeatedChargeLine = new AllocationDraft(2, 20L, 30L, 40L, 5L, 62L,
        null, new BigDecimal("15.00"), null);
    RecoveryEntryRequest duplicateCharge = new RecoveryEntryRequest(10L, new BigDecimal("25.00"),
        LocalDate.now(), "CASH", null, null, null, null, null, 1L, null, null,
        List.of(selectedBookingLine, repeatedChargeLine), 30L, "Residential", "Sales", null, null);

    assertThatThrownBy(() -> service.create(duplicateCharge, "recovery-user"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("each charge head only once");
    verifyNoInteractions(jdbc, reservations);
  }
}
