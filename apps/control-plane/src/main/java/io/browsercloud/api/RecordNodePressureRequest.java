package io.browsercloud.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record RecordNodePressureRequest(
    @NotNull @DecimalMin("0.0") @DecimalMax("100.0") @Digits(integer = 3, fraction = 4)
        BigDecimal memoryPsiSomeAvg10,
    @NotNull @DecimalMin("0.0") @DecimalMax("100.0") @Digits(integer = 3, fraction = 4)
        BigDecimal memoryPsiFullAvg10,
    @NotNull @DecimalMin("0.0") @DecimalMax("100.0") @Digits(integer = 3, fraction = 4)
        BigDecimal cpuPsiSomeAvg10,
    @NotNull @DecimalMin("0.0") @DecimalMax("100.0") @Digits(integer = 3, fraction = 4)
        BigDecimal ioPsiFullAvg10,
    @Size(max = 128) String reason) {}
