package com.itheima.orderflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.orderflow.order.Order;
import com.itheima.orderflow.order.OrderMapper;
import com.itheima.orderflow.order.OrderStatusLog;
import com.itheima.orderflow.order.OrderStatusLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

import static com.itheima.orderflow.order.OrderStatus.COMPLETED;
import static com.itheima.orderflow.order.OrderStatus.IN_PROGRESS;
import static com.itheima.orderflow.order.OrderStatus.OVERDUE;
import static com.itheima.orderflow.order.OrderStatus.PAID;
import static com.itheima.orderflow.order.OrderStatus.PENDING_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {AuthIntegrationTests.TEST_JWT_PROPERTY, AuthIntegrationTests.TEST_NO_SCHEDULE_PROPERTY})
@AutoConfigureMockMvc
@Transactional
class IndusFlowFulfillmentIntegrationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String EQUIPMENT_CODE = "PUMP-001";
    private static final String FAULT = "Abnormal vibration during operation";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private OrderMapper orders;
    @Autowired
    private OrderStatusLogMapper statusLogs;

    @Test
    void savesAndReturnsEquipmentInformationAndCalculatesTheAcceptanceDeadlineOnPayment() throws Exception {
        Account customer = loginAs("USER");
        String orderNo = create(customer);
        Order pending = order(orderNo);
        assertThat(pending.getEquipmentCode()).isEqualTo(EQUIPMENT_CODE);
        assertThat(pending.getFaultDescription()).isEqualTo(FAULT);
        assertThat(pending.getAcceptanceDeadlineAt()).isNull();
        assertThat(pending.getStartedAt()).isNull();
        assertThat(pending.getOverdueAt()).isNull();

        JsonNode paid = pay(customer, orderNo);
        LocalDateTime paidAt = LocalDateTime.parse(paid.required("paidAt").asText());
        LocalDateTime deadline = LocalDateTime.parse(paid.required("acceptanceDeadlineAt").asText());
        assertThat(deadline).isEqualTo(paidAt.plusHours(2));

        Order saved = order(orderNo);
        assertThat(saved.getPaidAt()).isEqualTo(paidAt);
        assertThat(saved.getAcceptanceDeadlineAt()).isEqualTo(deadline);
        assertThat(saved.getStartedAt()).isNull();
        assertThat(saved.getOverdueAt()).isNull();

        mvc.perform(authenticated(get("/api/orders/{orderNo}", orderNo), customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipmentCode").value(EQUIPMENT_CODE))
                .andExpect(jsonPath("$.faultDescription").value(FAULT))
                .andExpect(jsonPath("$.acceptanceDeadlineAt").value(paid.required("acceptanceDeadlineAt").asText()));
        mvc.perform(authenticated(get("/api/orders"), customer).param("status", "PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].orderNo").value(orderNo))
                .andExpect(jsonPath("$.records[0].equipmentCode").value(EQUIPMENT_CODE));
    }

    @ParameterizedTest
    @MethodSource("invalidEquipmentRequests")
    void requiresNonBlankEquipmentFieldsWithinColumnLimits(Map<String, ?> body) throws Exception {
        Account customer = loginAs("USER");
        mvc.perform(authenticated(post("/api/orders"), customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest());
        assertThat(orders.selectCount(Wrappers.<Order>lambdaQuery().eq(Order::getUserId, customer.id()))).isZero();
    }

    private static Stream<Map<String, ?>> invalidEquipmentRequests() {
        return Stream.of(
                Map.of("payAmount", 12.30, "faultDescription", FAULT),
                Map.of("payAmount", 12.30, "equipmentCode", EQUIPMENT_CODE),
                Map.of("payAmount", 12.30, "equipmentCode", " ", "faultDescription", FAULT),
                Map.of("payAmount", 12.30, "equipmentCode", EQUIPMENT_CODE, "faultDescription", " "),
                Map.of("payAmount", 12.30, "equipmentCode", "E".repeat(65), "faultDescription", FAULT),
                Map.of("payAmount", 12.30, "equipmentCode", EQUIPMENT_CODE, "faultDescription", "F".repeat(501)));
    }

    @Test
    void onlyAdministratorsStartPaidApplicationsAndCannotStartThemAgain() throws Exception {
        Account customer = loginAs("USER");
        Account administrator = loginAs("ADMIN");
        String orderNo = create(customer);
        pay(customer, orderNo);
        Order paid = order(orderNo);

        mvc.perform(post("/api/admin/orders/{orderNo}/start", orderNo)).andExpect(status().isUnauthorized());
        mvc.perform(start(orderNo, customer)).andExpect(status().isForbidden());
        mvc.perform(start(orderNo, administrator)).andExpect(status().isNoContent()).andExpect(content().string(""));

        Order started = order(orderNo);
        assertThat(started.getStatus()).isEqualTo(IN_PROGRESS);
        assertThat(started.getVersion()).isEqualTo(paid.getVersion() + 1);
        assertThat(started.getStartedAt()).isEqualTo(started.getUpdatedAt()).isBefore(paid.getAcceptanceDeadlineAt());
        assertThat(started.getOverdueAt()).isNull();
        assertThat(logs(started)).filteredOn(log -> log.getToStatus() == IN_PROGRESS).singleElement()
                .satisfies(log -> {
                    assertThat(log.getFromStatus()).isEqualTo(PAID);
                    assertThat(log.getOperatorType()).isEqualTo("ADMIN");
                    assertThat(log.getOperatorId()).isEqualTo(administrator.id());
                    assertThat(log.getCreatedAt()).isEqualTo(started.getStartedAt());
                });

        mvc.perform(start(orderNo, administrator)).andExpect(status().isConflict());
        assertThat(logs(started)).hasSize(3);
    }

    @Test
    void startsAnOverdueApplicationAndKeepsItsFirstOverdueTimeAfterCompletion() throws Exception {
        Account customer = loginAs("USER");
        Account administrator = loginAs("ADMIN");
        String orderNo = create(customer);
        pay(customer, orderNo);
        LocalDateTime deadline = pastDeadline();
        LocalDateTime overdueAt = deadline.plusMinutes(1);
        jdbc.update("""
                UPDATE orders SET status = 'OVERDUE', acceptance_deadline_at = ?, overdue_at = ?, version = version + 1
                WHERE order_no = ?
                """, deadline, overdueAt, orderNo);

        mvc.perform(start(orderNo, administrator)).andExpect(status().isNoContent());

        Order started = order(orderNo);
        assertThat(started.getStatus()).isEqualTo(IN_PROGRESS);
        assertThat(started.getOverdueAt()).isEqualTo(overdueAt);
        assertThat(logs(started)).filteredOn(log -> log.getToStatus() == IN_PROGRESS).singleElement()
                .satisfies(log -> assertThat(log.getFromStatus()).isEqualTo(OVERDUE));

        mvc.perform(authenticated(post("/api/admin/orders/{orderNo}/complete", orderNo), administrator))
                .andExpect(status().isNoContent());

        Order completed = order(orderNo);
        assertThat(completed.getStatus()).isEqualTo(COMPLETED);
        assertThat(completed.getOverdueAt()).isEqualTo(overdueAt);
        assertThat(completed.getStartedAt()).isEqualTo(started.getStartedAt());
    }

    @Test
    void recordsTheMissedDeadlineWhenAnAdministratorStartsBeforeTheNextScan() throws Exception {
        Account customer = loginAs("USER");
        Account administrator = loginAs("ADMIN");
        String orderNo = create(customer);
        pay(customer, orderNo);
        LocalDateTime deadline = pastDeadline();
        jdbc.update("UPDATE orders SET acceptance_deadline_at = ? WHERE order_no = ?", deadline, orderNo);

        mvc.perform(start(orderNo, administrator)).andExpect(status().isNoContent());

        Order started = order(orderNo);
        assertThat(started.getStatus()).isEqualTo(IN_PROGRESS);
        assertThat(started.getStartedAt()).isAfter(deadline);
        assertThat(started.getOverdueAt()).isEqualTo(started.getStartedAt());
        assertThat(started.getUpdatedAt()).isEqualTo(started.getStartedAt());
        assertThat(started.getVersion()).isEqualTo(3);
        List<OrderStatusLog> history = logs(started);
        assertThat(history).extracting(OrderStatusLog::getToStatus)
                .containsExactly(PENDING_PAYMENT, PAID, OVERDUE, IN_PROGRESS);
        assertThat(history.get(2).getFromStatus()).isEqualTo(PAID);
        assertThat(history.get(2).getOperatorType()).isEqualTo("SYSTEM");
        assertThat(history.get(2).getOperatorId()).isNull();
        assertThat(history.get(2).getCreatedAt()).isEqualTo(started.getStartedAt());
        assertThat(history.get(3).getFromStatus()).isEqualTo(OVERDUE);
        assertThat(history.get(3).getOperatorId()).isEqualTo(administrator.id());
        assertThat(history.get(3).getCreatedAt()).isEqualTo(started.getStartedAt());
    }

    private Account loginAs(String role) throws Exception {
        String username = "fulfillment-test-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounts (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
                username, passwords.encode(password), role, LocalDateTime.now(SHANGHAI));
        Long id = jdbc.queryForObject("SELECT id FROM accounts WHERE username = ?", Long.class, username);
        byte[] response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return new Account(id, json.readTree(response).required("accessToken").asText());
    }

    private String create(Account customer) throws Exception {
        byte[] response = mvc.perform(authenticated(post("/api/orders"), customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("payAmount", 12.30,
                                "equipmentCode", EQUIPMENT_CODE, "faultDescription", FAULT))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipmentCode").value(EQUIPMENT_CODE))
                .andExpect(jsonPath("$.faultDescription").value(FAULT))
                .andReturn().getResponse().getContentAsByteArray();
        return json.readTree(response).required("orderNo").asText();
    }

    private JsonNode pay(Account customer, String orderNo) throws Exception {
        byte[] response = mvc.perform(authenticated(post("/api/orders/{orderNo}/pay", orderNo), customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"ALIPAY\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return json.readTree(response);
    }

    private MockHttpServletRequestBuilder start(String orderNo, Account administrator) {
        return authenticated(post("/api/admin/orders/{orderNo}/start", orderNo), administrator);
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, Account account) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + account.accessToken());
    }

    private Order order(String orderNo) {
        return orders.selectOne(Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, orderNo));
    }

    private List<OrderStatusLog> logs(Order order) {
        return statusLogs.selectList(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getOrderId, order.getId()).orderByAsc(OrderStatusLog::getId));
    }

    private LocalDateTime pastDeadline() {
        return LocalDateTime.now(SHANGHAI).minusMinutes(5).truncatedTo(ChronoUnit.MILLIS);
    }

    private record Account(long id, String accessToken) {
    }
}
