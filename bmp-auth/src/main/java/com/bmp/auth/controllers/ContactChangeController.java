package com.bmp.auth.controllers;

import com.bmp.auth.services.ContactChangeService;
import com.bmp.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Change your own phone or email. Session 65.
 *
 * <h2>The user id comes from the TOKEN, never from the request</h2>
 * There is no {@code userId} path variable or body field anywhere in this controller, and that is
 * the security property, not an omission. An endpoint that accepted a user id would have to
 * authorise it — and the day that check is wrong, anybody signed in can point somebody else's
 * account at their own phone number, which is a complete account takeover in one request.
 *
 * <p>Taking the id from {@link AuthenticatedUser} makes that class of bug unreachable: the only
 * account you can name is the one you are holding a token for.
 *
 * <h2>Two steps, because one would prove nothing</h2>
 * Request, then confirm with a code. A single "change my number" endpoint would let a borrowed
 * unlocked phone — or a stolen access token — move the login identity in one tap, with no second
 * factor and nothing sent anywhere the real owner would see.
 */
@Tag(name = "Contact change", description = "Change your own phone or email, confirmed by a code.")
@RestController
@RequestMapping("/api/v1/auth/contact")
public class ContactChangeController {

    private final ContactChangeService service;

    public ContactChangeController(ContactChangeService service) {
        this.service = service;
    }

    /**
     * @param phone E.164 or a bare Indian mobile — canonicalised server-side, so the app does not
     *              have to agree with us about formatting. Null or blank leaves the phone alone.
     * @param email null or blank leaves the email alone.
     */
    public record ChangeRequest(
            @Pattern(regexp = "^$|^\\+?[0-9 \\-]{10,15}$", message = "That doesn't look like a phone number.")
            String phone,
            @Pattern(regexp = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "That doesn't look like an email address.")
            String email) {}

    /**
     * @param sentToMasked   where to look. Masked, because echoing a full address back is a small
     *                       gift to anybody reading over a shoulder.
     * @param provesNewNumber false for a phone-only change — the code went to the existing email
     *                       and confirms the requester, not the new number. The app says so
     *                       plainly rather than implying a verification that did not happen.
     */
    public record RequestAccepted(String sentToMasked, Instant expiresAt, boolean provesNewNumber) {}

    @Operation(summary = "Ask to change your phone and/or email",
               description = "Sends a confirmation code. Nothing changes until it is confirmed.")
    @PostMapping("/request")
    @PreAuthorize("isAuthenticated()")
    public RequestAccepted request(@Valid @RequestBody ChangeRequest req,
                                    @AuthenticationPrincipal AuthenticatedUser me) {
        var result = service.request(me.userId(), req.phone(), req.email());
        return new RequestAccepted(result.sentToMasked(), result.expiresAt(), result.provesNewNumber());
    }

    public record ConfirmRequest(@NotBlank String code) {}

    /**
     * Confirm and apply.
     *
     * <p>The session deliberately SURVIVES this. Signing the person out on success would be the
     * cautious-looking choice and is the wrong one here: a phone change cannot be verified against
     * the new number today, so a typo needs correcting — and the only person who can correct it
     * quickly is somebody still signed in. Ending the session locks them out of fixing their own
     * mistake.
     */
    @Operation(summary = "Confirm the change with the code",
               description = "Single-use, 10 minutes, five attempts. You stay signed in — see the source for why.")
    @PostMapping("/confirm")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> confirm(@Valid @RequestBody ConfirmRequest req,
                                         @AuthenticationPrincipal AuthenticatedUser me) {
        service.verify(me.userId(), req.code());
        return ResponseEntity.noContent().build();
    }
}
