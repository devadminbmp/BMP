package com.bmp.admin.security;

import io.jsonwebtoken.Claims;
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
 * Mints and validates STAFF access tokens.
 *
 * <h2>Why this exists instead of reusing bmp-common's JWT plumbing</h2>
 * Two things must be true, and neither survives sharing a key:
 *
 * <ol>
 *   <li><b>A different signing secret.</b> If staff and customer tokens were signed with the
 *       same key, anything able to forge one could forge the other. Separate keys mean
 *       compromising the customer stack gives you nothing here.</li>
 *   <li><b>A different audience.</b> Even correctly signed, a staff token carries
 *       {@code aud=bmp-admin}, which no customer-facing service accepts; and a customer token
 *       fails here for the same reason. Audience is checked explicitly rather than assumed
 *       from the signature — "it verified, so it must be ours" is how tokens get accepted
 *       across boundaries they were never meant to cross.</li>
 * </ol>
 *
 * <h2>Short expiry, on purpose</h2>
 * 15 minutes. Staff refresh silently while working; a token found in a log or a browser on a
 * shared machine is stale almost immediately. The refresh token (staff_session) is the thing
 * that can be revoked instantly.
 */
@Service
public class AdminJwtService {

    /** The claim that keeps staff tokens out of customer services and vice versa. */
    public static final String AUDIENCE = "bmp-admin";

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";
    /** Marks the short-lived token issued between the password step and the 2FA step. */
    private static final String CLAIM_STAGE = "stage";
    public static final String STAGE_CHALLENGE = "totp_challenge";
    public static final String STAGE_SESSION = "session";

    private final SecretKey key;
    private final long accessTokenSeconds;
    private final long challengeTokenSeconds;

    public AdminJwtService(
            @Value("${bmp.admin.jwt-secret:dev-only-ADMIN-secret-change-me-min-32-bytes-long-really}") String secret,
            @Value("${bmp.admin.access-token-seconds:900}") long accessTokenSeconds,
            @Value("${bmp.admin.challenge-token-seconds:180}") long challengeTokenSeconds) {
        // A short key would throw at startup rather than silently weakening every token.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenSeconds = accessTokenSeconds;
        this.challengeTokenSeconds = challengeTokenSeconds;
    }

    /** A full session token, issued only after BOTH factors have passed. */
    public String issueAccessToken(UUID staffId, String email, String role) {
        return build(staffId, email, role, STAGE_SESSION, accessTokenSeconds);
    }

    /**
     * The intermediate token between "password correct" and "code correct".
     *
     * <p>Three minutes, and it carries {@code stage=totp_challenge} so it cannot be used as a
     * session token by anything that only checks the signature. Without this stage claim, a
     * half-authenticated token would be a full one — which is precisely the bug that makes
     * two-factor decorative.
     */
    public String issueChallengeToken(UUID staffId, String email, String role) {
        return build(staffId, email, role, STAGE_CHALLENGE, challengeTokenSeconds);
    }

    public long accessTokenSeconds() {
        return accessTokenSeconds;
    }

    /**
     * Parse and validate. Returns null for anything not a valid, in-date, correctly-audienced
     * token of the expected stage — callers treat null as "not authenticated" and never
     * distinguish why, because telling a caller *how* their token was wrong is free
     * reconnaissance.
     */
    public StaffPrincipal parse(String token, String expectedStage) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Audience checked explicitly rather than via the parser's require* helpers: in
            // jjwt 0.12 `aud` became a Set and the builder-side helper changed shape, so doing
            // it by hand is both version-proof and impossible to misread. "It verified, so it
            // must be ours" is how tokens get accepted across boundaries they were never
            // meant to cross — the whole point of this claim.
            var audience = claims.getAudience();
            if (audience == null || !audience.contains(AUDIENCE)) {
                return null;
            }

            if (!expectedStage.equals(claims.get(CLAIM_STAGE, String.class))) {
                return null;
            }
            return new StaffPrincipal(
                    UUID.fromString(claims.getSubject()),
                    claims.get(CLAIM_EMAIL, String.class),
                    claims.get(CLAIM_ROLE, String.class));
        } catch (Exception e) {
            // Expired, tampered, wrong audience, wrong key — all the same answer.
            return null;
        }
    }

    private String build(UUID staffId, String email, String role, String stage, long seconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(staffId.toString())
                .audience().add(AUDIENCE).and()
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_STAGE, stage)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(seconds)))
                .signWith(key)
                .compact();
    }
}
