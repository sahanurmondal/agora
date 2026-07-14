package com.agora.identity.api;

import com.agora.identity.security.AuthenticatedUser;
import com.agora.identity.security.JwtUtil;
import com.agora.identity.user.User;
import com.agora.identity.user.UserService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    public AuthController(UserService userService, JwtUtil jwtUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }

    public record RegisterRequest(String username, String password) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record UserResponse(Long id, String username) {
    }

    public record TokenResponse(String token, @JsonProperty("expires_in") long expiresIn) {
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@RequestBody RegisterRequest request) {
        String username = requireText(request.username(), "username");
        String password = requireText(request.password(), "password");
        User user = userService.register(username, password);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UserResponse(user.getId(), user.getUsername()));
    }

    @PostMapping("/login")
    public TokenResponse login(@RequestBody LoginRequest request) {
        String username = requireText(request.username(), "username");
        String password = requireText(request.password(), "password");
        User user = userService.authenticate(username, password)
                .orElseThrow(InvalidCredentialsException::new);
        return new TokenResponse(jwtUtil.generateToken(user), jwtUtil.getExpirationSeconds());
    }

    /** Introspection endpoint — identity comes straight from the verified token claims. */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return new UserResponse(principal.id(), principal.username());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
