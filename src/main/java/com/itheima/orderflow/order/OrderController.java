package com.itheima.orderflow.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
class OrderController {

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreateOrderResponse create(@Valid @RequestBody CreateOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        Order order = orderService.createOrder(userId, request);
        return new CreateOrderResponse(order.getOrderNo(), order.getStatus(), order.getPayAmount(),
                order.getCreatedAt(), order.getEquipmentCode(), order.getFaultDescription());
    }

    @PostMapping("/{orderNo}/pay")
    PayOrderResponse pay(@PathVariable("orderNo") String orderNo, @Valid @RequestBody PayOrderRequest request,
                         @AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        return orderService.payOrder(orderNo, userId, request.paymentMethod());
    }

    @PostMapping("/{orderNo}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable("orderNo") String orderNo, @AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        orderService.cancelOrder(orderNo, userId);
    }

    @GetMapping
    OrderListResponse list(@RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
                           @RequestParam(name = "size", defaultValue = "10") @Min(1) @Max(50) int size,
                           @RequestParam(name = "status", required = false) String status,
                           @AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        return orderService.listOrders(userId, page, size, parseStatus(status));
    }

    @GetMapping("/{orderNo}")
    OrderDetailResponse detail(@PathVariable("orderNo") String orderNo, @AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        Order order = orderService.getOrderDetail(orderNo, userId);
        return new OrderDetailResponse(order.getOrderNo(), order.getStatus(), order.getPayAmount(),
                order.getPaymentMethod(), order.getPaidAt(), order.getCreatedAt(), order.getUpdatedAt(),
                order.getEquipmentCode(), order.getFaultDescription(), order.getAcceptanceDeadlineAt(),
                order.getStartedAt(), order.getOverdueAt());
    }

    private static OrderStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return OrderStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid order status");
        }
    }
}
