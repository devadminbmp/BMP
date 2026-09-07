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

    /*
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * REMOVED IN SESSION 55: validate(ValidateCouponRequest)
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * The second, weaker implementation of "is this coupon valid for this basket". It applied six
     * rules and none of the audience targeting (V003), the max_discount cap, or the welcome
     * coupon's first-booking requirement — that last one carried the comment "skipped (assumed
     * true) in this CRUD-first pass", which meant a first-booking-only code validated for anybody,
     * every time.
     *
     * CouponRedemptionService.quote is the single implementation now. It is what the app has
     * always called, and it applies every rule the real redemption applies — which is the point:
     * a preview that is more permissive than the redemption shows a customer a discount and then
     * charges them full price.
     */


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

    /**
     * The one place money enters a wallet.
     *
     * <p>Session 64: the name and the old comment ("ADMIN-ONLY, dev/testing credit") are no longer
     * the whole truth — ReferralService now calls this to settle a referral, which is a real,
     * customer-facing credit rather than a testing affordance. The MECHANISM was always general;
     * only the caller list was narrow.
     *
     * <p>Left named {@code adminCredit} deliberately rather than renamed: it is referenced by an
     * admin endpoint whose route is public API, and a rename would either break that or leave a
     * misleading alias. The javadoc is the honest fix.
     *
     * <p>{@code wallet_transaction} stays append-only and is never POSTed directly by a client —
     * every balance change goes through here so that the transaction row and the new balance
     * snapshot cannot disagree.
     *
     * @param type a wallet_transaction type, e.g. 'referral_bonus', 'goodwill', 'admin_credit'.
     *             Not free text in practice — the ledger is read by finance, and a typo becomes a
     *             category nobody can total.
     */
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
