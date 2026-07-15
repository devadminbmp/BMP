package com.bmp.auth.internal.service;

import com.bmp.auth.internal.client.UserServiceClient;
import com.bmp.auth.internal.dto.*;
import com.bmp.auth.internal.entity.OtpRequests;
import com.bmp.auth.internal.entity.RefreshTokens;
import com.bmp.auth.internal.repository.OtpRequestsRepository;
import com.bmp.auth.internal.repository.RefreshTokensRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Owns the whole OTP -> JWT lifecycle. See BMP-31's original spec (Phase 3 tracker task)
 * for the full endpoint contract this implements.
 *
 * <p>Refresh tokens use a split selector/verifier pattern (like Symfony/Laravel-style
 * "remember me" tokens): the DB stores a plain, indexed `selector` for lookup, and a
 * bcrypt hash of the `verifier` for the actual secret check. This is necessary because
 * bcrypt hashes cannot be searched by value — you can't "SELECT WHERE token_hash = ?"
 * against a bcrypt column, since every encode() call produces a different hash even for
 * the same input (random salt). The client-facing opaque token is "selector.verifier".
 */
@Service
public class AuthService {

    private final OtpRequestsRepository otpRepo;
    private final RefreshTokensRepository refreshRepo;
    private final UserServiceClient userServiceClient;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    private final int otpTtlMinutes;
    private final int otpMaxAttempts;
    private final int otpLockoutMinutes;
    private final int refreshTokenTtlDays;

    public AuthService(
            OtpRequestsRepository otpRepo,
            RefreshTokensRepository refreshRepo,
            UserServiceClient userServiceClient,
            JwtService jwtService,
            @Value("${bmp.auth.otp-ttl-minutes}") int otpTtlMinutes,
            @Value("${bmp.auth.otp-max-attempts}") int otpMaxAttempts,
            @Value("${bmp.auth.otp-lockout-minutes}") int otpLockoutMinutes,
            @Value("${bmp.auth.refresh-token-ttl-days}") int refreshTokenTtlDays) {
        this.otpRepo = otpRepo;
        this.refreshRepo = refreshRepo;
        this.userServiceClient = userServiceClient;
        this.jwtService = jwtService;
        this.otpTtlMinutes = otpTtlMinutes;
        this.otpMaxAttempts = otpMaxAttempts;
        this.otpLockoutMinutes = otpLockoutMinutes;
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }

    public OtpRequestResponse requestOtp(String phone) {
        otpRepo.findTopByPhoneOrderByCreatedAtDesc(phone).ifPresent(existing -> {
            if (existing.getCreatedAt().isAfter(Instant.now().minusSeconds(55))) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "An OTP was already sent recently — wait before requesting another.");
            }
        });

        String code = String.format("%06d", random.nextInt(1_000_000));
        String hash = passwordEncoder.encode(code);
        Instant expiresAt = Instant.now().plus(otpTtlMinutes, ChronoUnit.MINUTES);

        OtpRequests entry = new OtpRequests(phone, hash, 0, null, expiresAt);
        otpRepo.save(entry);

        // TODO (Phase 3, BMP-8): send `code` via bmp-notification-service -> MSG91 WhatsApp,
        // SMS fallback. Not wired yet — this is the auth *mechanism*, not the send integration.

        return new OtpRequestResponse(entry.getId(), expiresAt);
    }

    public OtpVerifyResponse verifyOtp(OtpVerifyRequest req) {
        OtpRequests entry = otpRepo.findTopByPhoneOrderByCreatedAtDesc(req.phone())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No OTP requested for this phone"));

        if (entry.getLockedUntil() != null && entry.getLockedUntil().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "Too many incorrect attempts — locked until " + entry.getLockedUntil());
        }
        if (entry.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "OTP expired — request a new one");
        }
        if (!passwordEncoder.matches(req.otp(), entry.getOtpHash())) {
            entry.setAttempts(entry.getAttempts() + 1);
            if (entry.getAttempts() >= otpMaxAttempts) {
                entry.setLockedUntil(Instant.now().plus(otpLockoutMinutes, ChronoUnit.MINUTES));
            }
            otpRepo.save(entry);
            int remaining = Math.max(0, otpMaxAttempts - entry.getAttempts());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Incorrect OTP — " + remaining + " attempt(s) remaining before lockout");
        }

        UserDto user = findOrCreateUser(req.phone());

        String selector = randomUrlSafe(12);   // stored plain, indexed
        String verifier = randomUrlSafe(32);   // never stored plain
        String verifierHash = passwordEncoder.encode(verifier);

        RefreshTokens tokenRow = new RefreshTokens(
                user.id(), selector, verifierHash, req.deviceFingerprint(), false,
                Instant.now().plus(refreshTokenTtlDays, ChronoUnit.DAYS));
        refreshRepo.save(tokenRow);

        String opaqueRefreshToken = selector + "." + verifier;
        String accessToken = jwtService.generateAccessToken(user.id(), user.defaultRole());

        return new OtpVerifyResponse(user.id(), opaqueRefreshToken, accessToken, jwtService.getAccessTokenTtlSeconds());
    }

    public RefreshResponse refresh(String opaqueToken) {
        RefreshTokens row = lookupBySplitToken(opaqueToken);
        if (row.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }
        // Need the user's role to mint a new access token — a real implementation should
        // fetch this from bmp-user-service (getUserByPhone won't do; would need a
        // getUserById method added to UserServiceClient). Using "customer" as a placeholder
        // default here rather than silently guessing — flagged for whoever wires this fully.
        String accessToken = jwtService.generateAccessToken(row.getUserId(), "customer");
        return new RefreshResponse(accessToken, jwtService.getAccessTokenTtlSeconds());
    }

    public void logout(String opaqueToken) {
        RefreshTokens row = lookupBySplitToken(opaqueToken);
        row.setRevoked(true);
        refreshRepo.save(row);
    }

    private RefreshTokens lookupBySplitToken(String opaqueToken) {
        int dot = opaqueToken.indexOf('.');
        if (dot < 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Malformed refresh token");
        }
        String selector = opaqueToken.substring(0, dot);
        String verifier = opaqueToken.substring(dot + 1);

        RefreshTokens row = refreshRepo.findBySelectorAndRevokedFalse(selector)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown or revoked refresh token"));

        if (!passwordEncoder.matches(verifier, row.getTokenHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token verifier mismatch");
        }
        return row;
    }

    private String randomUrlSafe(int numBytes) {
        byte[] buf = new byte[numBytes];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private UserDto findOrCreateUser(String phone) {
        try {
            ResponseEntity<UserDto> existing = userServiceClient.getUserByPhone(phone);
            if (existing.getStatusCode().is2xxSuccessful() && existing.getBody() != null) {
                return existing.getBody();
            }
        } catch (Exception ignored) {
            // Feign throws on 404 by default; fall through to create-on-first-verify.
        }
        return userServiceClient.createUser(new CreateUserRequest(phone, "customer"));
    }
}
