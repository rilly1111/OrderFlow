package com.itheima.orderflow.order;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

record PayOrderRequest(@NotNull @Pattern(regexp = "ALIPAY|WECHAT|BANK_CARD") String paymentMethod) {

    @JsonAnySetter
    void rejectUnexpectedField(String name, JsonNode value) {
        throw new IllegalArgumentException("Only paymentMethod is allowed in the payment request");
    }
}
