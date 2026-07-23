package com.bmp.rewards.services;

import com.bmp.common.money.Money;
import com.bmp.rewards.dto.RewardsDtos.*;
import com.bmp.rewards.entities.Coupon;
import com.bmp.rewards.entities.ReferralCode;
import com.bmp.rewards.entities.Wallet;
import com.bmp.rewards.entities.WalletTransaction;
import com.bmp.rewards.repositories.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** BMP-28: coupon / wallet / wallet_transaction (append-only) / referral_code. */
@Service
public class RewardsService {

    private final CouponRepository coupons;
    private final CouponUsageRepository couponUsages;
    private final WalletRepository wallets;
    private final WalletTransactionRepository walletTx;
    private final ReferralCodeRepository referralCodes;

    public RewardsService(CouponRepository coupons, CouponUsageRepository couponUsages, WalletRepository wallets,
                           WalletTransactionRepository walletTx, ReferralCodeRepository referralCodes) {
        this.coupons = coupons;
        this.couponUsages = couponUsages;
        this.wallets = wallets;
        this.walletTx = walletTx;
        this.referralCodes = referralCodes;
    }

    @Transactional
    public CouponResponse createCoupon(CreateCouponRequest req) {
        // salon_specific coupons ALWAYS pre_discount — server-enforced, never client-trusted.
        String commissionBase = "salon_specific".equals(req.type()) ? "pre_discount" : req.commissionBase();
        Coupon c = new Coupon(req.code(), req.type(), req.salonId(), commissionBase, req.discountType(),
                req.value(), Money.ofPaise(req.minSpendPaise()), req.perUserLimit(), req.totalUsageCap(),
                req.activeFrom(), req.activeTo(), req.allowsWalletStacking());
        c = coupons.save(c);
        return toResponse(c);
    }

    /** Runs the 6 locked validation rules IN ORDER, returns the FIRST failing reason. */
    public ValidateCouponResponse validate(ValidateCouponRequest req) {
        Coupon c = coupons.findByCode(req.code()).orElse(null);
        if (c == null) {
            return new ValidateCouponResponse(false, null, null, null, "INACTIVE_OR_EXPIRED");
        }
        Instant now = Instant.now();
        // Rule 1: active window
        if (now.isBefore(c.getActiveFrom()) || now.isAfter(c.getActiveTo())) {
            return new ValidateCouponResponse(false, c.getId(), null, null, "INACTIVE_OR_EXPIRED");
        }
        // Rule 2: salon match (null salon_id = platform-wide, always matches)
        if (c.getSalonId() != null && req.salonId() != null && !c.getSalonId().equals(req.salonId())) {
            return new ValidateCouponResponse(false, c.getId(), null, null, "SALON_MISMATCH");
        }
        // Rule 3: per-user limit
        long usedByUser = couponUsages.findByCouponIdAndUserId(c.getId(), req.userId()).size();
        if (c.getPerUserLimit() > 0 && usedByUser >= c.getPerUserLimit()) {
            return new ValidateCouponResponse(false, c.getId(), null, null, "PER_USER_LIMIT_EXCEEDED");
        }
        // Rule 4: minimum spend
        if (req.subtotalPaise() < c.getMinSpendPaise().paise()) {
            return new ValidateCouponResponse(false, c.getId(), null, null, "MIN_SPEND_NOT_MET");
        }
        // Rule 5: total usage cap
        if (c.getTotalUsageCap() > 0 && couponUsages.countByCouponId(c.getId()) >= c.getTotalUsageCap()) {
            return new ValidateCouponResponse(false, c.getId(), null, null, "TOTAL_CAP_EXCEEDED");
        }
        // Rule 6: welcome coupons require this to be the user's first booking.
        // TODO(Phase 3 / inter-service): verify via a Feign call to bmp-booking-service.
        // Skipped (assumed true) in this CRUD-first pass per the team's phased build order.

        Money discount = "percent".equals(c.getDiscountType())
                ? Money.ofPaise(req.subtotalPaise()).percentBps((int) (c.getValue() * 100))
                : Money.ofPaise(c.getValue());
        return new ValidateCouponResponse(true, c.getId(), discount.paise(), c.getCommissionBase(), null);
    }

    public WalletResponse getWallet(UUID userId) {
        Wallet w = wallets.findByUserId(userId).orElseGet(() -> wallets.save(new Wallet(userId, Money.ZERO, false)));
        return new WalletResponse(w.getUserId(), w.getBalancePaise().paise(), w.isFrozen());
    }

    public PagedTransactions listTransactions(UUID userId, int page, int size) {
        Wallet w = wallets.findByUserId(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND"));
        Page<WalletTransaction> p = walletTx.findByWalletIdOrderByCreatedAtDesc(w.getId(), PageRequest.of(page, size));
        List<WalletTransactionResponse> content = p.getContent().stream()
                .map(t -> new WalletTransactionResponse(t.getId(), t.getTransactionType(), t.getAmountPaise().paise(),
                        t.getBalanceAfterPaise().paise(), t.getCreatedAt()))
                .toList();
        return new PagedTransactions(content, page, size, p.getTotalElements());
    }

    /** ADMIN-ONLY, dev/testing credit. wallet_transaction is otherwise append-only and never directly POSTed by a client. */
    @Transactional
    public WalletResponse adminCredit(UUID userId, long amountPaise, String type) {
        Wallet w = wallets.findByUserId(userId).orElseGet(() -> wallets.save(new Wallet(userId, Money.ZERO, false)));
        Money newBalance = w.getBalancePaise().plus(Money.ofPaise(amountPaise));
        Money.requireNonNegative(newBalance, "balancePaise");
        w.setBalancePaise(newBalance);
        w.touch();
        walletTx.save(new WalletTransaction(w.getId(), type, Money.ofPaise(amountPaise), newBalance));
        return new WalletResponse(w.getUserId(), newBalance.paise(), w.isFrozen());
    }

    @Transactional
    public ReferralCodeResponse getOrCreateReferralCode(UUID userId) {
        ReferralCode rc = referralCodes.findByUserId(userId).orElse(null);
        if (rc != null) return new ReferralCodeResponse(rc.getUserId(), rc.getCode(), rc.getCreatedAt());
        String code = "BMP-" + userId.toString().substring(0, 6).toUpperCase();
        rc = referralCodes.save(new ReferralCode(userId, code));
        return new ReferralCodeResponse(rc.getUserId(), rc.getCode(), rc.getCreatedAt());
    }

    private CouponResponse toResponse(Coupon c) {
        return new CouponResponse(c.getId(), c.getCode(), c.getCouponType(), c.getSalonId(), c.getCommissionBase(),
                c.getDiscountType(), c.getValue(), c.getMinSpendPaise().paise(), c.getPerUserLimit(),
                c.getTotalUsageCap(), c.getActiveFrom(), c.getActiveTo(), c.isAllowsWalletStacking(), c.getCreatedAt());
    }
}
