package com.bmp.auth.services;

import com.bmp.auth.client.SalonServiceClient;
import com.bmp.auth.client.UserServiceClient;
import com.bmp.auth.dto.*;
import com.bmp.auth.entities.OAuthIdentity;
import com.bmp.auth.entities.OtpRequests;
import com.bmp.auth.entities.RefreshTokens;
import com.bmp.auth.repositories.OAuthIdentityRepository;
import com.bmp.auth.repositories.OtpRequestsRepository;
import com.bmp.auth.repositories.RefreshTokensRepository;
import com.bmp.common.events.OtpRequested;
import com.bmp.common.events.UserRegistered;
import com.bmp.common.outbox.OutboxPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the whole OTP -> JWT lifecycle. See BMP-31's original spec (Phase 3 tracker task)
 * for the base contract; Session 6 adds dual-channel (email+phone) delivery, role-based
 * signup (CUSTOMER/SALON_OWNER/MANAGER/STYLIST), and Google sign-in for customers.
 *
 * <p>Refresh tokens use a split selector/verifier pattern (like Symfony/Laravel-style
 * "remember me" tokens): the DB stores a plain, indexed `selector` for lookup, and a
 * bcrypt hash of the `verifier` for the actual secret check. This is necessary because
 * bcrypt hashes cannot be searched by value — you can't "SELECT WHERE token_hash = ?"
 * against a bcrypt column, since every encode() call produces a different hash even for
 * the same input (random salt). The client-facing opaque token is "selector.verifier".
 *
 * <p>"Forgot password" doesn't have its own endpoint here on purpose: there is no password
 * in an OTP-only system, so recovering account access IS the normal login flow (request a
 * fresh OTP, verify it). Flagging this explicitly since the original ask named it as a
 * separate feature — it's the same code path, not a gap.
 *
 * <p>Session 7: {@code @RefreshScope} — otp-ttl-minutes/otp-max-attempts/otp-lockout-minutes/
 * refresh-token-ttl-days are all constructor-injected {@code @Value}s, tunable at runtime via
 * config-repo/bmp-auth-service.yml + a bus refresh (e.g. shortening the lockout window during
 * an incident) without redeploying this service.
 */
@Service
@RefreshScope
public class AuthService {

    private static final Set<String> VALID_ROLES = Set.of("customer", "salon_owner", "manager", "stylist");

    private final OtpRequestsRepository otpRepo;
    private final RefreshTokensRepository refreshRepo;
    private final OAuthIdentityRepository oauthRepo;
    private final UserServiceClient userServiceClient;
    private final SalonServiceClient salonServiceClient;
    private final JwtService jwtService;
    private final GoogleTokenVerifier googleVerifier;
    private final OutboxPublisher outbox;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    private final int otpTtlMinutes;
    private final int otpMaxAttempts;
    private final int otpLockoutMinutes;
    private final int refreshTokenTtlDays;

    public AuthService(
            OtpRequestsRepository otpRepo,
            RefreshTokensRepository refreshRepo,
            OAuthIdentityRepository oauthRepo,
            UserServiceClient userServiceClient,
            SalonServiceClient salonServiceClient,
            JwtService jwtService,
            GoogleTokenVerifier googleVerifier,
            OutboxPublisher outbox,
            @Value("${bmp.auth.otp-ttl-minutes}") int otpTtlMinutes,
            @Value("${bmp.auth.otp-max-attempts}") int otpMaxAttempts,
            @Value("${bmp.auth.otp-lockout-minutes}") int otpLockoutMinutes,
            @Value("${bmp.auth.refresh-token-ttl-days}") int refreshTokenTtlDays) {
        this.otpRepo = otpRepo;
        this.refreshRepo = refreshRepo;
        this.oauthRepo = oauthRepo;
        this.userServiceClient = userServiceClient;
        this.salonServiceClient = salonServiceClient;
        this.jwtService = jwtService;
        this.googleVerifier = googleVerifier;
        this.outbox = outbox;
        this.otpTtlMinutes = otpTtlMinutes;
        this.otpMaxAttempts = otpMaxAttempts;
        this.otpLockoutMinutes = otpLockoutMinutes;
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }

    /** Session 6: dual-channel — the same code goes out over email AND phone (see
     * OtpRequested/bmp-notification), not one-or-the-other. */
    @Transactional
    public OtpRequestResponse requestOtp(OtpRequestRequest req) {
        otpRepo.findTopByPhoneOrderByCreatedAtDesc(req.phone()).ifPresent(existing -> {
            if (existing.getCreatedAt().isAfter(Instant.now().minusSeconds(55))) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "An OTP was already sent recently — wait before requesting another.");
            }
        });

        UserDto existingUser = lookupUserByPhone(req.phone());
        String resolvedEmail = existingUser != null && existingUser.email() != null
                ? existingUser.email() : req.email();
        if (existingUser == null && resolvedEmail == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "email is required the first time a phone number signs up");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        String hash = passwordEncoder.encode(code);
        Instant expiresAt = Instant.now().plus(otpTtlMinutes, ChronoUnit.MINUTES);

        OtpRequests entry = new OtpRequests(req.phone(), resolvedEmail, hash, 0, null, expiresAt);
        otpRepo.save(entry);

        outbox.publish(new OtpRequested(entry.getId(), req.phone(), resolvedEmail, code, expiresAt));

        return new OtpRequestResponse(entry.getId(), expiresAt);
    }

    @Transactional
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

        UUID userId;
        String role;
        UUID salonId;

        UserDto existing = lookupUserByPhone(req.phone());
        if (existing != null) {
            userId = existing.id();
            role = existing.defaultRole();
            salonId = resolveSalonScope(userId, role);
        } else {
            String requestedRole = req.role() == null ? "customer" : req.role().toLowerCase();
            if (!VALID_ROLES.contains(requestedRole)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown role: " + req.role());
            }
            String email = existing != null ? null : req.email();
            if (email == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required to sign up");
            }

            UserDto created = userServiceClient.createUser(new CreateUserRequest(req.phone(), email, requestedRole));
            userId = created.id();
            role = requestedRole;
            salonId = null;

            switch (requestedRole) {
                case "manager" -> {
                    if (req.inviteToken() == null || req.inviteToken().isBlank()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "inviteToken is required to sign up as MANAGER — ask the salon owner for one");
                    }
                    ConsumeInviteResponse consumed = salonServiceClient.consumeInvite(
                            new ConsumeInviteRequest(req.inviteToken(), req.phone(), userId.toString()));
                    salonId = consumed.salonId();
                }
                case "stylist" -> {
                    String name = (req.name() == null || req.name().isBlank()) ? "New stylist" : req.name();
                    salonServiceClient.createStylist(new CreateStylistRequest(name, userId));
                    // salonId stays null on purpose — stylist_salon links are the portable-
                    // identity join, a stylist isn't scoped to one salon the way owner/manager
                    // are. See StylistCrudService's own javadoc for why.
                }
                case "salon_owner", "customer" -> { /* nothing extra at signup time */ }
            }

            if (req.googleSubject() != null && !req.googleSubject().isBlank()
                    && oauthRepo.findByProviderAndProviderSubject("google", req.googleSubject()).isEmpty()) {
                oauthRepo.save(new OAuthIdentity(userId, "google", req.googleSubject(), email));
            }

            outbox.publish(new UserRegistered(userId, req.phone(), email, requestedRole, salonId));
        }

        String selector = randomUrlSafe(12);   // stored plain, indexed
        String verifier = randomUrlSafe(32);   // never stored plain
        String verifierHash = passwordEncoder.encode(verifier);

        RefreshTokens tokenRow = new RefreshTokens(
                userId, selector, verifierHash, req.deviceFingerprint(), false,
                Instant.now().plus(refreshTokenTtlDays, ChronoUnit.DAYS));
        refreshRepo.save(tokenRow);

        String opaqueRefreshToken = selector + "." + verifier;
        String accessToken = jwtService.generateAccessToken(userId, role, salonId);

        return new OtpVerifyResponse(userId, opaqueRefreshToken, accessToken, jwtService.getAccessTokenTtlSeconds());
    }

    /** Session 6: Google sign-in for customers. See GoogleAuthResponse for why an unlinked
     * Google account doesn't immediately create a user (no phone number available). */
    @Transactional
    public GoogleAuthResponse loginWithGoogle(GoogleLoginRequest req) {
        GoogleTokenVerifier.VerifiedGoogleToken g = googleVerifier.verify(req.idToken());

        return oauthRepo.findByProviderAndProviderSubject("google", g.subject())
                .map(link -> {
                    ResponseEntity<UserDto> u = userServiceClient.getUserById(link.getUserId());
                    UserDto user = u.getBody();
                    if (user == null) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "linked user no longer exists");
                    }
                    UUID salonId = resolveSalonScope(user.id(), user.defaultRole());

                    String selector = randomUrlSafe(12);
                    String verifier = randomUrlSafe(32);
                    RefreshTokens tokenRow = new RefreshTokens(
                            user.id(), selector, passwordEncoder.encode(verifier), req.deviceFingerprint(), false,
                            Instant.now().plus(refreshTokenTtlDays, ChronoUnit.DAYS));
                    refreshRepo.save(tokenRow);

                    String accessToken = jwtService.generateAccessToken(user.id(), user.defaultRole(), salonId);
                    return new GoogleAuthResponse(true, user.id(), selector + "." + verifier, accessToken,
                            jwtService.getAccessTokenTtlSeconds(), user.email(), g.subject());
                })
                .orElseGet(() -> new GoogleAuthResponse(false, null, null, null, 0, g.email(), g.subject()));
    }

    @Transactional
    public RefreshResponse refresh(String opaqueToken) {
        RefreshTokens row = lookupBySplitToken(opaqueToken);
        if (row.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }

        ResponseEntity<UserDto> resp = userServiceClient.getUserById(row.getUserId());
        UserDto user = resp.getBody();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user no longer exists");
        }
        UUID salonId = resolveSalonScope(user.id(), user.defaultRole());
        String accessToken = jwtService.generateAccessToken(user.id(), user.defaultRole(), salonId);
        return new RefreshResponse(accessToken, jwtService.getAccessTokenTtlSeconds());
    }

    public void logout(String opaqueToken) {
        RefreshTokens row = lookupBySplitToken(opaqueToken);
        row.setRevoked(true);
        refreshRepo.save(row);
    }

    /** SALON_OWNER/MANAGER only — resolves their CURRENT salon_staff seat fresh on every
     * token mint, so a JWT never carries a stale salonId (e.g. an owner who just created
     * their first salon after signing up gets it on their very next refresh). CUSTOMER and
     * STYLIST are never salon-scoped this way (stylists: portable identity by design). */
    private UUID resolveSalonScope(UUID userId, String role) {
        if (!"salon_owner".equalsIgnoreCase(role) && !"manager".equalsIgnoreCase(role)) {
            return null;
        }
        try {
            ResponseEntity<StaffLookupResponse> resp = salonServiceClient.lookupStaff(userId);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                return resp.getBody().salonId();
            }
        } catch (Exception ignored) {
            // No staff seat yet (fresh SALON_OWNER signup) — null is correct, not an error.
        }
        return null;
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

    private UserDto lookupUserByPhone(String phone) {
        try {
            ResponseEntity<UserDto> existing = userServiceClient.getUserByPhone(phone);
            if (existing.getStatusCode().is2xxSuccessful() && existing.getBody() != null) {
                return existing.getBody();
            }
        } catch (Exception ignored) {
            // Feign throws on 404 by default — no user with this phone yet.
        }
        return null;
    }
}
