package com.bmp.rewards.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.rewards.dto.CouponRequestDtos.*;
import com.bmp.rewards.entities.CouponRequest;
import com.bmp.rewards.services.CouponRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The SALON OWNER's side of the coupon request flow.
 *
 * <p>Session 31. A salon owner cannot create coupons — a coupon may be funded out of BMP's
 * commission ({@code commission_base}), so "who pays for this?" is not the beneficiary's
 * question to answer. What they CAN do is ask, which is what this controller is.
 *
 * <p>Note this is a customer-side token ({@code ROLE_SALON_OWNER} from bmp-auth), not a staff
 * token — an owner uses the normal app, not the console. Staff go through bmp-admin instead;
 * see {@link CouponRequestInternalController}.
 *
 * <p>Everything is scoped to the caller's own identity, taken from the token and never from the
 * body. An owner can only raise for their own salon and can only see their own requests.
 */
@Tag(name = "Coupon requests (salon)", description = "A salon owner asking BMP to create a promotional coupon for their salon.")
@RestController
@RequestMapping("/api/v1/coupon-requests")
public class CouponRequestController {

    private final CouponRequestService service;

    public CouponRequestController(CouponRequestService service) {
        this.service = service;
    }

    @Operation(
        summary = "Ask BMP to create a coupon for your salon",
        description = """
            Raises a request for an admin to approve. You cannot create coupons directly, because \
            a discount can be funded from BMP's commission rather than by you — that decision is \
            made when the request is approved.

            The scope is forced to your own salon from your token; asking for a platform-wide \
            coupon is not possible. `justification` must be at least 20 characters: an admin \
            decides on this without you in the room.""")
    @PreAuthorize("hasRole('SALON_OWNER')")
    @PostMapping
    public ResponseEntity<CouponRequestResponse> raise(
            @Valid @RequestBody RaiseRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        RequesterContext who = new RequesterContext(
                CouponRequest.REQUESTER_SALON_OWNER,
                caller.userId(),
                null,                       // bmp-rewards doesn't hold names; bmp-admin enriches for display
                null,
                "salon_owner",
                caller.salonId());

        return ResponseEntity.status(HttpStatus.CREATED).body(service.raise(req, who));
    }

    @Operation(summary = "Your salon's coupon requests", description = "Everything you've asked for, newest first, with the code once approved.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    @GetMapping
    public List<CouponRequestResponse> mine(@AuthenticationPrincipal AuthenticatedUser caller) {
        // By SALON, not by requester: a manager should see what their owner asked for, and an
        // owner shouldn't lose their history because a co-owner raised it.
        return caller.salonId() == null ? List.of() : service.forSalon(caller.salonId());
    }

    @Operation(summary = "Withdraw a request you raised", description = "For when you've sorted it another way. Only your own, and only while it's still pending.")
    @PreAuthorize("hasRole('SALON_OWNER')")
    @PostMapping("/{requestId}/cancel")
    public CouponRequestResponse cancel(@PathVariable UUID requestId,
                                         @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.cancel(requestId, caller.userId());
    }
}
