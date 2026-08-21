package com.bmp.rewards.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.rewards.entities.Referral;
import com.bmp.rewards.services.ReferralService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Referral codes.
 *
 * <p>⚠️ <b>Attribution works; payout does not.</b> Referrals are recorded correctly, but nothing
 * credits a reward — that needs a booking-completed event this service doesn't consume and a
 * wallet credit that needs payments. Recording attribution now is still worth it, because it's
 * the part that can't be reconstructed later.
 *
 * <p><b>Do not advertise a referral programme until the payout path exists.</b> A customer who
 * invites three friends on the strength of "₹150 each" and receives nothing has been misled,
 * regardless of what the code does internally.
 */
@Tag(name = "Referrals", description = "Shareable codes and referral attribution. Reward payout is NOT implemented — see the class notes.")
@RestController
@RequestMapping("/api/v1/referrals")
public class ReferralController {

    private final ReferralService referrals;

    public ReferralController(ReferralService referrals) {
        this.referrals = referrals;
    }

    public record MyCodeResponse(String code, String shareMessage) {}

    public record AttributeRequest(@NotBlank String code, @NotNull UUID refereeUserId) {}

    public record AttributeResponse(UUID referralId, boolean accepted, String note) {}

    @Operation(
        summary = "My referral code",
        description = "Created on first request rather than at signup — most customers never share one, and generating millions of unused codes is work for its own sake.")
    @GetMapping("/my-code")
    @PreAuthorize("isAuthenticated()")
    public MyCodeResponse myCode(@AuthenticationPrincipal AuthenticatedUser caller) {
        if (caller == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "NO_PRINCIPAL");
        String code = referrals.codeFor(caller.userId());
        return new MyCodeResponse(code,
                "Join me on BMP — book salons with real availability. Use my code " + code + " when you sign up.");
    }

    @Operation(
        summary = "[internal] Attribute a signup to a referral code",
        description = """
            Called by bmp-auth when someone signs up with a code. Self-referral and \
            already-referred are RECORDED as fraud reasons rather than refused — you want to be \
            able to see that someone tried, and a hard refusal teaches them precisely which \
            check to work around next time. The reward simply never completes.""")
    @PostMapping("/internal/attribute")
    @PreAuthorize("hasRole('SERVICE')")
    public AttributeResponse attribute(@RequestBody AttributeRequest req) {
        Referral referral = referrals.attribute(req.code(), req.refereeUserId());
        boolean accepted = referral.getFraudReason() == null;
        return new AttributeResponse(
                referral.getId(),
                accepted,
                accepted
                        ? "Referral recorded. NOTE: reward payout is not implemented yet."
                        : "Recorded but flagged: " + referral.getFraudReason());
    }
}
