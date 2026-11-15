package com.itheima.orderflow.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

record OrderDetailResponse(String orderNo, OrderStatus status, BigDecimal payAmount, String paymentMethod,
                           LocalDateTime paidAt, LocalDateTime createdAt, LocalDateTime updatedAt,
                           String equipmentCode, String faultDescription, LocalDateTime acceptanceDeadlineAt,
                           LocalDateTime startedAt, LocalDateTime overdueAt) {
}
