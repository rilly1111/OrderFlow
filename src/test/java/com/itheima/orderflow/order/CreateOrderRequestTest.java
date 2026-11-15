package com.itheima.orderflow.order;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CreateOrderRequestTest {

    @ParameterizedTest
    @ValueSource(strings = {"1", "1.60", "0.01", "9999999999.99"})
    void acceptsPositiveAmountsThatFitTheColumn(String amount) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            CreateOrderRequest request = new CreateOrderRequest(new BigDecimal(amount), "PUMP-001", "Abnormal vibration");
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1.00", "1.605", "10000000000.00"})
    void rejectsMissingOrOutOfRangeAmounts(String amount) {
        BigDecimal payAmount = amount == null ? null : new BigDecimal(amount);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            CreateOrderRequest request = new CreateOrderRequest(payAmount, "PUMP-001", "Abnormal vibration");
            assertThat(factory.getValidator().validate(request)).isNotEmpty();
        }
    }
}
