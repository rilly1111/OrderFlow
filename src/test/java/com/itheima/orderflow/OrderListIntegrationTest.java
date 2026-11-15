package com.itheima.orderflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.orderflow.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.Map;
import java.util.UUID;

import static com.itheima.orderflow.order.OrderStatus.CANCELLED;
import static com.itheima.orderflow.order.OrderStatus.PAID;
import static com.itheima.orderflow.order.OrderStatus.PENDING_PAYMENT;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {AuthIntegrationTests.TEST_JWT_PROPERTY, AuthIntegrationTests.TEST_NO_SCHEDULE_PROPERTY})
@AutoConfigureMockMvc
@Transactional
class OrderListIntegrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 1, 8, 0);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private ObjectMapper json;

    @Test
    void paginatesInCreationTimeOrderAndBreaksTiesByDescendingId() throws Exception {
        Account customer = loginAs("USER");
        String newest = insertOrder(customer.id(), PAID, CREATED_AT.plusHours(1));
        String firstAtSameTime = insertOrder(customer.id(), PENDING_PAYMENT, CREATED_AT);
        String secondAtSameTime = insertOrder(customer.id(), PENDING_PAYMENT, CREATED_AT);
        String oldest = insertOrder(customer.id(), CANCELLED, CREATED_AT.minusHours(1));

        mvc.perform(ordersFor(customer).param("page", "1").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.pages").value(2))
                .andExpect(jsonPath("$.pageNum").value(1))
                .andExpect(jsonPath("$.pageSize").value(3))
                .andExpect(jsonPath("$.records[*].orderNo", contains(newest, secondAtSameTime, firstAtSameTime)))
                .andExpect(jsonPath("$.records[0].status").value("PAID"))
                .andExpect(jsonPath("$.records[0].payAmount").value(12.30))
                .andExpect(jsonPath("$.records[0].createdAt").isString())
                .andExpect(jsonPath("$.records[0].id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.records[0].userId").doesNotHaveJsonPath());

        mvc.perform(ordersFor(customer).param("page", "2").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.pages").value(2))
                .andExpect(jsonPath("$.pageNum").value(2))
                .andExpect(jsonPath("$.records[*].orderNo", contains(oldest)));

        mvc.perform(ordersFor(customer).param("page", "3").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.pages").value(2))
                .andExpect(jsonPath("$.pageNum").value(3))
                .andExpect(jsonPath("$.records").isEmpty());
    }

    @Test
    void filtersByStatusWithinTheAuthenticatedAccountUsingDefaultPagination() throws Exception {
        Account customer = loginAs("USER");
        Account otherCustomer = loginAs("USER");
        String paidOrder = insertOrder(customer.id(), PAID, CREATED_AT);
        insertOrder(customer.id(), PENDING_PAYMENT, CREATED_AT);
        insertOrder(otherCustomer.id(), PAID, CREATED_AT);

        mvc.perform(ordersFor(customer).param("status", "PAID").param("userId", Long.toString(otherCustomer.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.pages").value(1))
                .andExpect(jsonPath("$.pageNum").value(1))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.records[*].orderNo", contains(paidOrder)));
    }

    @Test
    void returnsAnEmptyPageForCustomerWithoutOrders() throws Exception {
        mvc.perform(ordersFor(loginAs("USER")).param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.pages").value(0))
                .andExpect(jsonPath("$.pageNum").value(1))
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.records").isArray())
                .andExpect(jsonPath("$.records").isEmpty());
    }

    @Test
    void aLargePageNumberDoesNotWrapAroundToTheFirstPage() throws Exception {
        Account customer = loginAs("USER");
        insertOrder(customer.id(), PENDING_PAYMENT, CREATED_AT);

        mvc.perform(ordersFor(customer).param("page", "1073741825").param("size", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.pages").value(1))
                .andExpect(jsonPath("$.pageNum").value(1073741825))
                .andExpect(jsonPath("$.records").isEmpty());
    }

    @Test
    void requiresAnAuthenticatedCustomer() throws Exception {
        mvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());
        Account admin = loginAs("ADMIN");
        mvc.perform(ordersFor(admin))
                .andExpect(status().isForbidden());
        mvc.perform(head("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({"page, 0", "page, -1", "page, bad", "size, 0", "size, 51", "size, bad", "status, UNKNOWN"})
    void rejectsInvalidQueryParameters(String name, String value) throws Exception {
        mvc.perform(ordersFor(loginAs("USER")).param(name, value))
                .andExpect(status().isBadRequest());
    }

    private Account loginAs(String role) throws Exception {
        String username = "order-list-test-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounts (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
                username, passwords.encode(password), role, CREATED_AT);
        Long accountId = jdbc.queryForObject("SELECT id FROM accounts WHERE username = ?", Long.class, username);

        byte[] response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String token = json.readTree(response).required("accessToken").asText();
        return new Account(accountId, token);
    }

    private String insertOrder(long userId, OrderStatus status, LocalDateTime createdAt) {
        String orderNo = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO orders (order_no, user_id, pay_amount, status, version, created_at, updated_at)
                VALUES (?, ?, 12.30, ?, 0, ?, ?)
                """, orderNo, userId, status.name(), createdAt, createdAt);
        return orderNo;
    }

    private MockHttpServletRequestBuilder ordersFor(Account account) {
        return get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token());
    }

    private record Account(long id, String token) {
    }
}
