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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.itheima.orderflow.order.OrderStatus.PAID;
import static com.itheima.orderflow.order.OrderStatus.PENDING_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {AuthIntegrationTests.TEST_JWT_PROPERTY, AuthIntegrationTests.TEST_NO_SCHEDULE_PROPERTY})
@AutoConfigureMockMvc
@Transactional
class OrderPaymentIntegrationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 1, 8, 0);
    private static final int ORIGINAL_VERSION = 7;
    private static final String ALIPAY_REQUEST = "{\"paymentMethod\":\"ALIPAY\"}";

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
    @ValueSource(strings = {"ALIPAY", "WECHAT", "BANK_CARD"})
    void paysWithAnAllowedMethodAndWritesTheOrderAndLog(String method) throws Exception {
        Account customer = loginAs("USER");
        String orderNo = insertOrder(customer.id(), PENDING_PAYMENT);
        LocalDateTime before = LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.MILLIS);

        byte[] response = mvc.perform(authorizedPay(orderNo, customer, json.writeValueAsString(Map.of("paymentMethod", method))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(orderNo))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payAmount").value(12.30))
                .andExpect(jsonPath("$.paymentMethod").value(method))
                .andReturn().getResponse().getContentAsByteArray();

        LocalDateTime after = LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.MILLIS);
        var payment = json.readTree(response);
        String transactionNo = payment.required("paymentTransactionNo").asText();
        LocalDateTime paidAt = LocalDateTime.parse(payment.required("paidAt").asText());
        assertThat(transactionNo).matches("[0-9a-f]{32}").isNotEqualTo(orderNo);
        assertThat(paidAt).isBetween(before, after);

        Order order = findOrder(orderNo);
        assertThat(order.getUserId()).isEqualTo(customer.id());
        assertThat(order.getStatus()).isEqualTo(PAID);
        assertThat(order.getPayAmount()).isEqualByComparingTo("12.30");
        assertThat(order.getPaymentMethod()).isEqualTo(method);
        assertThat(order.getPaymentTransactionNo()).isEqualTo(transactionNo);
        assertThat(order.getPaidAt()).isEqualTo(paidAt);
        assertThat(order.getUpdatedAt()).isEqualTo(paidAt);
        assertThat(order.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(order.getVersion()).isEqualTo(ORIGINAL_VERSION + 1);
        assertThat(logsFor(order)).singleElement().satisfies(log -> {
            assertThat(log.getOrderId()).isEqualTo(order.getId());
            assertThat(log.getFromStatus()).isEqualTo(PENDING_PAYMENT);
            assertThat(log.getToStatus()).isEqualTo(PAID);
            assertThat(log.getOperatorType()).isEqualTo("USER");
            assertThat(log.getOperatorId()).isEqualTo(customer.id());
            assertThat(log.getRemark()).isEqualTo("模拟支付成功");
            assertThat(log.getCreatedAt()).isEqualTo(paidAt);
        });
    }

    @Test
    void refusesToPayTheSameOrderAgain() throws Exception {
        Account customer = loginAs("USER");
        String orderNo = insertOrder(customer.id(), PENDING_PAYMENT);
        mvc.perform(authorizedPay(orderNo, customer, ALIPAY_REQUEST)).andExpect(status().isOk());
        Order firstPayment = findOrder(orderNo);

        mvc.perform(authorizedPay(orderNo, customer, "{\"paymentMethod\":\"WECHAT\"}"))
                .andExpect(status().isConflict());

        Order order = findOrder(orderNo);
        assertThat(order.getPaymentMethod()).isEqualTo("ALIPAY");
        assertThat(order.getPaymentTransactionNo()).isEqualTo(firstPayment.getPaymentTransactionNo());
        assertThat(order.getPaidAt()).isEqualTo(firstPayment.getPaidAt());
        assertThat(order.getVersion()).isEqualTo(ORIGINAL_VERSION + 1);
        assertThat(logsFor(order)).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CANCELLED", "COMPLETED"})
    void refusesToPayAnOrderInATerminalState(OrderStatus state) throws Exception {
        Account customer = loginAs("USER");
        String orderNo = insertOrder(customer.id(), state);

        mvc.perform(authorizedPay(orderNo, customer, ALIPAY_REQUEST)).andExpect(status().isConflict());

        assertNoPayment(orderNo, state);
    }

    @Test
    void concealsMissingOrdersAndOrdersThatBelongToOthers() throws Exception {
        Account customer = loginAs("USER");
        Account otherCustomer = loginAs("USER");
        String otherOrder = insertOrder(otherCustomer.id(), PENDING_PAYMENT);

        mvc.perform(authorizedPay(otherOrder, customer, ALIPAY_REQUEST)).andExpect(status().isNotFound());
        mvc.perform(authorizedPay(UUID.randomUUID().toString().replace("-", ""), customer, ALIPAY_REQUEST))
                .andExpect(status().isNotFound());

        assertNoPayment(otherOrder, PENDING_PAYMENT);
    }

    @Test
    void requiresAnAuthenticatedCustomer() throws Exception {
        Account customer = loginAs("USER");
        Account admin = loginAs("ADMIN");
        String orderNo = insertOrder(customer.id(), PENDING_PAYMENT);

        mvc.perform(pay(orderNo, ALIPAY_REQUEST)).andExpect(status().isUnauthorized());
        mvc.perform(authorizedPay(orderNo, admin, ALIPAY_REQUEST)).andExpect(status().isForbidden());

        assertNoPayment(orderNo, PENDING_PAYMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"paymentMethod\":null}", "{\"paymentMethod\":\"alipay\"}",
            "{\"paymentMethod\":\"CASH\"}", "{\"paymentMethod\":0}"})
    void rejectsInvalidOrMissingPaymentMethods(String body) throws Exception {
        Account customer = loginAs("USER");
        String orderNo = insertOrder(customer.id(), PENDING_PAYMENT);

        mvc.perform(authorizedPay(orderNo, customer, body)).andExpect(status().isBadRequest());

        assertNoPayment(orderNo, PENDING_PAYMENT);
    }

    @Test
    void rejectsClientSuppliedOrderFields() throws Exception {
        Account customer = loginAs("USER");
        String orderNo = insertOrder(customer.id(), PENDING_PAYMENT);
        var forbiddenFields = Map.of(
                "userId", customer.id() + 1,
                "payAmount", 0.01,
                "paymentTransactionNo", "client-supplied",
                "paidAt", CREATED_AT.toString(),
                "status", "COMPLETED",
                "version", 42);

        for (var field : forbiddenFields.entrySet()) {
            String body = json.writeValueAsString(Map.of("paymentMethod", "ALIPAY", field.getKey(), field.getValue()));
            mvc.perform(authorizedPay(orderNo, customer, body)).andExpect(status().isBadRequest());
        }

        assertNoPayment(orderNo, PENDING_PAYMENT);
    }

    private Account loginAs(String role) throws Exception {
        String username = "order-payment-test-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounts (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
                username, passwords.encode(password), role, CREATED_AT);
        Long accountId = jdbc.queryForObject("SELECT id FROM accounts WHERE username = ?", Long.class, username);

        byte[] response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String token = json.readTree(response).required("accessToken").asText();
        return new Account(accountId, token);
    }

    private String insertOrder(long userId, OrderStatus status) {
        String orderNo = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO orders (order_no, user_id, pay_amount, status, version, created_at, updated_at)
                VALUES (?, ?, 12.30, ?, ?, ?, ?)
                """, orderNo, userId, status.name(), ORIGINAL_VERSION, CREATED_AT, CREATED_AT);
        return orderNo;
    }

    private Order findOrder(String orderNo) {
        return orderMapper.selectOne(Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, orderNo));
    }

    private List<OrderStatusLog> logsFor(Order order) {
        return logMapper.selectList(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getOrderId, order.getId()));
    }

    private void assertNoPayment(String orderNo, OrderStatus originalStatus) {
        Order order = findOrder(orderNo);
        assertThat(order.getStatus()).isEqualTo(originalStatus);
        assertThat(order.getVersion()).isEqualTo(ORIGINAL_VERSION);
        assertThat(order.getPayAmount()).isEqualByComparingTo("12.30");
        assertThat(order.getPaidAt()).isNull();
        assertThat(order.getPaymentTransactionNo()).isNull();
        assertThat(order.getPaymentMethod()).isNull();
        assertThat(logsFor(order)).isEmpty();
    }

    private MockHttpServletRequestBuilder authorizedPay(String orderNo, Account account, String body) {
        return pay(orderNo, body).header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token());
    }

    private MockHttpServletRequestBuilder pay(String orderNo, String body) {
        return post("/api/orders/{orderNo}/pay", orderNo)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private record Account(long id, String token) {
    }
}
