package com.itheima.orderflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.orderflow.order.OrderMapper;
import com.itheima.orderflow.order.OrderStatus;
import com.itheima.orderflow.order.OrderStatusLog;
import com.itheima.orderflow.order.OrderStatusLogMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {AuthIntegrationTests.TEST_JWT_PROPERTY, AuthIntegrationTests.TEST_NO_SCHEDULE_PROPERTY})
@AutoConfigureMockMvc
class OrderPaymentTransactionIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private ObjectMapper json;
    @MockitoSpyBean
    private OrderMapper orderMapper;
    @MockitoSpyBean
    private OrderStatusLogMapper logMapper;

    private Long accountId;
    private Long orderId;
    private String orderNo;
    private String accessToken;

    @BeforeEach
    void createCommittedPendingOrder() throws Exception {
        String username = "order-pay-tx-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounts (username, password_hash, role, created_at) VALUES (?, ?, 'USER', ?)",
                username, passwords.encode(password), LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        accountId = jdbc.queryForObject("SELECT id FROM accounts WHERE username = ?", Long.class, username);

        byte[] loginResponse = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        accessToken = json.readTree(loginResponse).required("accessToken").asText();

        byte[] createResponse = mvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payAmount":12.30,"equipmentCode":"PUMP-001","faultDescription":"Abnormal vibration"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();
        orderNo = json.readTree(createResponse).required("orderNo").asText();
        orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_no = ? AND user_id = ?",
                Long.class, orderNo, accountId);
    }

    @AfterEach
    void removeTestData() {
        if (accountId != null) {
            jdbc.update("""
                    DELETE log FROM order_status_log log
                    JOIN orders o ON log.order_id = o.id
                    WHERE o.user_id = ?
                    """, accountId);
            jdbc.update("DELETE FROM orders WHERE user_id = ?", accountId);
            jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
    }

    @Test
    void paymentUpdateRollsBackWhenWritingTheStatusLogFails() {
        PaymentState before = readPaymentState();
        doAnswer(invocation -> {
            OrderStatusLog log = invocation.getArgument(0);
            assertThat(log.getOrderId()).isEqualTo(orderId);
            assertThat(log.getToStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

            PaymentState updated = readPaymentState();
            assertThat(updated.status()).isEqualTo("PAID");
            assertThat(updated.version()).isEqualTo(before.version() + 1);
            assertThat(updated.paymentMethod()).isEqualTo("ALIPAY");
            assertThat(updated.transactionNo()).isNotBlank();
            assertThat(updated.paidAt()).isNotNull();
            throw new DataIntegrityViolationException("Simulated payment status log insert failure");
        }).when(logMapper).insert(any(OrderStatusLog.class));

        assertThatThrownBy(() -> mvc.perform(pay("ALIPAY")))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(DataIntegrityViolationException.class);

        // The test itself has no transaction, so this reads the outcome of the payment transaction.
        assertThat(readPaymentState()).isEqualTo(before);
        assertThat(paymentLogCount()).isZero();
    }

    @Test
    void twoPaymentsUsingTheSameOriginalVersionProduceOneSuccessAndOneConflict() throws Exception {
        CyclicBarrier beforeUpdate = new CyclicBarrier(2);
        var affectedRows = new ConcurrentLinkedQueue<Integer>();
        Answer<?> realMapper = mockingDetails(orderMapper).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            // Both requests must finish loading the original order before either updates it.
            beforeUpdate.await(10, TimeUnit.SECONDS);
            int rows = (Integer) realMapper.answer(invocation);
            affectedRows.add(rows);
            return rows;
        }).when(orderMapper).update(isNull(), any());

        MvcResult first;
        MvcResult second;
        var workers = Executors.newFixedThreadPool(2);
        try {
            var firstRequest = workers.submit(() -> mvc.perform(pay("ALIPAY")).andReturn());
            var secondRequest = workers.submit(() -> mvc.perform(pay("WECHAT")).andReturn());
            first = firstRequest.get(20, TimeUnit.SECONDS);
            second = secondRequest.get(20, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(affectedRows).containsExactlyInAnyOrder(1, 0);
        assertThat(List.of(first.getResponse().getStatus(), second.getResponse().getStatus()))
                .containsExactlyInAnyOrder(200, 409);
        MvcResult winner = first.getResponse().getStatus() == 200 ? first : second;
        var body = json.readTree(winner.getResponse().getContentAsByteArray());

        PaymentState paid = readPaymentState();
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.version()).isEqualTo(1);
        assertThat(paid.paymentMethod()).isEqualTo(body.required("paymentMethod").asText());
        assertThat(paid.transactionNo()).isEqualTo(body.required("paymentTransactionNo").asText());
        assertThat(paymentLogCount()).isEqualTo(1L);
    }

    private MockHttpServletRequestBuilder pay(String paymentMethod) throws Exception {
        return post("/api/orders/{orderNo}/pay", orderNo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("paymentMethod", paymentMethod)));
    }

    private PaymentState readPaymentState() {
        return jdbc.queryForObject("""
                SELECT status, version, pay_amount, payment_method, payment_transaction_no, paid_at, updated_at
                FROM orders WHERE id = ?
                """, (row, index) -> new PaymentState(row.getString("status"), row.getInt("version"),
                row.getBigDecimal("pay_amount"), row.getString("payment_method"),
                row.getString("payment_transaction_no"), row.getObject("paid_at", LocalDateTime.class),
                row.getObject("updated_at", LocalDateTime.class)), orderId);
    }

    private Long paymentLogCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_status_log WHERE order_id = ? AND to_status = 'PAID'",
                Long.class, orderId);
    }

    private record PaymentState(String status, int version, BigDecimal payAmount, String paymentMethod,
                                String transactionNo, LocalDateTime paidAt, LocalDateTime updatedAt) {
    }
}
