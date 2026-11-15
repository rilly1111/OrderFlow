package com.itheima.orderflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {AuthIntegrationTests.TEST_JWT_PROPERTY, AuthIntegrationTests.TEST_NO_SCHEDULE_PROPERTY})
@AutoConfigureMockMvc
@Transactional
class OrderDetailIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private ObjectMapper json;

    @Test
    void customerCanReadOwnOrderUsingOnlyTheAuthenticatedIdentity() throws Exception {
        String token = loginAs("USER");
        JsonNode created = createOrder(token);
        String orderNo = created.required("orderNo").asText();
        String createdAt = created.required("createdAt").asText();

        mvc.perform(authorizedDetail(orderNo, token).param("userId", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(orderNo))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.payAmount").value(12.30))
                .andExpect(jsonPath("$.paymentMethod").value(nullValue()))
                .andExpect(jsonPath("$.paidAt").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value(createdAt))
                .andExpect(jsonPath("$.updatedAt").value(createdAt))
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.userId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.paymentTransactionNo").doesNotHaveJsonPath());
    }

    @Test
    void otherCustomersOrderReturnsNotFound() throws Exception {
        String orderNo = createOrder(loginAs("USER")).required("orderNo").asText();
        String otherToken = loginAs("USER");

        mvc.perform(authorizedDetail(orderNo, otherToken))
                .andExpect(status().isNotFound())
                .andExpect(status().reason("Order not found"));
    }

    @Test
    void missingOrderReturnsNotFound() throws Exception {
        String missingOrderNo = UUID.randomUUID().toString().replace("-", "");

        mvc.perform(authorizedDetail(missingOrderNo, loginAs("USER")))
                .andExpect(status().isNotFound())
                .andExpect(status().reason("Order not found"));
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(get("/api/orders/{orderNo}", "example-order"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAdminRequests() throws Exception {
        String orderNo = createOrder(loginAs("USER")).required("orderNo").asText();
        String adminToken = loginAs("ADMIN");

        mvc.perform(authorizedDetail(orderNo, adminToken))
                .andExpect(status().isForbidden());
        mvc.perform(head("/api/orders/{orderNo}", orderNo).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    private String loginAs(String role) throws Exception {
        String username = "order-detail-test-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounts (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
                username, passwords.encode(password), role, LocalDateTime.now(ZoneId.of("Asia/Shanghai")));

        byte[] response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return json.readTree(response).required("accessToken").asText();
    }

    private JsonNode createOrder(String token) throws Exception {
        byte[] response = mvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payAmount":12.30,"equipmentCode":"PUMP-001","faultDescription":"Abnormal vibration"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();
        return json.readTree(response);
    }

    private MockHttpServletRequestBuilder authorizedDetail(String orderNo, String token) {
        return get("/api/orders/{orderNo}", orderNo).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
