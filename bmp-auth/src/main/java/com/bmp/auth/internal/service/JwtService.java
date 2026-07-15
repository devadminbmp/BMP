package com.bmp.auth.internal.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates access tokens. tokenType claim distinguishes a customer/salon-role
 * token from a bmp_staff token — Phase-3-style authorization middleware (not built yet)
 * MUST check this claim, not just the role, since bmp_staff has zero shared auth with
 * users per the Schema Rule in CONTEXT.md.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTokenTtlSeconds;

    public JwtService(
            @Value("${bmp.auth.jwt-secret}") String secret,
            @Value("${bmp.auth.access-token-ttl-seconds}") long accessTokenTtlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public String generateAccessToken(UUID userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .claim("tokenType", "user")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTokenTtlSeconds)))
                .signWith(key)
                .compact();
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }
}
