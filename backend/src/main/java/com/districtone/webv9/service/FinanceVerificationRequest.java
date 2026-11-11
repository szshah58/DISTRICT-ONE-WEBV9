package com.districtone.webv9.service;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record FinanceVerificationRequest(
    @NotNull @DecimalMin(value = "0.01") BigDecimal verifiedSourceAmount,
    @Size(max = 255) String sourceReference,
    @NotBlank @Size(max = 500) String remarks
) {}
