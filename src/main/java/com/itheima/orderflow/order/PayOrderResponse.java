package com.itheima.orderflow.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

record PayOrderResponse(String orderNo, OrderStatus status, BigDecimal payAmount, String paymentMethod,
                        String paymentTransactionNo, LocalDateTime paidAt, LocalDateTime acceptanceDeadlineAt) {
}
