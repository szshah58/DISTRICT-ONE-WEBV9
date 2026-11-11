package com.districtone.webv9.service;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.Map;

public record CustomerLedgerReportRequest(
    @NotNull Long memberId,
    Long bookingId,
    @NotNull LocalDate toDate,
    String reportType,
    Map<String, Boolean> options
) {}
