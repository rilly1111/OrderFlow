package com.itheima.orderflow.order;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotNull
        @Positive
        @Digits(integer = 10, fraction = 2)
        BigDecimal payAmount,
        @NotBlank @Size(max = 64) String equipmentCode,
        @NotBlank @Size(max = 500) String faultDescription
) {
}
