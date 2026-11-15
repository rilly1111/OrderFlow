package com.itheima.orderflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.orderflow.order.Order;
import com.itheima.orderflow.order.OrderMapper;
import com.itheima.orderflow.order.OrderStatus;
import com.itheima.orderflow.order.OrderStatusLog;
import com.itheima.orderflow.order.OrderStatusLogMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {AuthIntegrationTests.TEST_JWT_PROPERTY, AuthIntegrationTests.TEST_NO_SCHEDULE_PROPERTY})
@AutoConfigureMockMvc
class OrderCreationIntegrationTest {

    private static final String VALID_ORDER = """
            {"payAmount":12.30,"equipmentCode":"PUMP-001","faultDescription":"Abnormal vibration"}
            """;

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

    @MockitoSpyBean
    private OrderStatusLogMapper logMapper;

    private Long accountId;

    @Test
    void createsOrderAndInitialLogWithTheAuthenticatedCustomerId() throws Exception {
        String accessToken = loginAs("USER");
        // The request body cannot select the owner of the new order.
        long otherUserId = accountId + 1;
        byte[] response = mvc.perform(authorizedOrder(accessToken,
                        json.writeValueAsString(Map.of("payAmount", 12.30, "userId", otherUserId,
                                "equipmentCode", "PUMP-001", "faultDescription", "Abnormal vibration"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.payAmount").value(12.30))
                .andReturn().getResponse().getContentAsByteArray();
        var body = json.readTree(response);
        String orderNo = body.required("orderNo").asText();
        assertThat(orderNo).matches("[0-9a-f]{32}");

        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, orderNo));
        assertThat(order.getId()).isPositive();
        assertThat(order.getUserId()).isEqualTo(accountId);
        assertThat(order.getPayAmount()).isEqualByComparingTo("12.30");
        assertThat(order.getVersion()).isZero();
        assertThat(order.getCreatedAt()).isEqualTo(LocalDateTime.parse(body.required("createdAt").asText()));
        assertThat(order.getUpdatedAt()).isEqualTo(order.getCreatedAt());

        assertThat(logMapper.selectList(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getOrderId, order.getId())))
                .singleElement().satisfies(log -> {
                    assertThat(log.getOrderId()).isEqualTo(order.getId());
                    assertThat(log.getFromStatus()).isNull();
                    assertThat(log.getToStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
                    assertThat(log.getOperatorType()).isEqualTo("USER");
                    assertThat(log.getOperatorId()).isEqualTo(accountId);
                    assertThat(log.getCreatedAt()).isEqualTo(order.getCreatedAt());
                });
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deniesCreationToAdmin() throws Exception {
        mvc.perform(authorizedOrder(loginAs("ADMIN"), VALID_ORDER))
                .andExpect(status().isForbidden());
        assertThat(orderCount()).isZero();
    }

    @Test
    void rejectsInvalidAmountBeforeWriting() throws Exception {
        mvc.perform(authorizedOrder(loginAs("USER"), """
                        {"payAmount":1.605,"equipmentCode":"PUMP-001","faultDescription":"Abnormal vibration"}
                        """))
                .andExpect(status().isBadRequest());
        assertThat(orderCount()).isZero();
    }

    @Test
    void rollsBackOrderWhenInitialLogWriteFails() throws Exception {
        String accessToken = loginAs("USER");
        doAnswer(invocation -> {
            OrderStatusLog log = invocation.getArgument(0);
            assertThat(log.getOrderId()).isPositive();
            assertThat(orderCount()).isEqualTo(1);
            throw new DataIntegrityViolationException("Simulated status log insert failure");
        }).when(logMapper).insert(any(OrderStatusLog.class));

        // With no test-managed transaction, the following read sees the Service transaction's outcome.
        assertThatThrownBy(() -> mvc.perform(authorizedOrder(accessToken, VALID_ORDER)))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(DataIntegrityViolationException.class);
        assertThat(orderCount()).isZero();
    }

    @AfterEach
    void removeTestAccountAndItsOrders() {
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

    private String loginAs(String role) throws Exception {
        String username = "order-create-test-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounts (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
                username, passwords.encode(password), role, LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        accountId = jdbc.queryForObject("SELECT id FROM accounts WHERE username = ?", Long.class, username);

        byte[] response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return json.readTree(response).required("accessToken").asText();
    }

    private MockHttpServletRequestBuilder authorizedOrder(String accessToken, String body) {
        return post("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Long orderCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Long.class, accountId);
    }
}
