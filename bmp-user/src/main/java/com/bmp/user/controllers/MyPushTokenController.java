package com.bmp.user.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.user.client.PushServiceClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Where the app registers for push. Session 64.
 *
 * <h2>The user id comes from the token, never the body</h2>
 * Note what this endpoint does NOT accept: a user id. A caller-supplied one would let anybody
 * register THEIR device against SOMEBODY ELSE'S account and start receiving that person's booking
 * notifications — name, salon, time. A push token is a delivery address, and letting a client
 * choose whose address it is turns this into an interception endpoint with a friendly name.
 *
 * <p>Same rule as the support surface: a field that cannot be sent cannot be forged.
 */
@Tag(name = "My push tokens")
@RestController
@RequestMapping("/api/v1/me/push-token")
public class MyPushTokenController {

    private static final Logger log = LoggerFactory.getLogger(MyPushTokenController.class);

    private final PushServiceClient push;

    public MyPushTokenController(PushServiceClient push) {
        this.push = push;
    }

    public record RegisterRequest(@NotBlank String token, @NotBlank String platform, String deviceId) {}

    public record ReleaseRequest(@NotBlank String token) {}

    @Operation(summary = "Register this device for push notifications",
               description = "Idempotent. Re-registering the same token refreshes it, and revives it if it had been disabled.")
    @PostMapping
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest req,
                                          @AuthenticationPrincipal AuthenticatedUser me) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try {
            push.register(new PushServiceClient.RegisterPush(
                    me.userId(), req.token().trim(), req.platform().trim().toLowerCase(), req.deviceId()));
        } catch (Exception e) {
            /*
             * Never fail the app over this.
             *
             * Registration happens on launch, right after sign-in. If bmp-notification is down, the
             * correct outcome is "no push until next launch" — not a login that appears broken. The
             * app retries on every launch anyway, so a missed registration heals itself.
             */
            log.warn("Could not register a push token for {} — push will retry next launch ({})",
                    me.userId(), e.toString());
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Stop push to this device", description = "Called on sign-out. Affects only this device.")
    @DeleteMapping
    public ResponseEntity<Void> release(@Valid @RequestBody ReleaseRequest req,
                                         @AuthenticationPrincipal AuthenticatedUser me) {
        /*
         * ── WHOSE TOKEN? Session 65, an IDOR fix. ──────────────────────────────────────────────
         *
         * This method took no principal at all. It accepted a token string from the body and asked
         * bmp-notification to disable it — so any authenticated caller holding somebody else's
         * token could silently turn off that person's notifications, and the victim would never
         * find out, because a device that stops receiving push looks exactly like a device with
         * nothing to say.
         *
         * Expo tokens are long and random, so this was never trivially exploitable. It was still
         * "do you know the id", which is not authentication.
         *
         * The id now travels with the request and bmp-notification refuses a mismatch. Note the
         * REGISTER method above already did this correctly — the two sat next to each other and
         * only one of them asked who was calling.
         */
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try {
            push.release(new PushServiceClient.ReleasePush(req.token().trim(), me.userId()));
        } catch (Exception e) {
            // Same reasoning: a sign-out must complete regardless. Worst case the token stays live
            // until the provider reports it dead.
            log.warn("Could not release a push token on sign-out ({})", e.toString());
        }
        return ResponseEntity.noContent().build();
    }
}
