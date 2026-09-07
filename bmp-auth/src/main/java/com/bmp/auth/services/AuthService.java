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
import com.bmp.common.security.AuthenticatedUser;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final Set<String> VALID_ROLES = Set.of("customer", "salon_owner", "manager", "stylist");

    /** Minimum gap between two {@code /otp/request} calls for the same phone. Surfaced to the
     * client as {@code OtpRequestResponse.resendAvailableAt} so it can show a resend timer;
     * enforced below with a 429. Kept as a constant (not a config value) because it's a UX
     * contract the frontend hardcodes a countdown against — changing it means changing both. */
    public static final int OTP_RESEND_COOLDOWN_SECONDS = 55;

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
    private final String devMasterOtp;
    /** Lower-cased, trimmed E.164 numbers the master OTP is allowed to unlock. Never empty in
     *  practice when devMasterOtp is set — see the constructor note. */
    private final Set<String> devMasterOtpPhones;

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
            @Value("${bmp.auth.refresh-token-ttl-days}") int refreshTokenTtlDays,
            @Value("${bmp.auth.dev-master-otp:}") String devMasterOtp,
            @Value("${bmp.auth.dev-master-otp-phones:}") String devMasterOtpPhones) {
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
        this.devMasterOtp = devMasterOtp;
        this.devMasterOtpPhones = Arrays.stream(devMasterOtpPhones.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        // Session 43: a master OTP with no allowlist is a master key to every account on the
        // platform. That was the old behaviour and it was fine while the only accounts were
        // seeded ones — but the moment a real signup exists it stops being fine, and nothing
        // in the code would have flagged the transition. Fail closed and say why.
        if (!this.devMasterOtp.isBlank() && this.devMasterOtpPhones.isEmpty()) {
            throw new IllegalStateException(
                    "bmp.auth.dev-master-otp is set but bmp.auth.dev-master-otp-phones is empty. "
                    + "An unrestricted master OTP unlocks EVERY account. List the seeded test "
                    + "numbers explicitly, or unset dev-master-otp to disable it entirely.");
        }
        if (!this.devMasterOtp.isBlank()) {
            log.warn("DEV MASTER OTP is ENABLED for {} seeded test number(s). This must never be "
                    + "set outside local development.", this.devMasterOtpPhones.size());
        }
    }

    /**
     * Is this the dev master OTP, for a phone that is allowed to use it?
     *
     * <h2>Session 43 — why the phone matters</h2>
     * {@code 000000} used to work for <b>any</b> number. That is convenient exactly until the
     * first real account exists, at which point it is a master key — and worse, it means the
     * real OTP path (generate → email → read → type) is never exercised in development, so the
     * first time anyone tests it is in front of a customer.
     *
     * <p>Restricting it to the six numbers in {@code seed/dev-seed.sql} keeps the convenience
     * where it belongs (fixed test personas) and forces every other number — including any new
     * account created while testing — down the real emailed-code path.
     *
     * <p>Note this is checked against the phone on the REQUEST, which is the same string the OTP
     * row was created under. There is no normalisation here on purpose: if the two didn't match,
     * the row lookup above would already have failed.
     */
    private boolean isDevMasterOtpFor(String phone, String submittedOtp) {
        if (devMasterOtp.isBlank() || !devMasterOtp.equals(submittedOtp)) {
            return false;
        }
        if (!devMasterOtpPhones.contains(phone)) {
            // Deliberately logged: someone typing the master OTP at a non-test number is either
            // a developer confused about which account they're on, or something worth noticing.
            log.warn("Dev master OTP submitted for phone={} which is NOT an allowlisted test "
                    + "number — treating it as an ordinary (wrong) code", maskPhone(phone));
            return false;
        }
        return true;
    }

    /** Session 6: dual-channel — the same code goes out over email AND phone (see
     * OtpRequested/bmp-notification), not one-or-the-other. */
    @Transactional
    public OtpRequestResponse requestOtp(OtpRequestRequest req) {
        /*
         * Session 65 — canonicalise BEFORE anything reads or writes the phone.
         *
         * Every line below (the cooldown lookup, the user lookup, the otp_requests row, the
         * published event) used `req.phone()` raw. One caller sending `918431710204` instead of
         * `+918431710204` would look up nothing, find no existing user, and create a SECOND account
         * for a number that already had one — past a UNIQUE index that compares bytes and cannot
         * see they are the same person. See canonicalPhone.
         */
        final OtpRequestRequest canonicalReq =
                new OtpRequestRequest(canonicalPhone(req.phone()), req.email());

        // Cooldown: reject a second request for the same phone inside the resend window.
        // The frontend already knows when resend is allowed (previous response's
        // resendAvailableAt), so hitting this 429 means either a retry storm or abuse.
        otpRepo.findTopByPhoneOrderByCreatedAtDesc(canonicalReq.phone()).ifPresent(existing -> {
            if (existing.getCreatedAt().isAfter(Instant.now().minusSeconds(OTP_RESEND_COOLDOWN_SECONDS))) {
                log.warn("OTP resend rejected (cooldown) for phone={}", maskPhone(canonicalReq.phone()));
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "An OTP was already sent recently — wait before requesting another.");
            }
        });

        // First-time signup needs an email (nothing on file to reuse); an existing user's
        // stored email always wins over whatever the request carries.
        UserDto existingUser = lookupUserByPhone(canonicalReq.phone());
        String resolvedEmail = existingUser != null && existingUser.email() != null
                ? existingUser.email() : canonicalReq.email();

        /*
         * Session 62 — refuse to issue a code we cannot deliver.
         *
         * This check used to be `existingUser == null && resolvedEmail == null`, which only covered
         * the SIGNUP case. An EXISTING user with no email on file fell straight through: a row was
         * written, `OtpRequested` was published with a null email, the client was told "we emailed
         * you a code", and NotificationDispatcher logged an error nobody was watching. Email is the
         * only live channel — SMS and WhatsApp are stubs — so that user simply could not log in,
         * and every screen told them everything had worked.
         *
         * That is not hypothetical. A DPDP erasure clears the email column and leaves the row
         * (see UserService.anonymise), and the phone becomes a non-dialable tombstone — but a user
         * whose email was never captured, or was cleared by any future path, lands here too.
         *
         * The rule: an OTP that cannot be delivered must fail LOUDLY at request time. A silent
         * success that produces nothing is the worst outcome available — the person retries,
         * burns the resend cooldown, and eventually concludes the product is broken.
         */
        if (resolvedEmail == null) {
            log.warn("Refusing to issue an OTP for phone={} — no email address available "
                    + "(existingUser={}). Email is the only live delivery channel.",
                    maskPhone(canonicalReq.phone()), existingUser != null);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    existingUser == null
                            ? "We'll need an email address — that's where the code goes."
                            : "There's no email address on this account, so we can't send the "
                              + "code. Contact support and we'll add one.");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        String hash = passwordEncoder.encode(code); // NEVER store or log the plaintext code
        Instant now = Instant.now();
        Instant expiresAt = now.plus(otpTtlMinutes, ChronoUnit.MINUTES);
        Instant resendAvailableAt = now.plusSeconds(OTP_RESEND_COOLDOWN_SECONDS);

        OtpRequests entry = new OtpRequests(canonicalReq.phone(), resolvedEmail, hash, 0, null, expiresAt);
        otpRepo.save(entry);

        // Dual-channel delivery happens asynchronously: the code goes onto the outbox and
        // bmp-notification sends it over both SMS and email (see OutboxKafkaRelay ->
        // NotificationDispatcher). We log that a code was issued, but NOT the code itself.
        outbox.publish(new OtpRequested(entry.getId(), canonicalReq.phone(), resolvedEmail, code, expiresAt));
        /*
         * The row id is logged on BOTH sides — here and in verify. Session 48.
         *
         * Added while chasing "the resent code is rejected as wrong". Everything about that bug is
         * invisible in the logs as they stood: you can see a code was issued and a verify failed,
         * but not WHETHER THEY WERE THE SAME ROW. If a resend creates row B and verify reads row
         * A, these two lines say so immediately; if they match, the problem is the code itself and
         * the search moves to delivery. One field, and it splits the hypothesis space in half.
         */
        log.info("OTP issued for phone={} (existingUser={}) otpRowId={} expiresIn={}m",
                maskPhone(canonicalReq.phone()), existingUser != null, entry.getId(), otpTtlMinutes);

        /*
         * Session 65 — tell the caller that this phone already has an account, and (masked) where
         * the code went.
         *
         * The behaviour above is unchanged and correct: for an existing account the STORED email
         * wins over whatever the request carried, because otherwise anybody could type somebody
         * else's phone number with their own address and be mailed that person's login code.
         *
         * What was missing is any way for the person in front of the screen to know that happened.
         * Filling in a signup form with a new email address, and receiving nothing at that address,
         * is indistinguishable from the system being broken — and was reported as exactly that.
         */
        return new OtpRequestResponse(entry.getId(), expiresAt, resendAvailableAt,
                existingUser != null, existingUser != null ? maskEmail(resolvedEmail) : null);
    }

    /**
     * {@code nidhitrikani50401@gmail.com} → {@code n••••••••01@gmail.com}. Session 65.
     *
     * <h2>Why masked, and not either extreme</h2>
     * The FULL address would turn this endpoint into an enumeration oracle: type any phone number,
     * read back that person's email. Unauthenticated, and it is the first half of a credential
     * stuffing list.
     *
     * NOTHING at all leaves the legitimate owner — who has three Gmail accounts and has just been
     * told a code was sent — with no way to know which inbox to open. That is the exact confusion
     * this field exists to remove.
     *
     * Keeping the first character, the LAST TWO before the @, and the whole domain is enough for
     * the owner to recognise their own address at a glance ("...01@gmail, that's my old one") and
     * not enough for a stranger to reconstruct it.
     */
    /**
     * One phone number, one spelling. Session 65.
     *
     * <h2>The gap this closes</h2>
     * {@code uk_users_phone} (V004) makes the phone UNIQUE — but a unique index compares BYTES.
     * {@code +918431710204}, {@code 918431710204} and {@code 08431710204} are three different byte
     * sequences and one human being, so the constraint would happily accept all three as separate
     * accounts. It cannot do otherwise; that check has to happen before the insert.
     *
     * <p>Normalisation existed only in the FRONTEND ({@code toE164} in validation.ts). That is fine
     * until anything else calls this API — a second screen that forgets, a partner integration, a
     * curl during testing — at which point the database grows a duplicate that the UNIQUE index was
     * supposed to prevent and nobody notices until two people share a login.
     *
     * <p>A server-side rule is the only one that holds for every caller. The client-side one stays,
     * because it gives immediate feedback while typing; this is the one that is load-bearing.
     *
     * <h2>What it does</h2>
     * Strips everything that is not a digit, drops a leading {@code 0} or {@code 91}, and re-prefixes
     * {@code +91}. Deliberately narrow: BMP is a Bengaluru product and every number is an Indian
     * mobile. A general E.164 library would be the right answer the day that stops being true, and
     * would be a large dependency and a lot of unused code today.
     *
     * <p>Returns the input UNCHANGED if it does not look like an Indian mobile, so a genuinely
     * foreign number is rejected by the existing validation with a clear message rather than being
     * silently mangled into a wrong one.
     */
    static String canonicalPhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");

        // 0XXXXXXXXXX — the old STD-prefix habit, still how many people write their own number.
        if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        // 91XXXXXXXXXX — country code without the plus.
        if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);

        // An Indian mobile is 10 digits starting 6-9. Anything else is left alone for the validator
        // to reject honestly rather than being reshaped into something that looks valid and isn't.
        if (digits.length() != 10 || digits.charAt(0) < '6' || digits.charAt(0) > '9') return raw;

        return "+91" + digits;
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return null;
        int at = email.indexOf('@');
        if (at < 1) return "•••";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 3) {
            return local.charAt(0) + "••" + domain;
        }
        return local.charAt(0) + "•".repeat(Math.max(2, local.length() - 3))
                + local.substring(local.length() - 2) + domain;
    }

    @Transactional
    public OtpVerifyResponse verifyOtp(OtpVerifyRequest req) {
        // Session 65 — same canonical form the code was issued under, or the lookup misses and a
        // perfectly correct code is rejected as unknown. See canonicalPhone.
        req = new OtpVerifyRequest(canonicalPhone(req.phone()), req.otp(), req.deviceFingerprint(),
                req.email(), req.role(), req.name(), req.inviteToken(), req.googleSubject(),
                req.loginOnly());

        OtpRequests entry = otpRepo.findTopByPhoneOrderByCreatedAtDesc(req.phone())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No OTP requested for this phone"));

        if (entry.getLockedUntil() != null && entry.getLockedUntil().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "Too many incorrect attempts — locked until " + entry.getLockedUntil());
        }
        if (entry.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "OTP expired — request a new one");
        }
        // Session 43 (V005): one-time means once. Before this, a verified code kept working for
        // the rest of its 5-minute TTL, so anyone who saw it — a forwarded email, a shared
        // screen — could reuse it after the legitimate login had already happened. Checked here,
        // ahead of the code comparison, for the same reason lockout and expiry are: a spent code
        // is not a wrong code, and burning an attempt on it would punish the wrong person.
        if (entry.isConsumed()) {
            log.warn("Replay of an already-consumed OTP for phone={} (consumed at {})",
                    maskPhone(req.phone()), entry.getConsumedAt());
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This code has already been used — request a new one");
        }
        boolean isDevMasterOtp = isDevMasterOtpFor(req.phone(), req.otp());
        if (!isDevMasterOtp && !passwordEncoder.matches(req.otp(), entry.getOtpHash())) {
            entry.setAttempts(entry.getAttempts() + 1);
            boolean nowLocked = entry.getAttempts() >= otpMaxAttempts;
            if (nowLocked) {
                entry.setLockedUntil(Instant.now().plus(otpLockoutMinutes, ChronoUnit.MINUTES));
            }
            otpRepo.save(entry);
            int remaining = Math.max(0, otpMaxAttempts - entry.getAttempts());
            // otpRowId + issuedAt: compare against the "OTP issued" line above. A mismatch means
            // we verified against a code the user was never sent — see that method's comment.
            log.warn("OTP verify FAILED for phone={} otpRowId={} issuedAt={} (attempt {}/{}{})",
                    maskPhone(req.phone()), entry.getId(), entry.getCreatedAt(),
                    entry.getAttempts(), otpMaxAttempts, nowLocked ? ", NOW LOCKED" : "");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Incorrect OTP — " + remaining + " attempt(s) remaining before lockout");
        }
        if (isDevMasterOtp) {
            log.warn("DEV MASTER OTP used for seeded test phone={} — must never appear in a real environment",
                    maskPhone(req.phone()));
        }

        // Spend the code. Must happen for the master OTP too: a test account is still an account,
        // and leaving its row unconsumed would keep a second code path alive that behaves
        // differently from production — which is how "works in dev" bugs are born.
        entry.markConsumed();
        otpRepo.save(entry);

        UUID userId;
        String role;
        UUID salonId;
        // isNewUser distinguishes signup (account created just now) from login (existing
        // account) — returned to the frontend so it can route to onboarding vs home.
        boolean isNewUser;

        UserDto existing = lookupUserByPhone(req.phone());
        if (existing != null) {
            isNewUser = false;
            userId = existing.id();
            role = existing.defaultRole();
            salonId = resolveSalonScope(userId, role);
            /*
             * ── BLOCKED? STOP HERE. Session 65. ────────────────────────────────────────────────
             *
             * This check MUST come before the reactivation below, and the ordering is the entire
             * fix. When Block was first wired up it set `deactivated_at`, so control fell into the
             * branch underneath — which exists to welcome back people who paused their own
             * account — and the block was lifted by the very login it was supposed to stop.
             *
             * The OTP was verified successfully to get here. That is deliberate: refusing before
             * verification would let anybody discover which numbers are blocked by watching where
             * the flow stops. They get a code, they enter it correctly, and then they are told no.
             */
            if (existing.blockedAt() != null) {
                log.warn("Blocked account attempted to sign in: user={} blockedAt={}", userId, existing.blockedAt());
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "ACCOUNT_BLOCKED: this account has been blocked. Contact support if you "
                        + "believe this is a mistake.");
            }

            if (existing.deactivatedAt() != null) {
                // Session 13: soft deactivation is reversed by the next successful OTP
                // login — this line IS the reactivation flow, there's no separate one.
                // Session 65: unreachable for a blocked account — see the guard above.
                log.info("Reactivating soft-deactivated user {} on login", userId);
                userServiceClient.reactivateUser(userId);
            }
            log.info("Login OK: user={} role={} salonId={}", userId, role, salonId);
        } else {
            // ── No account on this phone. Everything below CREATES one. ────────────────────
            // Session 43: a door that says "sign in" must never end up here silently. See
            // OtpVerifyRequest.loginOnly for why the intent is sent explicitly rather than
            // inferred from which optional fields happen to be present.
            if (req.isLoginOnly()) {
                log.info("Login-only verify for an unknown phone={} — refusing to create an account",
                        maskPhone(req.phone()));
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No account found for this number. Check the number, or sign up first.");
            }

            isNewUser = true;
            String requestedRole = req.role() == null ? "customer" : req.role().toLowerCase();
            if (!VALID_ROLES.contains(requestedRole)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown role: " + req.role());
            }
            String email = existing != null ? null : req.email();
            if (email == null) {
                // Session 43: this message used to read "email is required to sign up", which
                // described the CODE's immediate problem rather than the USER's situation. The
                // person reading it was almost never trying to sign up — they were logging in to
                // an account the service had failed to find, and being told to supply an email
                // they had already supplied one screen earlier. Darshan lost an evening to it.
                //
                // Say the thing that is actually true and actionable: there is no account here.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No account exists for this number yet. Sign up to create one — "
                        + "we'll need an email address for it.");
            }

            /*
             * req.name() — Session 65. It was omitted, so every account created here was nameless.
             *
             * Blank-to-null rather than passing "": an empty string is a name that renders as
             * nothing, which is indistinguishable on screen from no name and impossible to
             * distinguish in a query. Null means "not given" and every caller already handles it.
             */
            String signupName = (req.name() == null || req.name().isBlank()) ? null : req.name().trim();

            UserDto created = userServiceClient.createUser(
                    new CreateUserRequest(req.phone(), signupName, email, requestedRole));
            userId = created.id();
            role = requestedRole;
            salonId = null;

            // Role-specific signup side effects. Each non-customer role has to be wired to
            // the salon side here, in the SAME transaction as the user creation, so we never
            // end up with a half-onboarded staff member.
            switch (requestedRole) {
                case "manager" -> {
                    // A manager can't self-serve — they must present a token the salon owner
                    // generated for their phone (POST /api/v1/salons/{salonId}/invites).
                    if (req.inviteToken() == null || req.inviteToken().isBlank()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "inviteToken is required to sign up as MANAGER — ask the salon owner for one");
                    }
                    ConsumeInviteResponse consumed = salonServiceClient.consumeInvite(
                            new ConsumeInviteRequest(req.inviteToken(), req.phone(), userId.toString()));
                    salonId = consumed.salonId();
                    log.info("MANAGER signup: user={} attached to salon={} via invite", userId, salonId);
                }
                case "stylist" -> {
                    String name = (req.name() == null || req.name().isBlank()) ? "New stylist" : req.name();
                    StylistDto stylist = salonServiceClient.createStylist(new CreateStylistRequest(name, userId));
                    // salonId stays null on purpose — stylist_salon links are the portable-
                    // identity join, a stylist isn't scoped to one salon the way owner/manager
                    // are. See StylistCrudService's own javadoc for why.
                    log.info("STYLIST signup: user={} portable stylist profile created (name='{}')", userId, name);

                    // Session 17: an invite token is OPTIONAL for a stylist — unlike a manager,
                    // who cannot exist without one. A stylist can sign up alone (their profile
                    // is theirs, not a salon's), and a token simply attaches them to the salon
                    // that invited them, in the same transaction as the profile creation.
                    if (req.inviteToken() != null && !req.inviteToken().isBlank()) {
                        ConsumeInviteResponse consumed = salonServiceClient.consumeInvite(
                                new ConsumeInviteRequest(req.inviteToken(), req.phone(), userId.toString(),
                                        stylist.id()));
                        // NOTE: salonId is deliberately NOT set on the session. The link exists
                        // in stylist_salon, but a stylist's JWT stays unscoped — see
                        // resolveSalonScope, which excludes stylists for exactly this reason.
                        log.info("STYLIST signup: user={} linked to salon={} via invite", userId, consumed.salonId());
                    }
                }
                case "salon_owner" -> {
                    // Nothing to attach yet — the owner creates their salon afterward as an
                    // authenticated call (POST /api/v1/salons) and becomes its OWNER there.
                    // salonId stays null until their next /refresh picks up the new seat.
                    log.info("SALON_OWNER signup: user={} (salon created separately, post-signup)", userId);
                }
                case "customer" -> log.info("CUSTOMER signup: user={}", userId);
            }

            // Optional Google linkage carried through from an unlinked /oauth2/google response.
            if (req.googleSubject() != null && !req.googleSubject().isBlank()
                    && oauthRepo.findByProviderAndProviderSubject("google", req.googleSubject()).isEmpty()) {
                oauthRepo.save(new OAuthIdentity(userId, "google", req.googleSubject(), email));
                log.info("Linked Google identity to new user {}", userId);
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

        // Session 14: role/salonId/isNewUser now returned so the frontend can route without
        // decoding the JWT — see OtpVerifyResponse.
        return new OtpVerifyResponse(userId, role, salonId, isNewUser,
                opaqueRefreshToken, accessToken, jwtService.getAccessTokenTtlSeconds());
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
                    log.info("Google login OK (linked): user={} role={}", user.id(), user.defaultRole());
                    // Session 14: role/salonId added for frontend routing parity with /otp/verify.
                    return new GoogleAuthResponse(true, user.id(), user.defaultRole(), salonId,
                            selector + "." + verifier, accessToken,
                            jwtService.getAccessTokenTtlSeconds(), user.email(), g.subject());
                })
                .orElseGet(() -> {
                    // First time we've seen this Google account — no phone, so no user yet.
                    // Frontend collects a phone and finishes via /otp/request + /otp/verify,
                    // passing googleSubject through to link. role/salonId null here.
                    log.info("Google login: first-seen account (subject={}), returning linked=false", g.subject());
                    return new GoogleAuthResponse(false, null, null, null, null, null, 0, g.email(), g.subject());
                });
    }

    @Transactional
    public RefreshResponse refresh(String opaqueToken) {
        RefreshTokens row = lookupBySplitToken(opaqueToken);
        if (row.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }

        // Re-resolve role + salon scope LIVE (not from the old token) — this is what lets a
        // stale session self-correct, e.g. a SALON_OWNER who created their salon after login.
        ResponseEntity<UserDto> resp = userServiceClient.getUserById(row.getUserId());
        UserDto user = resp.getBody();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user no longer exists");
        }
        /*
         * ── A BLOCK HAS TO REACH PEOPLE WHO ARE ALREADY SIGNED IN. Session 65. ─────────────────
         *
         * Blocking only the login door leaves everybody currently holding a session untouched
         * until their refresh token expires — days. For the one case a block is usually FOR (an
         * account doing something right now) that is the entire window that mattered.
         *
         * The refresh token is revoked as well as refused, so the client cannot sit in a retry
         * loop against a token that will never work again, and a stolen refresh token stops being
         * worth anything the moment the account is blocked.
         */
        if (user.blockedAt() != null) {
            row.setRevoked(true);
            refreshRepo.save(row);
            log.warn("Refresh refused and token revoked — account blocked: user={} blockedAt={}",
                    user.id(), user.blockedAt());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ACCOUNT_BLOCKED: this account has been blocked. Contact support if you "
                    + "believe this is a mistake.");
        }

        UUID salonId = resolveSalonScope(user.id(), user.defaultRole());
        String accessToken = jwtService.generateAccessToken(user.id(), user.defaultRole(), salonId);
        log.debug("Refreshed access token for user={} role={} salonId={}", user.id(), user.defaultRole(), salonId);
        // Session 14: role/salonId returned so the frontend re-syncs them after every refresh.
        return new RefreshResponse(accessToken, jwtService.getAccessTokenTtlSeconds(), user.defaultRole(), salonId);
    }

    public void logout(String opaqueToken) {
        RefreshTokens row = lookupBySplitToken(opaqueToken);
        row.setRevoked(true);
        refreshRepo.save(row);
        log.info("Logout: refresh token revoked for user={}", row.getUserId());
    }

    /**
     * Session 14: the "who am I" call ({@code GET /api/v1/auth/me}). {@code role}/{@code salonId}
     * come from the presented JWT (authoritative for this session); the profile subset is
     * fetched live from bmp-user. Used by the frontend on app startup with a stored token, to
     * restore the session and pick which UI to show in one round-trip.
     */
    public MeResponse me(AuthenticatedUser principal) {
        ResponseEntity<UserDto> resp = userServiceClient.getUserById(principal.userId());
        UserDto user = resp.getBody();
        if (user == null) {
            // Token is valid but the user was hard-deleted out from under it — treat as unauth.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user no longer exists");
        }
        /*
         * Session 65 — 403, deliberately NOT 401.
         *
         * The frontend's interceptor treats 401 as "token expired" and tries a refresh, then
         * clears the session and shows the login screen. A blocked person going round that loop
         * sees a generic sign-in page and simply signs in again, which looks to them like the app
         * is broken and to us like the block failed.
         *
         * 403 with this code is a different thing and the app can say what actually happened.
         */
        if (user.blockedAt() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ACCOUNT_BLOCKED: this account has been blocked. Contact support if you "
                    + "believe this is a mistake.");
        }
        return new MeResponse(user.id(), user.phone(), user.name(), user.email(),
                principal.role(), principal.salonId(), user.isVerified());
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
            // 204 NO CONTENT is bmp-salon's documented "looked, found no seat" answer — see
            // StaffController.lookupStaff, which does `.orElseGet(() -> noContent())`. It is NOT
            // 404, so don't go looking for FeignException.NotFound here; that would 503 every
            // fresh SALON_OWNER who hasn't created their salon yet.
            if (resp.getStatusCode().value() == 204) {
                log.debug("No salon_staff seat for user={} yet — minting a salon-less token", userId);
                return null;
            }
            log.error("bmp-salon returned an unexpected {} for staff lookup user={}", resp.getStatusCode(), userId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not resolve your salon right now — please try again");
        } catch (FeignException.NotFound e) {
            // Belt and braces: if the internal route is ever moved/renamed we'd get a real 404
            // here. Treat it as "no seat" rather than an outage, matching the 204 contract.
            log.debug("staff-lookup returned 404 for user={} — treating as no seat", userId);
            return null;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // Session 43: this was `catch (Exception ignored)` too, and it is arguably worse than
            // the phone-lookup one, because it fails SILENTLY rather than loudly.
            //
            // Every salon-scoped @PreAuthorize in this codebase reads
            //   principal.salonId() != null and principal.salonId().equals(#salonId)
            // so a token minted with a null salonId is a token that can log in and then do
            // NOTHING — the owner lands on their dashboard and every panel 403s. They'd report
            // "the console is broken", not "bmp-salon is down", and nobody would look here.
            //
            // Refusing to mint the token turns a confusing, silent, half-working session into one
            // clear message and a retry. Omission must fail closed.
            log.error("bmp-salon unreachable while resolving the salon seat for user={} — "
                    + "refusing to mint a token with a null salonId", userId, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not resolve your salon right now — please try again");
        }
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

    /** Masks a phone number for logs — keeps the country code and last 2 digits, hides the
     * rest (e.g. +919876543210 -> +91******10). We log auth activity for debugging, but a
     * full phone number is PII that shouldn't sit in plaintext log files. */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 5) {
            return "***";
        }
        return phone.substring(0, 3) + "*".repeat(phone.length() - 5) + phone.substring(phone.length() - 2);
    }

    /**
     * "Is there already an account on this phone?" — the question that decides LOGIN vs SIGNUP.
     *
     * <h2>Session 43: this used to be {@code catch (Exception ignored) { return null; }}</h2>
     * That single line made an <b>outage indistinguishable from a fact</b>. bmp-user being down,
     * a wrong {@code X-Internal-Service-Key} (the endpoint is {@code hasRole('SERVICE')}, so a bad
     * key is a 403), Eureka having no instance registered, a socket timeout — every one of them
     * came back as "no such user", and the caller then confidently took the SIGNUP branch for a
     * person who has had an account for months.
     *
     * <p>Two ways that hurt, in ascending order of seriousness:
     * <ol>
     *   <li><b>The visible one.</b> {@code verifyOtp} falls into the signup branch, which has no
     *       email to work with (email is collected at the /otp/request step, not /otp/verify), so
     *       the user is told <i>"email is required to sign up"</i>. Baffling, because they weren't
     *       signing up. Darshan hit exactly this logging in as the seeded salon owner.</li>
     *   <li><b>The dangerous one.</b> If the request DOES carry an email, we sail past that guard
     *       and call {@code createUser} — creating a <b>second account on a phone that already has
     *       one</b>, orphaning the original's bookings, salon and history. The unique index on
     *       {@code users.phone} (V004) is the only thing that stops it, and it stops it with a 409
     *       the user can't act on. A uniqueness constraint catching a logic error is luck, not
     *       design.</li>
     * </ol>
     *
     * <p>So: <b>only a 404 means "no such user".</b> That is bmp-user's deliberate, documented
     * signal ({@code UserService.getByPhone} throws {@code NOT_FOUND / "USER_NOT_FOUND"}).
     * Anything else is us failing to find out, which is not the same as finding out there's
     * nothing there — and the honest answer to "I don't know" is 503, not a guess. Failing closed
     * costs one retry; failing open costs a duplicate account.
     *
     * @return the existing user, or {@code null} iff bmp-user affirmatively answered 404
     * @throws ResponseStatusException 503 if bmp-user could not be reached or answered anything else
     */
    private UserDto lookupUserByPhone(String phone) {
        try {
            ResponseEntity<UserDto> existing = userServiceClient.getUserByPhone(phone);
            if (existing.getStatusCode().is2xxSuccessful() && existing.getBody() != null) {
                return existing.getBody();
            }
            // A 2xx with an empty body isn't a contract bmp-user has; treat it as unusable
            // rather than as absence, for the same reason as below.
            log.error("bmp-user returned {} with no body for phone={} — treating as UNAVAILABLE, "
                    + "NOT as 'no such user'", existing.getStatusCode(), maskPhone(phone));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not verify the account right now — please try again");
        } catch (FeignException.NotFound e) {
            // The ONLY branch that may return null. bmp-user looked, and there is no such user.
            log.debug("No existing user for phone={} — this is a signup", maskPhone(phone));
            return null;
        } catch (FeignException e) {
            // 403 = internal-service-key mismatch; 5xx = bmp-user is broken; etc.
            log.error("bmp-user rejected the phone lookup for phone={} with status {} — "
                    + "refusing to guess whether this is a signup", maskPhone(phone), e.status(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not verify the account right now — please try again");
        } catch (ResponseStatusException e) {
            throw e; // our own 503 from the empty-body branch above — don't re-wrap it
        } catch (Exception e) {
            // No Eureka instance, connection refused, read timeout, LoadBalancer errors.
            // THIS is the case that used to silently mean "new user".
            log.error("bmp-user is unreachable while looking up phone={} — refusing to guess "
                    + "whether this is a signup", maskPhone(phone), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not verify the account right now — please try again");
        }
    }
}
