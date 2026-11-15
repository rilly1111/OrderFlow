package com.itheima.orderflow.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

record CreateOrderResponse(String orderNo, OrderStatus status, BigDecimal payAmount, LocalDateTime createdAt,
                           String equipmentCode, String faultDescription) {
}
