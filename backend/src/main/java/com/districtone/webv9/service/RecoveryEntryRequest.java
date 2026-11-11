package com.districtone.webv9.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Shared JSON contract for every WEBV9 payment recovery entry endpoint. */
public record RecoveryEntryRequest(
    @NotNull(message = "Member ID is required") Long memberId,
    @NotNull(message = "Payment amount is required") @Positive(message = "Amount must be greater than zero") BigDecimal amount,
    @NotNull(message = "Payment date is required") LocalDate paymentDate,
    @NotBlank(message = "Payment method is required") String method,
    Long bankId,
    @Size(max = 160) String bankName,
    @Size(max = 180) String receivingAccount,
    @Size(max = 120) String referenceNo,
    LocalDate depositDate,
    Long cashSiteId,
    @Size(max = 160) String cashSite,
    @Size(max = 500) String remarks,
    @Valid List<AllocationDraft> allocationLines,
    @Positive(message = "Allocation scope booking must be positive") Long allocationScopeBookingId,
    @Size(max = 40) String partyRoleType,
    @Size(max = 20) String salesClassification,
    @Size(max = 24) String bankInstrumentType,
    @Size(max = 120) String paymentReference
) {
  /** Keeps existing recovery callers source-compatible; Receipt Entry may additionally bind all lines to one booking. */
  public RecoveryEntryRequest(Long memberId, BigDecimal amount, LocalDate paymentDate, String method,
      Long bankId, String bankName, String receivingAccount, String referenceNo, LocalDate depositDate,
      Long cashSiteId, String cashSite, String remarks, List<AllocationDraft> allocationLines) {
    this(memberId, amount, paymentDate, method, bankId, bankName, receivingAccount, referenceNo,
        depositDate, cashSiteId, cashSite, remarks, allocationLines, null, null, null, null, null);
  }

  /** Preserves source compatibility for callers that already pass Receipt Entry scope fields. */
  public RecoveryEntryRequest(Long memberId, BigDecimal amount, LocalDate paymentDate, String method,
      Long bankId, String bankName, String receivingAccount, String referenceNo, LocalDate depositDate,
      Long cashSiteId, String cashSite, String remarks, List<AllocationDraft> allocationLines,
      Long allocationScopeBookingId, String partyRoleType) {
    this(memberId, amount, paymentDate, method, bankId, bankName, receivingAccount, referenceNo,
        depositDate, cashSiteId, cashSite, remarks, allocationLines, allocationScopeBookingId,
        partyRoleType, null, null, null);
  }
}
