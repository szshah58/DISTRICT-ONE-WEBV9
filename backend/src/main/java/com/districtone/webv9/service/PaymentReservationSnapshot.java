package com.districtone.webv9.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentReservationSnapshot(
    Long paymentId,
    Long memberId,
    BigDecimal amount,
    String status,
    String receiptNo,
    String method,
    String paymentReference,
    String salesClassification,
    String bankInstrumentType,
    Long bankId,
    LocalDate depositDate,
    String depositReference,
    Long cashSiteId,
    Long enteredBy,
    Long verifiedBy
) {}
