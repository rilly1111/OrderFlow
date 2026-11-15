package com.itheima.orderflow;

import com.itheima.orderflow.order.Order;
import com.itheima.orderflow.order.OrderMapper;
import com.itheima.orderflow.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {AuthIntegrationTests.TEST_JWT_PROPERTY, AuthIntegrationTests.TEST_NO_SCHEDULE_PROPERTY})
@Transactional
class OrderInsertTest {

    private final String username = "order-test-" + UUID.randomUUID();
    private final String orderNo = UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private OrderMapper orderMapper;

    @Test
    void insertsOrderAndBackfillsAutoIncrementId() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("INSERT INTO accounts (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
                username, passwords.encode(UUID.randomUUID().toString()), "USER", now);
        Long userId = jdbc.queryForObject("SELECT id FROM accounts WHERE username = ?", Long.class, username);

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setPayAmount(new BigDecimal("12.30"));
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setVersion(0);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        assertThat(order.getId()).isNull();
        int affectedRows = orderMapper.insert(order);
        assertThat(affectedRows).isEqualTo(1);
        assertThat(order.getId()).isPositive();

        Order saved = orderMapper.selectById(order.getId());
        assertThat(saved).usingRecursiveComparison().isEqualTo(order);
    }

    @AfterTransaction
    void leavesNoTestRowsAfterRollback() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE order_no = ?", Long.class, orderNo))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE username = ?", Long.class, username))
                .isZero();
    }
}
