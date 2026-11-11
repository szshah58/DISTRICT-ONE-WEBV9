package com.districtone.webv9.service;

import java.math.BigDecimal;

/** A proposed payment allocation. One line is tied to exactly one receivable. */
public record AllocationDraft(
    Integer lineNo,
    Long registrationId,
    Long bookingId,
    Long plotId,
    Long chargeTypeId,
    Long receivableId,
    Long installmentScheduleId,
    BigDecimal allocatedAmount,
    String lineRemarks
) {}
