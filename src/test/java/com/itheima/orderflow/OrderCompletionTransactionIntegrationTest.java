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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {AuthIntegrationTests.TEST_JWT_PROPERTY, AuthIntegrationTests.TEST_NO_SCHEDULE_PROPERTY})
@AutoConfigureMockMvc
class OrderCompletionTransactionIntegrationTest {

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

    private final List<Long> fixtureAccountIds = new ArrayList<>();
    private Long customerId;
    private Long orderId;
    private String orderNo;

    @BeforeEach
    void createCommittedPaidOrder() throws Exception {
        Session customer = signInAs("USER");
        customerId = customer.accountId();
        byte[] created = mvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payAmount":12.30,"equipmentCode":"PUMP-001","faultDescription":"Abnormal vibration"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();
        orderNo = json.readTree(created).required("orderNo").asText();
        orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_no = ? AND user_id = ?",
                Long.class, orderNo, customerId);

        mvc.perform(post("/api/orders/{orderNo}/pay", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"ALIPAY\"}"))
                .andExpect(status().isOk());
    }

    @AfterEach
    void removeTestData() {
        if (customerId != null) {
            jdbc.update("""
                    DELETE log FROM order_status_log log
                    JOIN orders o ON log.order_id = o.id
                    WHERE o.user_id = ?
                    """, customerId);
            jdbc.update("DELETE FROM orders WHERE user_id = ?", customerId);
        }
        fixtureAccountIds.forEach(accountId -> jdbc.update("DELETE FROM accounts WHERE id = ?", accountId));
    }

    @Test
    void completionRollsBackWhenWritingTheStatusLogFails() throws Exception {
        startCommittedOrder();
        Session admin = signInAs("ADMIN");
        OrderState before = readOrderState();
        List<StatusChange> history = readStatusChanges();

        doAnswer(invocation -> {
            OrderStatusLog log = invocation.getArgument(0);
            assertThat(log.getOrderId()).isEqualTo(orderId);
            assertThat(log.getToStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(log.getOperatorId()).isEqualTo(admin.accountId());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            OrderState updated = readOrderState();
            assertThat(updated.status()).isEqualTo("COMPLETED");
            assertThat(updated.version()).isEqualTo(before.version() + 1);
            throw new DataIntegrityViolationException("Simulated completion status log insert failure");
        }).when(logMapper).insert(any(OrderStatusLog.class));

        assertThatThrownBy(() -> mvc.perform(complete(admin)))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(DataIntegrityViolationException.class);

        // The fixture was committed, so these reads show the outcome of the completion transaction.
        assertThat(readOrderState()).isEqualTo(before);
        assertThat(readStatusChanges()).isEqualTo(history);
    }

    @Test
    void concurrentAdminsCompletingTheSameVersionCommitOneOutcome() throws Exception {
        startCommittedOrder();
        Session firstAdmin = signInAs("ADMIN");
        Session secondAdmin = signInAs("ADMIN");
        OrderState before = readOrderState();
        List<StatusChange> history = readStatusChanges();
        var beforeUpdate = new CyclicBarrier(2);
        var affectedRows = new ConcurrentLinkedQueue<Integer>();
        Answer<?> realMapper = mockingDetails(orderMapper).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            // Both requests must load the original status and version before either update reaches MySQL.
            beforeUpdate.await(10, TimeUnit.SECONDS);
            int rows = (Integer) realMapper.answer(invocation);
            affectedRows.add(rows);
            return rows;
        }).when(orderMapper).update(isNull(), any());

        MvcResult firstResult;
        MvcResult secondResult;
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> mvc.perform(complete(firstAdmin)).andReturn());
            var second = workers.submit(() -> mvc.perform(complete(secondAdmin)).andReturn());
            firstResult = first.get(20, TimeUnit.SECONDS);
            secondResult = second.get(20, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(affectedRows).containsExactlyInAnyOrder(1, 0);
        int firstStatus = firstResult.getResponse().getStatus();
        int secondStatus = secondResult.getResponse().getStatus();
        assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(204, 409);
        long winnerId = firstStatus == 204 ? firstAdmin.accountId() : secondAdmin.accountId();

        OrderState result = readOrderState();
        assertThat(result).isEqualTo(new OrderState(before.userId(), "COMPLETED", before.version() + 1,
                before.payAmount(), before.paymentMethod(), before.transactionNo(), before.paidAt(), result.updatedAt()));
        List<StatusChange> completedHistory = new ArrayList<>(history);
        completedHistory.add(new StatusChange("IN_PROGRESS", "COMPLETED", "ADMIN", winnerId,
                "管理员完成订单", result.updatedAt()));
        assertThat(readStatusChanges()).isEqualTo(completedHistory);
    }

    @Test
    void lateAcceptanceRollsBackBothTransitionsWhenWritingTheSecondLogFails() throws Exception {
        Session admin = signInAs("ADMIN");
        LocalDateTime deadline = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .minusMinutes(5).truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("UPDATE orders SET acceptance_deadline_at = ? WHERE id = ?", deadline, orderId);
        OrderState before = readOrderState();
        AcceptanceTimes timesBefore = readAcceptanceTimes();
        List<StatusChange> history = readStatusChanges();

        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            OrderState updated = readOrderState();
            assertThat(updated.status()).isEqualTo("IN_PROGRESS");
            assertThat(updated.version()).isEqualTo(before.version() + 2);

            AcceptanceTimes times = readAcceptanceTimes();
            LocalDateTime transitionAt = times.startedAt();
            assertThat(transitionAt).isAfter(deadline);
            assertThat(times.overdueAt()).isEqualTo(transitionAt);
            assertThat(updated.updatedAt()).isEqualTo(transitionAt);

            List<StatusChange> expectedHistory = new ArrayList<>(history);
            expectedHistory.add(new StatusChange("PAID", "OVERDUE", "SYSTEM", null, "SLA受理超时", transitionAt));
            assertThat(readStatusChanges()).isEqualTo(expectedHistory);

            OrderStatusLog log = invocation.getArgument(0);
            assertThat(log.getFromStatus()).isEqualTo(OrderStatus.OVERDUE);
            assertThat(log.getOperatorType()).isEqualTo("ADMIN");
            assertThat(log.getOperatorId()).isEqualTo(admin.accountId());
            assertThat(log.getCreatedAt()).isEqualTo(transitionAt);
            throw new DataIntegrityViolationException("Simulated late acceptance status log insert failure");
        }).when(logMapper).insert(argThat((OrderStatusLog log) -> log != null && orderId.equals(log.getOrderId())
                && log.getToStatus() == OrderStatus.IN_PROGRESS));

        assertThatThrownBy(() -> mvc.perform(start(admin)))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(DataIntegrityViolationException.class);

        assertThat(readOrderState()).isEqualTo(before);
        assertThat(readAcceptanceTimes()).isEqualTo(timesBefore);
        assertThat(readStatusChanges()).isEqualTo(history);
    }

    private void startCommittedOrder() throws Exception {
        mvc.perform(start(signInAs("ADMIN"))).andExpect(status().isNoContent());
    }

    private Session signInAs(String role) throws Exception {
        String username = "order-complete-tx-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounts (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
                username, passwords.encode(password), role, LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        Long accountId = jdbc.queryForObject("SELECT id FROM accounts WHERE username = ?", Long.class, username);
        fixtureAccountIds.add(accountId);
        byte[] response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return new Session(accountId, json.readTree(response).required("accessToken").asText());
    }

    private MockHttpServletRequestBuilder complete(Session admin) {
        return post("/api/admin/orders/{orderNo}/complete", orderNo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token());
    }

    private MockHttpServletRequestBuilder start(Session admin) {
        return post("/api/admin/orders/{orderNo}/start", orderNo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token());
    }

    private OrderState readOrderState() {
        return jdbc.queryForObject("""
                SELECT user_id, status, version, pay_amount, payment_method, payment_transaction_no, paid_at, updated_at
                FROM orders WHERE id = ?
                """, (row, index) -> new OrderState(row.getLong("user_id"), row.getString("status"),
                row.getInt("version"), row.getBigDecimal("pay_amount"), row.getString("payment_method"),
                row.getString("payment_transaction_no"), row.getObject("paid_at", LocalDateTime.class),
                row.getObject("updated_at", LocalDateTime.class)), orderId);
    }

    private List<StatusChange> readStatusChanges() {
        return jdbc.query("""
                SELECT from_status, to_status, operator_type, operator_id, remark, created_at
                FROM order_status_log WHERE order_id = ? ORDER BY id
                """, (row, index) -> new StatusChange(row.getString("from_status"), row.getString("to_status"),
                row.getString("operator_type"), row.getObject("operator_id", Long.class),
                row.getString("remark"), row.getObject("created_at", LocalDateTime.class)), orderId);
    }

    private AcceptanceTimes readAcceptanceTimes() {
        return jdbc.queryForObject("""
                SELECT acceptance_deadline_at, started_at, overdue_at FROM orders WHERE id = ?
                """, (row, index) -> new AcceptanceTimes(row.getObject("acceptance_deadline_at", LocalDateTime.class),
                row.getObject("started_at", LocalDateTime.class), row.getObject("overdue_at", LocalDateTime.class)),
                orderId);
    }

    private record Session(long accountId, String token) {
    }

    private record OrderState(long userId, String status, int version, BigDecimal payAmount, String paymentMethod,
                              String transactionNo, LocalDateTime paidAt, LocalDateTime updatedAt) {
    }

    private record StatusChange(String fromStatus, String toStatus, String operatorType, Long operatorId,
                                String remark, LocalDateTime createdAt) {
    }

    private record AcceptanceTimes(LocalDateTime deadline, LocalDateTime startedAt, LocalDateTime overdueAt) {
    }
}
