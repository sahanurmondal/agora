package com.agora.identity.security;

import com.agora.identity.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * HS256 JWT issue/verify. Adapted from MyChatApp's {@code com.chat.util.JwtUtil}
 * (deliberate harvest) with two fixes for platform use:
 * <ul>
 *   <li>the signing key comes from {@code JWT_SECRET} instead of a random per-boot
 *       {@code Keys.secretKeyFor(...)} — a random key would invalidate every token on
 *       restart and could never be shared with the edge-gateway for local validation;</li>
 *   <li>jjwt 0.12 fluent API (typed {@code SecretKey}, {@code parseSignedClaims}).</li>
 * </ul>
 * Claims kept deliberately minimal: {@code sub} = username, {@code uid} = numeric id,
 * {@code iat}/{@code exp} with a 1h TTL (short expiry IS the revocation strategy — ADR-001).
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration-seconds:3600}") long expirationSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Parses and verifies signature + expiry in one step.
     * Throws {@link io.jsonwebtoken.JwtException} on any invalid/expired/tampered token.
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
