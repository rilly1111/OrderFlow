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
class OrderCancellationTransactionIntegrationTest {

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
        String username = "order-cancel-tx-" + UUID.randomUUID();
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
    void cancellationRollsBackWhenWritingTheStatusLogFails() {
        OrderState before = readOrderState();
        doAnswer(invocation -> {
            OrderStatusLog log = invocation.getArgument(0);
            assertThat(log.getOrderId()).isEqualTo(orderId);
            assertThat(log.getToStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

            OrderState updated = readOrderState();
            assertThat(updated.status()).isEqualTo("CANCELLED");
            assertThat(updated.version()).isEqualTo(before.version() + 1);
            throw new DataIntegrityViolationException("Simulated cancellation status log insert failure");
        }).when(logMapper).insert(any(OrderStatusLog.class));

        assertThatThrownBy(() -> mvc.perform(cancel()))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(DataIntegrityViolationException.class);

        // The test has no enclosing transaction, so these reads see the cancellation transaction's outcome.
        assertThat(readOrderState()).isEqualTo(before);
        assertThat(readStatusChanges()).containsExactly(new StatusChange(null, "PENDING_PAYMENT"));
    }

    @Test
    void paymentAndCancellationCompetingForTheSameVersionCommitOneOutcome() throws Exception {
        OrderState before = readOrderState();
        CyclicBarrier beforeUpdate = new CyclicBarrier(2);
        var affectedRows = new ConcurrentLinkedQueue<Integer>();
        Answer<?> realMapper = mockingDetails(orderMapper).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            // Let both requests read the original status and version before either update reaches MySQL.
            beforeUpdate.await(10, TimeUnit.SECONDS);
            int rows = (Integer) realMapper.answer(invocation);
            affectedRows.add(rows);
            return rows;
        }).when(orderMapper).update(isNull(), any());

        MvcResult paymentResult;
        MvcResult cancellationResult;
        var workers = Executors.newFixedThreadPool(2);
        try {
            var payment = workers.submit(() -> mvc.perform(pay()).andReturn());
            var cancellation = workers.submit(() -> mvc.perform(cancel()).andReturn());
            paymentResult = payment.get(20, TimeUnit.SECONDS);
            cancellationResult = cancellation.get(20, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(affectedRows).containsExactlyInAnyOrder(1, 0);
        boolean paid = paymentResult.getResponse().getStatus() == 200;
        assertThat(paymentResult.getResponse().getStatus()).isEqualTo(paid ? 200 : 409);
        assertThat(cancellationResult.getResponse().getStatus()).isEqualTo(paid ? 409 : 204);

        OrderState result = readOrderState();
        assertThat(result.status()).isEqualTo(paid ? "PAID" : "CANCELLED");
        assertThat(result.version()).isEqualTo(before.version() + 1);
        assertThat(result.payAmount()).isEqualByComparingTo(before.payAmount());
        if (paid) {
            var response = json.readTree(paymentResult.getResponse().getContentAsByteArray());
            assertThat(result.paymentMethod()).isEqualTo("ALIPAY");
            assertThat(result.transactionNo()).isEqualTo(response.required("paymentTransactionNo").asText());
            assertThat(result.paidAt()).isNotNull();
        } else {
            assertThat(result.paymentMethod()).isEqualTo(before.paymentMethod());
            assertThat(result.transactionNo()).isEqualTo(before.transactionNo());
            assertThat(result.paidAt()).isEqualTo(before.paidAt());
        }
        assertThat(readStatusChanges()).containsExactly(new StatusChange(null, "PENDING_PAYMENT"),
                new StatusChange("PENDING_PAYMENT", result.status()));
    }

    private MockHttpServletRequestBuilder cancel() {
        return post("/api/orders/{orderNo}/cancel", orderNo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }

    private MockHttpServletRequestBuilder pay() {
        return post("/api/orders/{orderNo}/pay", orderNo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"ALIPAY\"}");
    }

    private OrderState readOrderState() {
        return jdbc.queryForObject("""
                SELECT status, version, pay_amount, payment_method, payment_transaction_no, paid_at, updated_at
                FROM orders WHERE id = ?
                """, (row, index) -> new OrderState(row.getString("status"), row.getInt("version"),
                row.getBigDecimal("pay_amount"), row.getString("payment_method"),
                row.getString("payment_transaction_no"), row.getObject("paid_at", LocalDateTime.class),
                row.getObject("updated_at", LocalDateTime.class)), orderId);
    }

    private List<StatusChange> readStatusChanges() {
        return jdbc.query("""
                SELECT from_status, to_status FROM order_status_log WHERE order_id = ? ORDER BY id
                """, (row, index) -> new StatusChange(row.getString("from_status"), row.getString("to_status")), orderId);
    }

    private record OrderState(String status, int version, BigDecimal payAmount, String paymentMethod,
                              String transactionNo, LocalDateTime paidAt, LocalDateTime updatedAt) {
    }

    private record StatusChange(String fromStatus, String toStatus) {
    }
}
