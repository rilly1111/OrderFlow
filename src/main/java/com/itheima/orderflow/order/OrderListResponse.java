package com.itheima.orderflow.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

record OrderListResponse(long total, int pages, int pageNum, int pageSize, List<Item> records) {

    record Item(String orderNo, OrderStatus status, BigDecimal payAmount, LocalDateTime createdAt, String equipmentCode) {
    }
}
