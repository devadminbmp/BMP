package com.bmp.notification.services;

import com.bmp.notification.entities.PushToken;
import com.bmp.notification.repositories.PushTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registering devices, and pushing to all of a person's devices. Session 64.
 *
 * <p>Sits between {@link PushSender} (which knows how to talk to Expo) and the dispatcher (which
 * knows what happened). Neither of those should know that a person can have three phones.
 */
@Service
public class PushDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(PushDeliveryService.class);

    private final PushTokenRepository tokens;
    private final PushSender sender;

    public PushDeliveryService(PushTokenRepository tokens, PushSender sender) {
        this.tokens = tokens;
        this.sender = sender;
    }

    /**
     * Register or refresh a device.
     *
     * <p>Upsert on the TOKEN, not on (user, token) — see {@link PushTokenRepository#findByToken}.
     * A phone handed from one person to another must move, not duplicate.
     */
    @Transactional
    public void register(UUID userId, String token, String platform, String deviceId) {
        if (token == null || token.isBlank()) return;

        tokens.findByToken(token).ifPresentOrElse(
                existing -> {
                    boolean changingHands = !existing.getUserId().equals(userId);
                    existing.seen(userId, platform, deviceId);
                    tokens.save(existing);
                    if (changingHands) {
                        // Worth a line: it is either a shared device or a sign-out that did not
                        // clean up, and both are worth being able to find later.
                        log.info("Push token moved to a different user — device reassigned.");
                    }
                },
                () -> tokens.save(new PushToken(userId, token, platform, deviceId)));
    }

    /**
     * Stop sending to one device. Called on sign-out.
     *
     * <p>Not on sign-out from every device — only the one signing out. Somebody logging out of a
     * borrowed laptop should not silence their own phone.
     */
    @Transactional
    public void release(String token, String reason) {
        release(token, reason, null);
    }

    /**
     * Disable one device's token, optionally PROVING it belongs to the caller. Session 65.
     *
     * <h2>The hole this closes</h2>
     * {@code release(token, reason)} disables whatever token it is handed, with no notion of whose
     * it is. bmp-user's sign-out endpoint passed a raw token from the request body and took no
     * principal at all, so any authenticated caller holding somebody else's token string could
     * silently turn off that person's notifications — and the victim would never know, because a
     * device that stops receiving push looks exactly like a device with push working and nothing
     * to say.
     *
     * <p>Expo tokens are long and random, so this was never trivially exploitable. It is still an
     * IDOR: the check was "do you know the id", which is not authentication.
     *
     * @param expectedUserId when non-null, the token is released ONLY if it belongs to this user.
     *                       Null preserves the old unscoped behaviour for the paths that need it —
     *                       the provider telling us a token is dead has no user context and is
     *                       authenticated as a SERVICE.
     */
    @Transactional
    public void release(String token, String reason, java.util.UUID expectedUserId) {
        tokens.findByToken(token).ifPresent(t -> {
            if (expectedUserId != null && !expectedUserId.equals(t.getUserId())) {
                /*
                 * Logged and ignored rather than thrown. This runs on SIGN-OUT, and a sign-out that
                 * fails because of a token mismatch is a user stuck signed in — a worse outcome
                 * than a stale token, which the provider will eventually report dead anyway.
                 * The log line is what makes a real attempt visible.
                 */
                log.warn("Refused a push-token release: token does not belong to {}", expectedUserId);
                return;
            }
            t.disable(reason);
            tokens.save(t);
        });
    }

    /**
     * Push to every live device this person has.
     *
     * <h2>Never throws</h2>
     * Push is an ADDITION to email, never a replacement. A person with a dead phone, no devices
     * registered, or Expo having a bad afternoon must not be able to fail the work that was trying
     * to reach them — the email has already been queued by the caller.
     *
     * @return how many devices were attempted. Zero is a normal answer, not a failure: most people
     *         have not installed the app.
     */
    @Transactional
    public int pushToUser(UUID userId, String title, String body, Map<String, String> data) {
        if (userId == null) return 0;

        List<PushToken> live;
        try {
            live = tokens.findByUserIdAndDisabledAtIsNull(userId);
        } catch (Exception e) {
            log.warn("Could not read push tokens for {} — email is unaffected ({})", userId, e.toString());
            return 0;
        }

        int attempted = 0;
        for (PushToken t : live) {
            try {
                sender.send(t.getToken(), title, body, data);
                attempted++;
            } catch (ExpoPushSender.DeviceNotRegisteredException dead) {
                /*
                 * The normal end of a token's life — reinstall, device restore, app left unopened.
                 * Disable it so we stop trying, and keep the row so a reinstall returning the same
                 * token can revive it. Not an error, and deliberately not logged as one: alerting
                 * on this would train everybody to ignore this channel's alerts.
                 */
                t.disable("device_not_registered");
                tokens.save(t);
            } catch (Exception e) {
                log.warn("Push to one device failed for {} — continuing with the rest ({})",
                        userId, e.toString());
            }
        }
        return attempted;
    }
}
