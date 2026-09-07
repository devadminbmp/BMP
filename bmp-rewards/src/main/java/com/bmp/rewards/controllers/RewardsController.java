package com.bmp.rewards.controllers;

import com.bmp.rewards.dto.RewardsDtos.*;
import com.bmp.rewards.repositories.ReferralCodeRepository;
import com.bmp.rewards.services.RewardsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** BMP-28: coupon / wallet / referral_code endpoints. */
@Tag(name = "Rewards", description = "Coupon validation (6 locked rules, checked in order, first failure wins), append-only wallet, referral codes.")
@RestController
public class RewardsController {

    private final RewardsService service;
    private final ReferralCodeRepository referralCodes;

    public RewardsController(RewardsService service, ReferralCodeRepository referralCodes) {
        this.service = service;
        this.referralCodes = referralCodes;
    }

    /**
     * SERVICE-ONLY. Coupons are money, and this mints them.
     *
     * <p>Until the Session 29 audit this had no authorization at all: with bmp-rewards'
     * public-paths listing only actuator and swagger, it required <i>a</i> token — and
     * {@code JwtAuthFilter} accepts a CUSTOMER token. <b>Any signed-up customer could create
     * themselves a 100%-off coupon.</b>
     *
     * <p>Staff issue coupons through the console, which goes to bmp-admin's
     * {@code CouponController} and is subject to {@code CouponIssuePolicy} — the rules that stop
     * a support agent discounting the whole platform. This raw endpoint bypasses all of that,
     * so no human role may reach it.
     */
    @Operation(summary = "Create a coupon (internal)", description = "SERVICE role only. Staff use POST /api/v1/admin/coupons on bmp-admin, which applies the issuing policy.")
    @PreAuthorize("hasRole('SERVICE')")
    @PostMapping("/api/v1/coupons")
    public ResponseEntity<CouponResponse> createCoupon(@Valid @RequestBody CreateCouponRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCoupon(req));
    }

    /*
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * REMOVED IN SESSION 55: POST /api/v1/coupons/validate
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * There were TWO live implementations of "is this coupon valid for this basket", and they
     * disagreed. This endpoint ran RewardsService.validate — six rules, and NONE of:
     *
     *   · the audience check (selected_users / new_users / referred_users, V003). A coupon issued
     *     to three named people validated for everybody.
     *   · max_discount_paise. "20% up to ₹100" validated as an uncapped 20%.
     *   · the first-booking rule for welcome coupons, which was commented
     *     "skipped (assumed true) in this CRUD-first pass".
     *
     * POST /api/v1/coupons/quote (CouponRedemptionController) applies all of them, and is what the
     * app has always called. So the weaker one was reachable by any authenticated user and shipped
     * answers the real redemption would refuse — which is worse than no preview at all, because a
     * customer is shown a discount and then charged full price.
     *
     * The rule this restores: ONE QUESTION, ONE IMPLEMENTATION. Two of them is two answers, and
     * the one that quietly wins is whichever runs second.
     */

    /**
     * Your own wallet, or a service.
     *
     * <p>{@code principal.userId()} is the id from the caller's token; comparing it to the path
     * variable is what stops one customer reading another's balance. Previously any customer
     * token could read ANY user's wallet by changing the UUID in the URL — the classic IDOR,
     * and the reason ownership checks belong on the endpoint rather than in the client.
     */
    @Operation(summary = "Get a user's wallet balance", description = "Your own only, unless called by an internal service.")
    @PreAuthorize("hasRole('SERVICE') or principal.userId() == #userId")
    @GetMapping("/api/v1/users/{userId}/wallet")
    public WalletResponse getWallet(@PathVariable UUID userId) {
        return service.getWallet(userId);
    }

    /** Same ownership rule as the balance — the ledger is more revealing, not less. */
    @Operation(summary = "List a user's wallet transaction history", description = "Your own only. Paginated. wallet_transaction is append-only — this is a ledger, not an editable balance.")
    @PreAuthorize("hasRole('SERVICE') or principal.userId() == #userId")
    @GetMapping("/api/v1/users/{userId}/wallet/transactions")
    public PagedTransactions listTransactions(@PathVariable UUID userId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return service.listTransactions(userId, page, size);
    }

    /**
     * SERVICE-ONLY. This creates money out of nothing.
     *
     * <p>It was previously reachable by any authenticated customer, who could have credited
     * their own wallet by any amount with a single POST. The {@code /api/v1/admin/} path
     * prefix in the URL implied a protection that did not exist anywhere — <b>a path is not a
     * permission</b>, and naming one "admin" protects nothing on its own.
     */
    @Operation(summary = "[INTERNAL] Manually credit a wallet", description = "SERVICE role only. Creates a wallet_transaction directly — never reachable by an end user.")
    @PreAuthorize("hasRole('SERVICE')")
    @PostMapping("/api/v1/admin/wallet/credit")
    public WalletResponse adminCredit(@RequestParam UUID userId, @RequestParam long amountPaise,
                                       @RequestParam(defaultValue = "admin_credit") String type) {
        return service.adminCredit(userId, amountPaise, type);
    }

    /** Your own referral code, or a service creating one during signup. */
    @Operation(summary = "Get or create a user's referral code", description = "Your own only. Idempotent — 200 if one already existed, 201 if this call created it.")
    @PreAuthorize("hasRole('SERVICE') or principal.userId() == #userId")
    @PostMapping("/api/v1/users/{userId}/referral-code")
    public ResponseEntity<ReferralCodeResponse> referralCode(@PathVariable UUID userId) {
        boolean existed = referralCodes.findByUserId(userId).isPresent();
        ReferralCodeResponse resp = service.getOrCreateReferralCode(userId);
        return ResponseEntity.status(existed ? HttpStatus.OK : HttpStatus.CREATED).body(resp);
    }
}
