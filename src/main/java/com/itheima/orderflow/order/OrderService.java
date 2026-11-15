package com.itheima.orderflow.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
class OrderService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper logMapper;
    private final Duration acceptanceSla;
    private final Clock fulfillmentClock;

    OrderService(OrderMapper orderMapper, OrderStatusLogMapper logMapper,
                 @Value("${indusflow.fulfillment.acceptance-sla}") Duration acceptanceSla, Clock fulfillmentClock) {
        if (acceptanceSla.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("Acceptance SLA must be at least one millisecond");
        }
        this.orderMapper = orderMapper;
        this.logMapper = logMapper;
        this.acceptanceSla = acceptanceSla;
        this.fulfillmentClock = fulfillmentClock;
    }

    @Transactional
    public Order createOrder(long userId, CreateOrderRequest request) {
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS);
        Order order = new Order();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setPayAmount(request.payAmount());
        order.setEquipmentCode(request.equipmentCode());
        order.setFaultDescription(request.faultDescription());
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setVersion(0);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        if (orderMapper.insert(order) != 1 || order.getId() == null) {
            throw new IllegalStateException("Failed to insert order and obtain its ID");
        }

        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(order.getId());
        log.setFromStatus(null);
        log.setToStatus(order.getStatus());
        log.setOperatorType("USER");
        log.setOperatorId(userId);
        log.setRemark("订单创建");
        log.setCreatedAt(now);
        if (logMapper.insert(log) != 1) {
            throw new IllegalStateException("Failed to insert initial order status log");
        }

        return order;
    }

    public Order getOrderDetail(String orderNo, long userId) {
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getOrderNo, orderNo)
                .eq(Order::getUserId, userId));
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return order;
    }

    @Transactional
    public PayOrderResponse payOrder(String orderNo, long userId, String paymentMethod) {
        Order order = getOrderDetail(orderNo, userId);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not pending payment");
        }

        String transactionNo = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime paidAt = LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime acceptanceDeadline = paidAt.plus(acceptanceSla).truncatedTo(ChronoUnit.MILLIS);

        // Match the status and version again in SQL before changing the order.
        int updatedRows = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getUserId, userId)
                .eq(Order::getStatus, OrderStatus.PENDING_PAYMENT)
                .eq(Order::getVersion, order.getVersion())
                .set(Order::getStatus, OrderStatus.PAID)
                .set(Order::getPaymentMethod, paymentMethod)
                .set(Order::getPaymentTransactionNo, transactionNo)
                .set(Order::getPaidAt, paidAt)
                .set(Order::getAcceptanceDeadlineAt, acceptanceDeadline)
                .set(Order::getUpdatedAt, paidAt)
                .setIncrBy(Order::getVersion, 1));
        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed before payment was applied");
        }
        if (updatedRows != 1) {
            throw new IllegalStateException("Payment updated " + updatedRows + " orders; expected exactly one");
        }

        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(order.getId());
        log.setFromStatus(OrderStatus.PENDING_PAYMENT);
        log.setToStatus(OrderStatus.PAID);
        log.setOperatorType("USER");
        log.setOperatorId(userId);
        log.setCreatedAt(paidAt);
        log.setRemark("模拟支付成功");
        if (logMapper.insert(log) != 1) {
            throw new IllegalStateException("Failed to insert payment status log");
        }

        return new PayOrderResponse(order.getOrderNo(), OrderStatus.PAID, order.getPayAmount(), paymentMethod,
                transactionNo, paidAt, acceptanceDeadline);
    }

    @Transactional
    public void cancelOrder(String orderNo, long userId) {
        Order order = getOrderDetail(orderNo, userId);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not pending payment");
        }

        LocalDateTime cancelledAt = LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS);
        int updatedRows = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getUserId, userId)
                .eq(Order::getStatus, OrderStatus.PENDING_PAYMENT)
                .eq(Order::getVersion, order.getVersion())
                .set(Order::getStatus, OrderStatus.CANCELLED)
                .set(Order::getUpdatedAt, cancelledAt)
                .setIncrBy(Order::getVersion, 1));
        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed before cancellation was applied");
        }
        if (updatedRows != 1) {
            throw new IllegalStateException("Cancellation updated " + updatedRows + " orders; expected exactly one");
        }

        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(order.getId());
        log.setFromStatus(OrderStatus.PENDING_PAYMENT);
        log.setToStatus(OrderStatus.CANCELLED);
        log.setOperatorType("USER");
        log.setOperatorId(userId);
        log.setCreatedAt(cancelledAt);
        log.setRemark("用户取消订单");
        if (logMapper.insert(log) != 1) {
            throw new IllegalStateException("Failed to insert cancellation status log");
        }
    }

    private Order findFulfillmentOrder(String orderNo) {
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return order;
    }

    @Transactional
    public void startOrder(String orderNo, long adminId) {
        Order order = findFulfillmentOrder(orderNo);
        OrderStatus previous = order.getStatus();
        if (previous != OrderStatus.PAID && previous != OrderStatus.OVERDUE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order cannot be started from its current status");
        }

        LocalDateTime startedAt = businessNow();
        LocalDateTime deadline = order.getAcceptanceDeadlineAt();
        int version = order.getVersion();
        if (previous == OrderStatus.PAID && deadline != null && !deadline.isAfter(startedAt)) {
            // Preserve the missed SLA even when an administrator starts before the next scan.
            if (!applyOverdue(order.getId(), version, deadline, startedAt, startedAt)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed before fulfillment could start");
            }
            previous = OrderStatus.OVERDUE;
            version++;
        }

        int updatedRows = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, previous)
                .eq(Order::getVersion, version)
                .set(Order::getStatus, OrderStatus.IN_PROGRESS)
                .set(Order::getStartedAt, startedAt)
                .set(Order::getUpdatedAt, startedAt)
                .setIncrBy(Order::getVersion, 1));
        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed before fulfillment could start");
        }
        if (updatedRows != 1) {
            throw new IllegalStateException("Fulfillment start updated " + updatedRows + " orders; expected exactly one");
        }

        insertFulfillmentLog(order.getId(), previous, OrderStatus.IN_PROGRESS, "ADMIN", adminId,
                startedAt, "管理员开始处理");
    }

    @Transactional
    public boolean markOverdue(long orderId, int version, LocalDateTime deadline, LocalDateTime scanCutoff) {
        LocalDateTime transitionAt = LocalDateTime.ofInstant(fulfillmentClock.instant(), BUSINESS_ZONE)
                .truncatedTo(ChronoUnit.MILLIS);
        return applyOverdue(orderId, version, deadline, scanCutoff, transitionAt);
    }

    private boolean applyOverdue(long orderId, int version, LocalDateTime deadline,
                                 LocalDateTime cutoff, LocalDateTime transitionAt) {
        int updatedRows = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, OrderStatus.PAID)
                .eq(Order::getVersion, version)
                .eq(Order::getAcceptanceDeadlineAt, deadline)
                .le(Order::getAcceptanceDeadlineAt, cutoff)
                .set(Order::getStatus, OrderStatus.OVERDUE)
                .setSql("overdue_at = COALESCE(overdue_at, {0})", transitionAt)
                .set(Order::getUpdatedAt, transitionAt)
                .setIncrBy(Order::getVersion, 1));
        if (updatedRows == 0) {
            return false;
        }
        if (updatedRows != 1) {
            throw new IllegalStateException("Overdue transition updated " + updatedRows + " orders; expected exactly one");
        }

        insertFulfillmentLog(orderId, OrderStatus.PAID, OrderStatus.OVERDUE, "SYSTEM", null, transitionAt, "SLA受理超时");
        return true;
    }

    @Transactional
    public void completeOrder(String orderNo, long adminId) {
        Order order = findFulfillmentOrder(orderNo);
        if (order.getStatus() != OrderStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not in progress");
        }

        LocalDateTime completedAt = businessNow();
        int updatedRows = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, OrderStatus.IN_PROGRESS)
                .eq(Order::getVersion, order.getVersion())
                .set(Order::getStatus, OrderStatus.COMPLETED)
                .set(Order::getUpdatedAt, completedAt)
                .setIncrBy(Order::getVersion, 1));
        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed before completion was applied");
        }
        if (updatedRows != 1) {
            throw new IllegalStateException("Completion updated " + updatedRows + " orders; expected exactly one");
        }

        insertFulfillmentLog(order.getId(), OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED, "ADMIN", adminId,
                completedAt, "管理员完成订单");
    }

    private void insertFulfillmentLog(long orderId, OrderStatus from, OrderStatus to, String operatorType,
                                      Long operatorId, LocalDateTime now, String remark) {
        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(orderId);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorType(operatorType);
        log.setOperatorId(operatorId);
        log.setCreatedAt(now);
        log.setRemark(remark);
        if (logMapper.insert(log) != 1) {
            throw new IllegalStateException("Failed to insert fulfillment status log for " + to);
        }
    }

    static LocalDateTime businessNow() {
        return LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS);
    }

    public OrderListResponse listOrders(long userId, int page, int size, OrderStatus status) {
        var wrapper = Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId)
                .eq(status != null, Order::getStatus, status)
                .orderByDesc(Order::getCreatedAt, Order::getId);

        // Avoid the int multiplication overflow in PageHelper 6.1.1's default offset calculation.
        long startRow = (page - 1L) * size;
        PageHelper.startPage(page, size).setStartRow(startRow).setEndRow(startRow + size);
        List<Order> orders = orderMapper.selectList(wrapper);
        PageInfo<Order> pageInfo = new PageInfo<>(orders);
        List<OrderListResponse.Item> records = pageInfo.getList().stream()
                .map(order -> new OrderListResponse.Item(order.getOrderNo(), order.getStatus(),
                        order.getPayAmount(), order.getCreatedAt(), order.getEquipmentCode()))
                .toList();
        return new OrderListResponse(pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getPageNum(),
                pageInfo.getPageSize(), records);
    }
}
