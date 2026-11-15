package com.itheima.orderflow.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
class AuthService {

    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);

    private final AccountMapper accounts;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwtEncoder;
    private final String fallbackHash;

    AuthService(AccountMapper accounts, PasswordEncoder passwords, JwtEncoder jwtEncoder) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.jwtEncoder = jwtEncoder;
        this.fallbackHash = passwords.encode(UUID.randomUUID().toString());
    }

    String login(String username, String password) {
        if (username == null || username.isBlank() || username.length() > 64
                || password == null || password.isEmpty()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid login input");
        }

        AccountMapper.LoginAccount account = accounts.findByUsername(username);
        // Check a BCrypt hash even when the username is unknown.
        String hash = account == null ? fallbackHash : account.passwordHash();
        if (!passwords.matches(password, hash) || account == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(SecurityConfig.ISSUER)
                .subject(Long.toString(account.id()))
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_LIFETIME))
                .claim("role", account.role())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
