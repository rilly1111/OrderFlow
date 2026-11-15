package com.itheima.orderflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.orderflow.order.Order;
import com.itheima.orderflow.order.OrderMapper;
import com.itheima.orderflow.order.OrderStatus;
import com.itheima.orderflow.order.OrderStatusLog;
import com.itheima.orderflow.order.OrderStatusLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.itheima.orderflow.order.OrderStatus.CANCELLED;
import static com.itheima.orderflow.order.OrderStatus.PENDING_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {AuthIntegrationTests.TEST_JWT_PROPERTY, AuthIntegrationTests.TEST_NO_SCHEDULE_PROPERTY})
@AutoConfigureMockMvc
@Transactional
class OrderCancellationIntegrationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 1, 8, 0);
    private static final int ORIGINAL_VERSION = 7;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderStatusLogMapper logMapper;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelsPendingOrderAndPreservesItsOriginalData(boolean withPaymentInfo) throws Exception {
        Account customer = loginAs("USER");
        // Include a stored row with populated payment columns to catch accidental clearing.
        Order pending = insertOrder(customer.id(), PENDING_PAYMENT, withPaymentInfo);
        LocalDateTime before = LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.MILLIS);

        mvc.perform(authorizedCancel(pending, customer))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        LocalDateTime after = LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.MILLIS);
        Order cancelled = orderMapper.selectById(pending.getId());
        assertThat(cancelled.getStatus()).isEqualTo(CANCELLED);
        assertThat(cancelled.getVersion()).isEqualTo(ORIGINAL_VERSION + 1);
        assertThat(cancelled.getUpdatedAt()).isBetween(before, after);
        assertThat(cancelled).usingRecursiveComparison()
                .ignoringFields("status", "version", "updatedAt")
                .isEqualTo(pending);
        assertThat(logsFor(pending)).singleElement().satisfies(log -> {
            assertThat(log.getOrderId()).isEqualTo(pending.getId());
            assertThat(log.getFromStatus()).isEqualTo(PENDING_PAYMENT);
            assertThat(log.getToStatus()).isEqualTo(CANCELLED);
            assertThat(log.getOperatorType()).isEqualTo("USER");
            assertThat(log.getOperatorId()).isEqualTo(customer.id());
            assertThat(log.getRemark()).isEqualTo("用户取消订单");
            assertThat(log.getCreatedAt()).isEqualTo(cancelled.getUpdatedAt());
        });
    }

    @Test
    void refusesToCancelTheSameOrderAgain() throws Exception {
        Account customer = loginAs("USER");
        Order pending = insertOrder(customer.id(), PENDING_PAYMENT, false);
        mvc.perform(authorizedCancel(pending, customer)).andExpect(status().isNoContent());
        Order cancelled = orderMapper.selectById(pending.getId());

        mvc.perform(authorizedCancel(pending, customer)).andExpect(status().isConflict());

        assertThat(orderMapper.selectById(pending.getId())).usingRecursiveComparison().isEqualTo(cancelled);
        assertThat(logsFor(pending)).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "COMPLETED"})
    void refusesToCancelPaidAndCompletedOrders(OrderStatus state) throws Exception {
        Account customer = loginAs("USER");
        Order order = insertOrder(customer.id(), state, true);

        mvc.perform(authorizedCancel(order, customer)).andExpect(status().isConflict());

        assertNoCancellation(order);
    }

    @Test
    void concealsMissingOrdersAndOrdersThatBelongToOthers() throws Exception {
        Account customer = loginAs("USER");
        Account otherCustomer = loginAs("USER");
        Order otherOrder = insertOrder(otherCustomer.id(), PENDING_PAYMENT, false);

        mvc.perform(authorizedCancel(otherOrder, customer)).andExpect(status().isNotFound());
        mvc.perform(cancel(newOrderNo()).header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.token()))
                .andExpect(status().isNotFound());

        assertNoCancellation(otherOrder);
    }

    @Test
    void requiresAnAuthenticatedCustomer() throws Exception {
        Account customer = loginAs("USER");
        Account admin = loginAs("ADMIN");
        Order pending = insertOrder(customer.id(), PENDING_PAYMENT, false);

        mvc.perform(cancel(pending.getOrderNo())).andExpect(status().isUnauthorized());
        mvc.perform(authorizedCancel(pending, admin)).andExpect(status().isForbidden());

        assertNoCancellation(pending);
    }

    private Account loginAs(String role) throws Exception {
        String username = "order-cancel-test-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounts (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
                username, passwords.encode(password), role, CREATED_AT);
        Long accountId = jdbc.queryForObject("SELECT id FROM accounts WHERE username = ?", Long.class, username);

        byte[] response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return new Account(accountId, json.readTree(response).required("accessToken").asText());
    }

    private Order insertOrder(long userId, OrderStatus state, boolean withPaymentInfo) {
        Order order = new Order();
        order.setOrderNo(newOrderNo());
        order.setUserId(userId);
        order.setPayAmount(new BigDecimal("12.30"));
        order.setStatus(state);
        order.setVersion(ORIGINAL_VERSION);
        order.setCreatedAt(CREATED_AT);
        order.setUpdatedAt(CREATED_AT);
        if (withPaymentInfo) {
            order.setPaymentMethod("WECHAT");
            order.setPaymentTransactionNo(newOrderNo());
            order.setPaidAt(CREATED_AT);
        }
        assertThat(orderMapper.insert(order)).isEqualTo(1);
        return order;
    }

    private List<OrderStatusLog> logsFor(Order order) {
        return logMapper.selectList(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getOrderId, order.getId()));
    }

    private void assertNoCancellation(Order original) {
        assertThat(orderMapper.selectById(original.getId())).usingRecursiveComparison().isEqualTo(original);
        assertThat(logsFor(original)).isEmpty();
    }

    private MockHttpServletRequestBuilder authorizedCancel(Order order, Account account) {
        return cancel(order.getOrderNo()).header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token());
    }

    private MockHttpServletRequestBuilder cancel(String orderNo) {
        return post("/api/orders/{orderNo}/cancel", orderNo);
    }

    private String newOrderNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Account(long id, String token) {
    }
}
