package com.districtone.webv9.service;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PlotInventoryRequest(
    @NotNull Long blockId,
    @DecimalMin("0.01") @Digits(integer = 4, fraction = 2) BigDecimal sizeMarla,
    @Pattern(regexp = "(?i)RESIDENTIAL|COMMERCIAL") String type,
    @Size(max = 100) String category,
    @Pattern(regexp = "(?i)AVAILABLE|UNALLOCATED|OPEN|ALLOCATED|BOOKED|SOLD|INACTIVE") String status
) {}
