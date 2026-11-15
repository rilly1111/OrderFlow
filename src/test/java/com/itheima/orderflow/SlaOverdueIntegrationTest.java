package com.itheima.orderflow;

import com.itheima.orderflow.order.Order;
import com.itheima.orderflow.order.OrderMapper;
import com.itheima.orderflow.order.OrderStatus;
import com.itheima.orderflow.order.OrderStatusLog;
import com.itheima.orderflow.order.OrderStatusLogMapper;
import com.itheima.orderflow.order.SlaOverdueScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.itheima.orderflow.order.OrderStatus.CANCELLED;
import static com.itheima.orderflow.order.OrderStatus.COMPLETED;
import static com.itheima.orderflow.order.OrderStatus.OVERDUE;
import static com.itheima.orderflow.order.OrderStatus.PAID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {AuthIntegrationTests.TEST_JWT_PROPERTY, "indusflow.fulfillment.scan-enabled=false"})
class SlaOverdueIntegrationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2000, 1, 1, 12, 0);
    private static final LocalDateTime PAID_AT = NOW.minusHours(2);
    private static final LocalDateTime TRANSITION_AT = NOW.plusMinutes(5);
    private static final int ORIGINAL_VERSION = 7;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private SlaOverdueScheduler scheduler;
    @MockitoBean
    private Clock fulfillmentClock;
    @MockitoSpyBean
    private OrderMapper orderMapper;
    @MockitoSpyBean
    private OrderStatusLogMapper logMapper;

    private final Set<Long> fixtureIds = new HashSet<>();
    private Long accountId;

    @BeforeEach
    void createTestCustomerAndLimitScanWritesToItsFixtures() {
        when(fulfillmentClock.instant()).thenReturn(asInstant(TRANSITION_AT));
        String username = "sla-scan-" + UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (username, password_hash, role, created_at) VALUES (?, ?, 'USER', ?)",
                username, passwords.encode(UUID.randomUUID().toString()), PAID_AT);
        accountId = jdbc.queryForObject("SELECT id FROM accounts WHERE username = ?", Long.class, username);

        // Execute the real candidate query while restricting test-side writes to the rows this test inserted.
        doAnswer(this::findFixtureCandidates).when(orderMapper).findOverdueCandidates(any(LocalDateTime.class), anyInt());
    }

    @AfterEach
    void removeOnlyThisTestsRows() {
        if (accountId == null) {
            return;
        }
        jdbc.update("""
                DELETE log FROM order_status_log log
                JOIN orders o ON log.order_id = o.id
                WHERE o.user_id = ?
                """, accountId);
        jdbc.update("DELETE FROM orders WHERE user_id = ?", accountId);
        jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
    }

    @Test
    void scansAtTheDeadlineAndLeavesFutureLegacyAndTerminalOrdersAlone() {
        long atDeadline = insertOrder(PAID, NOW);
        long pastDeadline = insertOrder(PAID, NOW.minusMinutes(5));
        long futureDeadline = insertOrder(PAID, NOW.plusNanos(1_000_000));
        long noDeadline = insertOrder(PAID, null);
        long cancelled = insertOrder(CANCELLED, NOW);
        long completed = insertOrder(COMPLETED, NOW);
        LocalDateTime firstTransitionAt = TRANSITION_AT.minusSeconds(1);
        when(fulfillmentClock.instant()).thenReturn(asInstant(firstTransitionAt), asInstant(TRANSITION_AT));

        scheduler.scanOnce(NOW);
        scheduler.scanOnce(NOW);

        assertOverdue(atDeadline, NOW, TRANSITION_AT, TRANSITION_AT);
        assertOverdue(pastDeadline, NOW.minusMinutes(5), firstTransitionAt, firstTransitionAt);
        assertUnchanged(futureDeadline, PAID, NOW.plusNanos(1_000_000));
        assertUnchanged(noDeadline, PAID, null);
        assertUnchanged(cancelled, CANCELLED, NOW);
        assertUnchanged(completed, COMPLETED, NOW);
    }

    @Test
    void preservesAnAlreadyRecordedFirstOverdueTime() {
        long orderId = insertOrder(PAID, NOW);
        LocalDateTime firstOverdueAt = NOW.plusMinutes(2);
        jdbc.update("UPDATE orders SET overdue_at = ? WHERE id = ?", firstOverdueAt, orderId);

        scheduler.scanOnce(NOW);

        assertOverdue(orderId, NOW, firstOverdueAt, TRANSITION_AT);
    }

    @Test
    void skipsAStaleCandidateWhenItsDeadlineChangesButIsStillDue() {
        long orderId = insertOrder(PAID, NOW.minusMinutes(2));
        LocalDateTime revisedDeadline = NOW.minusMinutes(1);
        doAnswer(invocation -> {
            List<Order> candidates = findFixtureCandidates(invocation);
            assertThat(candidates).extracting(Order::getId).containsExactly(orderId);
            jdbc.update("UPDATE orders SET acceptance_deadline_at = ? WHERE id = ?", revisedDeadline, orderId);
            return candidates;
        }).when(orderMapper).findOverdueCandidates(any(LocalDateTime.class), anyInt());

        scheduler.scanOnce(NOW);

        assertUnchanged(orderId, PAID, revisedDeadline);
    }

    @Test
    void rollsBackTheFailedLogAndStillProcessesTheNextCandidate() {
        LocalDateTime failedDeadline = NOW.minusMinutes(2);
        long failed = insertOrder(PAID, failedDeadline);
        LocalDateTime healthyDeadline = NOW.minusMinutes(1);
        long healthy = insertOrder(PAID, healthyDeadline);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(readState(failed)).isEqualTo(new OrderState(OVERDUE, ORIGINAL_VERSION + 1,
                    failedDeadline, TRANSITION_AT, TRANSITION_AT));
            throw new DataIntegrityViolationException("Simulated SLA status log insert failure");
        }).when(logMapper).insert(argThat((OrderStatusLog log) -> log != null && log.getOrderId() == failed));

        scheduler.scanOnce(NOW);

        assertUnchanged(failed, PAID, failedDeadline);
        assertOverdue(healthy, healthyDeadline, TRANSITION_AT, TRANSITION_AT);
    }

    @Test
    void concurrentScansCommitOnlyOneOverdueTransition() throws Exception {
        long orderId = insertOrder(PAID, NOW);
        CyclicBarrier beforeUpdate = new CyclicBarrier(2);
        var affectedRows = new ConcurrentLinkedQueue<Integer>();
        Answer<?> realMapper = mockingDetails(orderMapper).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            // Both scans already found the same ID, version and deadline before either writes.
            beforeUpdate.await(10, TimeUnit.SECONDS);
            int rows = (Integer) realMapper.answer(invocation);
            affectedRows.add(rows);
            return rows;
        }).when(orderMapper).update(isNull(), any());

        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> scheduler.scanOnce(NOW));
            var second = workers.submit(() -> scheduler.scanOnce(NOW));
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(affectedRows).containsExactlyInAnyOrder(1, 0);
        assertOverdue(orderId, NOW, TRANSITION_AT, TRANSITION_AT);
    }

    private List<Order> findFixtureCandidates(InvocationOnMock invocation) throws Throwable {
        Answer<?> realMapper = mockingDetails(orderMapper).getMockCreationSettings().getDefaultAnswer();
        @SuppressWarnings("unchecked")
        List<Order> candidates = (List<Order>) realMapper.answer(invocation);
        return candidates.stream().filter(order -> fixtureIds.contains(order.getId())).toList();
    }

    private long insertOrder(OrderStatus status, LocalDateTime deadline) {
        String orderNo = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO orders (order_no, user_id, pay_amount, status, version, paid_at, acceptance_deadline_at,
                                    created_at, updated_at)
                VALUES (?, ?, 12.30, ?, ?, ?, ?, ?, ?)
                """, orderNo, accountId, status.name(), ORIGINAL_VERSION, PAID_AT, deadline, PAID_AT, PAID_AT);
        long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);
        fixtureIds.add(orderId);
        return orderId;
    }

    private static Instant asInstant(LocalDateTime time) {
        return time.atZone(SHANGHAI).toInstant();
    }

    private void assertOverdue(long orderId, LocalDateTime deadline, LocalDateTime firstOverdueAt,
                               LocalDateTime transitionAt) {
        assertThat(readState(orderId)).isEqualTo(new OrderState(OVERDUE, ORIGINAL_VERSION + 1,
                deadline, firstOverdueAt, transitionAt));
        assertThat(readLogs(orderId)).containsExactly(new StateChange("PAID", "OVERDUE", "SYSTEM", null,
                "SLA受理超时", transitionAt));
    }

    private void assertUnchanged(long orderId, OrderStatus status, LocalDateTime deadline) {
        assertThat(readState(orderId)).isEqualTo(new OrderState(status, ORIGINAL_VERSION, deadline, null, PAID_AT));
        assertThat(readLogs(orderId)).isEmpty();
    }

    private OrderState readState(long orderId) {
        return jdbc.queryForObject("""
                SELECT status, version, acceptance_deadline_at, overdue_at, updated_at FROM orders WHERE id = ?
                """, (row, index) -> new OrderState(OrderStatus.valueOf(row.getString("status")), row.getInt("version"),
                row.getObject("acceptance_deadline_at", LocalDateTime.class),
                row.getObject("overdue_at", LocalDateTime.class), row.getObject("updated_at", LocalDateTime.class)), orderId);
    }

    private List<StateChange> readLogs(long orderId) {
        return jdbc.query("""
                SELECT from_status, to_status, operator_type, operator_id, remark, created_at
                FROM order_status_log WHERE order_id = ? ORDER BY id
                """, (row, index) -> new StateChange(row.getString("from_status"), row.getString("to_status"),
                row.getString("operator_type"), row.getObject("operator_id", Long.class), row.getString("remark"),
                row.getObject("created_at", LocalDateTime.class)), orderId);
    }

    private record OrderState(OrderStatus status, int version, LocalDateTime deadline,
                              LocalDateTime overdueAt, LocalDateTime updatedAt) {
    }

    private record StateChange(String from, String to, String operatorType, Long operatorId, String remark,
                               LocalDateTime loggedAt) {
    }
}
