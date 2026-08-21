package com.bmp.rewards.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.rewards.dto.CouponRedemptionDtos.*;
import com.bmp.rewards.services.CouponRedemptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Applying a coupon at checkout.
 *
 * <p>Two audiences, two endpoints, one set of rules underneath:
 *
 * <ul>
 *   <li><b>{@code /quote}</b> — the customer, typing a code into the booking screen. Read-only,
 *       and returns a sentence when it refuses.</li>
 *   <li><b>{@code /internal/redeem}</b> — bmp-booking, inside the booking transaction. Writes
 *       the usage row and returns the commission basis to snapshot.</li>
 * </ul>
 *
 * <p>They share every check. If quote and redeem could disagree, a customer would be shown
 * "₹300 off" and then charged full price.
 */
@Tag(name = "Coupon redemption", description = "Check a code at checkout, and apply it when the booking is created.")
@RestController
@RequestMapping("/api/v1/coupons")
public class CouponRedemptionController {

    private final CouponRedemptionService redemption;

    public CouponRedemptionController(CouponRedemptionService redemption) {
        this.redemption = redemption;
    }

    @Operation(
        summary = "What is this code worth?",
        description = """
            Read-only. Returns the discount, or a refusal with a REASON the customer can act on \
            — "this code needs a minimum spend of ₹1,000" rather than "invalid coupon", because \
            the first is fixable and the second is a support ticket.

            The discount is computed server-side. The client sends a code and a basket, never an \
            amount.""")
    @PostMapping("/quote")
    @PreAuthorize("isAuthenticated()")
    public CouponQuoteResponse quote(@Valid @RequestBody CouponQuoteRequest req,
                                      @AuthenticationPrincipal AuthenticatedUser caller) {
        // A customer may only quote against their OWN account. Otherwise this endpoint becomes
        // a way to discover which coupons someone else holds — and support-issued goodwill
        // coupons are tied to specific people, so that's a real leak.
        requireSelf(req.userId(), caller);
        return redemption.quote(req);
    }

    @Operation(
        summary = "[internal] Apply a coupon to a booking",
        description = """
            Called by bmp-booking inside the booking transaction. Re-runs every check — a code \
            valid when the customer typed it may not be valid by the time they confirm, if the \
            last use went to someone else in between.

            Idempotent per booking: a retry returns the original discount rather than consuming \
            a second use. Locks the coupon row, so two customers cannot both claim the last one.

            Returns `commissionBase` so the booking can snapshot the right basis. Recalculating \
            it later — after a coupon has been paused or edited — would give a different answer \
            than the one the salon agreed to.""")
    @PostMapping("/internal/redeem")
    @PreAuthorize("hasRole('SERVICE')")
    public CouponRedeemResponse redeem(@Valid @RequestBody CouponRedeemRequest req) {
        return redemption.redeem(req);
    }

    @Operation(
        summary = "[internal] Release a coupon when a booking is cancelled",
        description = """
            The customer shouldn't lose a one-per-person coupon because a salon closed \
            unexpectedly. Marks the usage refunded rather than deleting it, so the history \
            still shows the code was applied and then released.

            ⚠️ Nothing calls this yet — bmp-booking's cancel path doesn't know about coupons. \
            Until it does, a cancelled booking silently burns the customer's coupon.""")
    @PostMapping("/internal/release/{bookingId}")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<Void> release(@PathVariable UUID bookingId,
                                         @RequestParam(required = false) String reason) {
        redemption.release(bookingId, reason == null ? "booking cancelled" : reason);
        return ResponseEntity.noContent().build();
    }

    private void requireSelf(UUID userId, AuthenticatedUser caller) {
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "NO_PRINCIPAL");
        }
        if (!"service".equalsIgnoreCase(caller.role()) && !caller.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_YOUR_ACCOUNT");
        }
    }
}
