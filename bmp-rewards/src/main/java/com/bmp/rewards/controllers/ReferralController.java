package com.bmp.rewards.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.rewards.entities.Referral;
import com.bmp.rewards.services.ReferralProgramService;
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
    /**
     * Session 64 — so the app can TELL people what the offer is.
     *
     * Without this the admin could set the amounts and no customer would ever learn them: the
     * wallet screen showed a code and no reward. A referral programme nobody is told about is a
     * programme nobody uses.
     */
    private final ReferralProgramService program;

    public ReferralController(ReferralService referrals, ReferralProgramService program) {
        this.referrals = referrals;
        this.program = program;
    }

    /**
     * @param shareMessage written server-side, including the amounts, so the offer in the message
     *                     and the offer that actually pays cannot drift. The app shows it verbatim.
     */
    public record MyCodeResponse(String code, String shareMessage) {}

    /**
     * What the offer is right now, for display. Session 64.
     *
     * @param active false when neither side pays — the app hides the referral card entirely rather
     *               than inviting somebody to share a code that earns nothing. Sent as a computed
     *               boolean so the client cannot decide "active" differently from the payout does.
     *
     * <p>AUTHENTICATED, not public. An earlier draft of this javadoc said "public because it is
     * marketing copy", and that was wrong twice over: bmp-rewards' public-paths list deliberately
     * contains nothing but actuator and swagger ("Nothing about rewards is public"), so the claim
     * did not match the code — and it did not need to, since only a signed-in person has a referral
     * code to share in the first place.
     */
    public record OfferResponse(long referrerRewardPaise, long refereeRewardPaise, boolean active) {}

    public record AttributeRequest(@NotBlank String code, @NotNull UUID refereeUserId) {}

    public record AttributeResponse(UUID referralId, boolean accepted, String note) {}

    @Operation(
        summary = "The current referral offer",
        description = "What each side gets. Signed-in only, like everything else in bmp-rewards.")
    @GetMapping("/offer")
    @PreAuthorize("isAuthenticated()")
    public OfferResponse offer() {
        var p = program.current();
        return new OfferResponse(p.referrerPayoutPaise(), p.refereePayoutPaise(), !p.isEffectivelyOff());
    }

    @Operation(
        summary = "My referral code",
        description = "Created on first request rather than at signup — most customers never share one, and generating millions of unused codes is work for its own sake.")
    @GetMapping("/my-code")
    @PreAuthorize("isAuthenticated()")
    public MyCodeResponse myCode(@AuthenticationPrincipal AuthenticatedUser caller) {
        if (caller == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "NO_PRINCIPAL");
        String code = referrals.codeFor(caller.userId());
        var offer = program.current();

        /*
         * The amount goes in the MESSAGE, built here rather than in the app.
         *
         * An admin can change the offer at any time; a share message with a hardcoded "₹100" in the
         * client would keep promising the old number until the next app release, and the person
         * being promised it is not our customer yet — they are somebody's friend, who will judge us
         * on whether the thing they were told turns out to be true.
         *
         * When the referee side is switched off the sentence simply omits it, rather than saying
         * "get ₹0".
         */
        String sweetener = offer.refereePayoutPaise() > 0
                ? " You'll get ₹" + (offer.refereePayoutPaise() / 100) + " off your first visit."
                : "";

        return new MyCodeResponse(code,
                "Join me on BMP — book salons with real availability. Use my code " + code
                        + " when you sign up." + sweetener);
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
