package com.bmp.rewards.controllers;

import com.bmp.rewards.dto.RewardsDtos.*;
import com.bmp.rewards.repositories.ReferralCodeRepository;
import com.bmp.rewards.services.RewardsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @Operation(summary = "Create a coupon")
    @PostMapping("/api/v1/coupons")
    public ResponseEntity<CouponResponse> createCoupon(@Valid @RequestBody CreateCouponRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCoupon(req));
    }

    @Operation(summary = "Validate a coupon against an in-progress order", description = "Runs all 6 locked validation rules in order; returns the FIRST failure, not all of them.")
    @PostMapping("/api/v1/coupons/validate")
    public ValidateCouponResponse validate(@Valid @RequestBody ValidateCouponRequest req) {
        return service.validate(req);
    }

    @Operation(summary = "Get a user's wallet balance")
    @GetMapping("/api/v1/users/{userId}/wallet")
    public WalletResponse getWallet(@PathVariable UUID userId) {
        return service.getWallet(userId);
    }

    @Operation(summary = "List a user's wallet transaction history", description = "Paginated. wallet_transaction is append-only — this is a ledger, not an editable balance.")
    @GetMapping("/api/v1/users/{userId}/wallet/transactions")
    public PagedTransactions listTransactions(@PathVariable UUID userId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return service.listTransactions(userId, page, size);
    }

    @Operation(
        summary = "[ADMIN/DEV ONLY] Manually credit a wallet",
        description = "The only way to create a wallet_transaction directly via the API — not a customer-facing endpoint.")
    @PostMapping("/api/v1/admin/wallet/credit")
    public WalletResponse adminCredit(@RequestParam UUID userId, @RequestParam long amountPaise,
                                       @RequestParam(defaultValue = "admin_credit") String type) {
        return service.adminCredit(userId, amountPaise, type);
    }

    @Operation(summary = "Get or create a user's referral code", description = "Idempotent — returns 200 if one already existed, 201 if this call just created it.")
    @PostMapping("/api/v1/users/{userId}/referral-code")
    public ResponseEntity<ReferralCodeResponse> referralCode(@PathVariable UUID userId) {
        boolean existed = referralCodes.findByUserId(userId).isPresent();
        ReferralCodeResponse resp = service.getOrCreateReferralCode(userId);
        return ResponseEntity.status(existed ? HttpStatus.OK : HttpStatus.CREATED).body(resp);
    }
}
