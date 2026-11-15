package com.itheima.orderflow.auth;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        String token = authService.login(request.username(), request.password());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LoginResponse(token));
    }

    @GetMapping("/me")
    Identity me(@AuthenticationPrincipal Jwt jwt) {
        return new Identity(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("role"));
    }

    record LoginRequest(String username, String password) {
        @Override
        public String toString() {
            return "LoginRequest";
        }
    }

    record LoginResponse(String accessToken) {
        @Override
        public String toString() {
            return "LoginResponse";
        }
    }

    record Identity(long accountId, String role) {
    }
}
