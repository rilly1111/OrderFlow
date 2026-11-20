package com.itheima.orderflow.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.itheima.orderflow.fulfillment.DashboardStatsChanged;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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

/**
 * 负责维修申请的业务状态流转，由 Spring 管理并为事务方法创建代理。
 * 支付、开始处理和成功标记超时会影响看板，在各自事务中发布统计变更事件。
 */
@Service
class OrderService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper logMapper;
    private final Duration acceptanceSla;
    private final Clock fulfillmentClock;
    // Spring 注入的事件发布器；提交后监听器负责 Redis 失效，当前业务方法只发布变更通知。
    private final ApplicationEventPublisher events;

    OrderService(OrderMapper orderMapper, OrderStatusLogMapper logMapper,
                 @Value("${indusflow.fulfillment.acceptance-sla}") Duration acceptanceSla, Clock fulfillmentClock,
                 ApplicationEventPublisher events) {
        if (acceptanceSla.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("Acceptance SLA must be at least one millisecond");
        }
        this.orderMapper = orderMapper;
        this.logMapper = logMapper;
        this.acceptanceSla = acceptanceSla;
        this.fulfillmentClock = fulfillmentClock;
        this.events = events;
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

    /**
     * 模拟支付并设置受理截止时间，返回支付结果。
     * orderNo 和 paymentMethod 来自请求，userId 由 Controller 从已认证的 JWT 身份取得。
     * Spring 事务同时保护订单更新与状态日志，任一操作失败都会回滚，提交后才递增缓存版本。
     */
    @Transactional
    public PayOrderResponse payOrder(String orderNo, long userId, String paymentMethod) {
        Order order = getOrderDetail(orderNo, userId);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not pending payment");
        }

        String transactionNo = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime paidAt = LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime acceptanceDeadline = paidAt.plus(acceptanceSla).truncatedTo(ChronoUnit.MILLIS);

        // SQL 再次匹配原状态和 version，避免查询后发生并发修改仍覆盖其他请求的结果。
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

        // 支付增加有效 SLA 申请。此处仍在数据库事务中，AFTER_COMMIT 监听器等成功提交后才 INCR。
        events.publishEvent(new DashboardStatsChanged());
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

    /**
     * 管理员开始处理申请；orderNo 来自路径，adminId 来自 Controller 读取的已认证身份。
     * 晚受理时先标记超时，再进入处理中，两次更新与日志共用一个事务，正常结束后由 Controller 返回 204。
     */
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
            // 截止后抢在扫描前开始处理，也要保留超时事实；两次流转共用本次开始处理时间。
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
        // 两次可能的流转和日志都已成功执行，统一发布一次事件；提交失败时不会递增缓存版本。
        events.publishEvent(new DashboardStatsChanged());
    }

    /**
     * 将扫描选出的单笔候选申请标记为超时，参数来自扫描任务读取的订单和本批截止时间。
     * 返回 true 表示更新和日志写入成功，false 表示候选已变化；事务提交后才通知缓存失效。
     * Spring 从扫描任务调用此代理方法，使每笔记录的更新和日志具备独立事务。
     */
    @Transactional
    public boolean markOverdue(long orderId, int version, LocalDateTime deadline, LocalDateTime scanCutoff) {
        LocalDateTime transitionAt = LocalDateTime.ofInstant(fulfillmentClock.instant(), BUSINESS_ZONE)
                .truncatedTo(ChronoUnit.MILLIS);
        boolean updated = applyOverdue(orderId, version, deadline, scanCutoff, transitionAt);
        if (updated) {
            // 只有本次条件更新成功才改变每日超时标记数；旧候选或并发竞争失败不发布事件。
            events.publishEvent(new DashboardStatsChanged());
        }
        return updated;
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
