package com.itheima.orderflow;

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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {AuthIntegrationTests.TEST_JWT_PROPERTY, AuthIntegrationTests.TEST_NO_SCHEDULE_PROPERTY})
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTests {

    static final String TEST_NO_SCHEDULE_PROPERTY = "indusflow.fulfillment.scan-enabled=false";

    // This key is public test data. The running application needs its own key.
    static final String TEST_JWT_PROPERTY =
            "orderflow.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void userAndAdminLogInAndReadTheirOwnIdentity() throws Exception {
        assertLoginIdentity("USER");
        String adminToken = assertLoginIdentity("ADMIN");

        mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsBadCredentialsAndRequestsWithoutAValidToken() throws Exception {
        String username = insertAccount("USER", UUID.randomUUID().toString());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(username, UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer invalid-test-token"))
                .andExpect(status().isUnauthorized());
    }

    private String assertLoginIdentity(String role) throws Exception {
        String password = UUID.randomUUID().toString();
        String username = insertAccount(role, password);
        Long accountId = jdbc.queryForObject("SELECT id FROM accounts WHERE username = ?", Long.class, username);

        byte[] response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(username, password)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        String token = json.readTree(response).required("accessToken").asText();

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.role").value(role));
        return token;
    }

    private String insertAccount(String role, String password) {
        String username = "auth-test-" + UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
                username, passwords.encode(password), role, LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        return username;
    }

    private String loginJson(String username, String password) throws Exception {
        return json.writeValueAsString(Map.of("username", username, "password", password));
    }
}
