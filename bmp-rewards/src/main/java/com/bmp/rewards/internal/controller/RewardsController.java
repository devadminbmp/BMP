package com.bmp.rewards.internal.controller;

import com.bmp.rewards.internal.dto.RewardsDtos.*;
import com.bmp.rewards.internal.repository.ReferralCodeRepository;
import com.bmp.rewards.internal.service.RewardsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** BMP-28: coupon / wallet / referral_code endpoints. */
@RestController
public class RewardsController {

    private final RewardsService service;
    private final ReferralCodeRepository referralCodes;

    public RewardsController(RewardsService service, ReferralCodeRepository referralCodes) {
        this.service = service;
        this.referralCodes = referralCodes;
    }

    @PostMapping("/api/v1/coupons")
    public ResponseEntity<CouponResponse> createCoupon(@Valid @RequestBody CreateCouponRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCoupon(req));
    }

    @PostMapping("/api/v1/coupons/validate")
    public ValidateCouponResponse validate(@Valid @RequestBody ValidateCouponRequest req) {
        return service.validate(req);
    }

    @GetMapping("/api/v1/users/{userId}/wallet")
    public WalletResponse getWallet(@PathVariable UUID userId) {
        return service.getWallet(userId);
    }

    @GetMapping("/api/v1/users/{userId}/wallet/transactions")
    public PagedTransactions listTransactions(@PathVariable UUID userId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return service.listTransactions(userId, page, size);
    }

    /** ADMIN-ONLY, dev/testing credit — the only way to create a wallet_transaction directly via the API. */
    @PostMapping("/api/v1/admin/wallet/credit")
    public WalletResponse adminCredit(@RequestParam UUID userId, @RequestParam long amountPaise,
                                       @RequestParam(defaultValue = "admin_credit") String type) {
        return service.adminCredit(userId, amountPaise, type);
    }

    @PostMapping("/api/v1/users/{userId}/referral-code")
    public ResponseEntity<ReferralCodeResponse> referralCode(@PathVariable UUID userId) {
        boolean existed = referralCodes.findByUserId(userId).isPresent();
        ReferralCodeResponse resp = service.getOrCreateReferralCode(userId);
        return ResponseEntity.status(existed ? HttpStatus.OK : HttpStatus.CREATED).body(resp);
    }
}
