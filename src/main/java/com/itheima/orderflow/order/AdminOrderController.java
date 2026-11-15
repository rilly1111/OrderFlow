package com.itheima.orderflow.order;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderController {

    private final OrderService orderService;

    AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/{orderNo}/start")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void start(@PathVariable("orderNo") String orderNo, @AuthenticationPrincipal Jwt jwt) {
        orderService.startOrder(orderNo, Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/{orderNo}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void complete(@PathVariable("orderNo") String orderNo, @AuthenticationPrincipal Jwt jwt) {
        long adminId = Long.parseLong(jwt.getSubject());
        orderService.completeOrder(orderNo, adminId);
    }
}
