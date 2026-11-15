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

import static com.itheima.orderflow.order.OrderStatus.COMPLETED;
import static com.itheima.orderflow.order.OrderStatus.IN_PROGRESS;
import static com.itheima.orderflow.order.OrderStatus.PAID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {AuthIntegrationTests.TEST_JWT_PROPERTY, AuthIntegrationTests.TEST_NO_SCHEDULE_PROPERTY})
@AutoConfigureMockMvc
@Transactional
class OrderCompletionIntegrationTest {

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

    @Test
    void administratorCompletesACustomersStartedOrderAndPreservesItsPayment() throws Exception {
        Account customer = loginAs("USER");
        Account administrator = loginAs("ADMIN");
        assertThat(administrator.id()).isNotEqualTo(customer.id());
        Order started = startPaidOrder(customer.id(), administrator);
        LocalDateTime before = LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.MILLIS);

        mvc.perform(authorizedComplete(started.getOrderNo(), administrator))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        LocalDateTime after = LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.MILLIS);
        Order completed = orderMapper.selectById(started.getId());
        assertThat(completed.getStatus()).isEqualTo(COMPLETED);
        assertThat(completed.getVersion()).isEqualTo(started.getVersion() + 1);
        assertThat(completed.getUpdatedAt()).isBetween(before, after);
        assertThat(completed).usingRecursiveComparison()
                .ignoringFields("status", "version", "updatedAt")
                .isEqualTo(started);
        List<OrderStatusLog> logs = logsFor(started);
        assertThat(logs).hasSize(2);
        assertThat(logs).filteredOn(log -> log.getToStatus() == COMPLETED).singleElement().satisfies(log -> {
            assertThat(log.getOrderId()).isEqualTo(started.getId());
            assertThat(log.getFromStatus()).isEqualTo(IN_PROGRESS);
            assertThat(log.getToStatus()).isEqualTo(COMPLETED);
            assertThat(log.getOperatorType()).isEqualTo("ADMIN");
            assertThat(log.getOperatorId()).isEqualTo(administrator.id());
            assertThat(log.getRemark()).isEqualTo("管理员完成订单");
            assertThat(log.getCreatedAt()).isEqualTo(completed.getUpdatedAt());
        });
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING_PAYMENT", "PAID", "CANCELLED", "OVERDUE"})
    void refusesToCompleteOrdersThatHaveNotStarted(OrderStatus originalStatus) throws Exception {
        Account customer = loginAs("USER");
        Account administrator = loginAs("ADMIN");
        Order original = insertOrder(customer.id(), originalStatus);

        mvc.perform(authorizedComplete(original.getOrderNo(), administrator)).andExpect(status().isConflict());

        assertNoCompletion(original);
    }

    @Test
    void refusesToCompleteAnOrderTwice() throws Exception {
        Account customer = loginAs("USER");
        Account administrator = loginAs("ADMIN");
        Order started = startPaidOrder(customer.id(), administrator);
        mvc.perform(authorizedComplete(started.getOrderNo(), administrator)).andExpect(status().isNoContent());
        Order completed = orderMapper.selectById(started.getId());

        mvc.perform(authorizedComplete(started.getOrderNo(), administrator)).andExpect(status().isConflict());

        assertThat(orderMapper.selectById(started.getId())).usingRecursiveComparison().isEqualTo(completed);
        assertThat(logsFor(started)).hasSize(2);
    }

    @Test
    void returnsNotFoundForAMissingOrder() throws Exception {
        Account administrator = loginAs("ADMIN");

        mvc.perform(authorizedComplete(newOrderNo(), administrator)).andExpect(status().isNotFound());
    }

    @Test
    void requiresAnAuthenticatedAdministratorEvenForTheCustomersOwnOrder() throws Exception {
        Account customer = loginAs("USER");
        Order paid = insertOrder(customer.id(), PAID);

        mvc.perform(complete(paid.getOrderNo())).andExpect(status().isUnauthorized());
        mvc.perform(authorizedComplete(paid.getOrderNo(), customer)).andExpect(status().isForbidden());

        assertNoCompletion(paid);
    }

    private Account loginAs(String role) throws Exception {
        String username = "order-complete-test-" + UUID.randomUUID();
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

    private Order insertOrder(long userId, OrderStatus orderStatus) {
        Order order = new Order();
        order.setOrderNo(newOrderNo());
        order.setUserId(userId);
        order.setPayAmount(new BigDecimal("12.30"));
        order.setStatus(orderStatus);
        order.setVersion(ORIGINAL_VERSION);
        order.setCreatedAt(CREATED_AT);
        order.setUpdatedAt(CREATED_AT);
        if (orderStatus == PAID) {
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

    private Order startPaidOrder(long customerId, Account administrator) throws Exception {
        Order paid = insertOrder(customerId, PAID);
        mvc.perform(post("/api/admin/orders/{orderNo}/start", paid.getOrderNo())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + administrator.token()))
                .andExpect(status().isNoContent());
        Order started = orderMapper.selectById(paid.getId());
        assertThat(started.getStatus()).isEqualTo(IN_PROGRESS);
        assertThat(started.getVersion()).isEqualTo(ORIGINAL_VERSION + 1);
        return started;
    }

    private void assertNoCompletion(Order original) {
        assertThat(orderMapper.selectById(original.getId())).usingRecursiveComparison().isEqualTo(original);
        assertThat(logsFor(original)).isEmpty();
    }

    private MockHttpServletRequestBuilder authorizedComplete(String orderNo, Account account) {
        return complete(orderNo).header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token());
    }

    private MockHttpServletRequestBuilder complete(String orderNo) {
        return post("/api/admin/orders/{orderNo}/complete", orderNo);
    }

    private String newOrderNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Account(long id, String token) {
    }
}
