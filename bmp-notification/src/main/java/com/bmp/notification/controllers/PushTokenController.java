package com.bmp.notification.controllers;

import com.bmp.notification.services.PushDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Where the app tells us how to reach it. Session 64.
 *
 * <h2>INTERNAL. The app never calls this directly.</h2>
 * bmp-notification is written to by other SERVICES, never by a browser or an app — its
 * application.yml says so explicitly, and its public-paths list exists because an earlier version
 * left the whole message history open. Adding an app-facing endpoint here would quietly reverse
 * that decision.
 *
 * <p>So the device talks to {@code POST /api/v1/me/push-token} in bmp-user, which takes the user id
 * from the verified JWT and calls this endpoint as ROLE_SERVICE. Same shape as MyPrivacyController
 * and the support surface.
 *
 * <h2>Why the user id may be trusted in the body here, and nowhere else</h2>
 * Because the only caller is bmp-user over the internal network, holding the service key, and it
 * derived the id from a token it verified. A caller-supplied user id from an APP would be an
 * interception endpoint with a friendly name: register your device against somebody else's account
 * and receive their booking notifications, complete with name, salon and time. That is exactly why
 * this endpoint is not reachable from one.
 */
@Tag(name = "Push tokens", description = "Device registration for push notifications")
@RestController
@RequestMapping("/api/v1/notifications/internal/push")
public class PushTokenController {

    private final PushDeliveryService push;

    public PushTokenController(PushDeliveryService push) {
        this.push = push;
    }

    /**
     * @param token    the Expo push token from the device.
     * @param platform ios / android / web.
     * @param deviceId a stable per-install id, so one device replaces its own token on reinstall
     *                 instead of accumulating a row each time — which is how a person ends up
     *                 receiving four copies of everything.
     */
    public record RegisterRequest(java.util.UUID userId, @NotBlank String token,
                                   @NotBlank String platform, String deviceId) {}

    @Operation(summary = "Register this device for push",
               description = "Idempotent. Re-registering the same token refreshes it and revives it if it had been disabled.")
    @PostMapping("/register")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest req) {
        if (req.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        push.register(req.userId(), req.token().trim(), req.platform().trim().toLowerCase(),
                req.deviceId());
        return ResponseEntity.noContent().build();
    }

    public record ReleaseRequest(@NotBlank String token,
                          /** Session 65 — whose token, when the caller knows. Null on the provider-bounce path. */
                          java.util.UUID userId) {}

    /**
     * Stop pushing to this device. Called on sign-out.
     *
     * <p>Only this device, not every device the person owns: signing out of a borrowed laptop must
     * not silence their own phone.
     *
     * <p>Deliberately does not check that the token belongs to the caller. It is a request to STOP
     * sending, so the worst a malicious caller achieves is silencing a device they know the token
     * of — and they would need the token, which only ever leaves that device to reach us. Requiring
     * ownership would instead mean a sign-out after an account switch fails to clean up, leaving
     * the previous person's notifications arriving on someone else's phone. Between those two, the
     * quiet failure is the one that leaks data.
     */
    @Operation(summary = "Stop pushing to this device")
    @PostMapping("/release")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<Void> release(@Valid @RequestBody ReleaseRequest req) {
        /*
         * Session 65 — scoped when the caller told us whose token it is.
         *
         * bmp-user now sends the signed-in user's id on sign-out; the provider-bounce path does
         * not have one and passes null, which preserves the unscoped behaviour it needs. See
         * PushDeliveryService.release for why an ownership mismatch logs rather than throws.
         */
        push.release(req.token().trim(), "signed_out", req.userId());
        return ResponseEntity.noContent().build();
    }
}
