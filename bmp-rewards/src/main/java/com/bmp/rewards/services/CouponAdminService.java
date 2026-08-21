package com.bmp.rewards.services;

import com.bmp.common.money.Money;
import com.bmp.rewards.dto.CouponAdminDtos.*;
import com.bmp.rewards.entities.Coupon;
import com.bmp.rewards.entities.CouponSalon;
import com.bmp.rewards.entities.CouponUser;
import com.bmp.rewards.repositories.CouponRepository;
import com.bmp.rewards.repositories.CouponSalonRepository;
import com.bmp.rewards.repositories.CouponUsageRepository;
import com.bmp.rewards.repositories.CouponUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Issuing and managing coupons on behalf of staff.
 *
 * <p>Called by bmp-admin over the internal service credential — the console never talks to this
 * service directly, so every coupon issued passes through one place where the audit entry is
 * written. The staff member's identity and role arrive as parameters rather than being read
 * from a token, because this service has no notion of staff sessions and shouldn't gain one.
 *
 * <p>The admin/support distinction lives entirely in {@link CouponIssuePolicy} — read it first,
 * it explains the reasoning. This class does the writing.
 */
@Service
public class CouponAdminService {

    private static final Logger log = LoggerFactory.getLogger(CouponAdminService.class);

    private final CouponRepository coupons;
    private final CouponUserRepository couponUsers;
    private final CouponSalonRepository couponSalons;
    private final CouponUsageRepository usage;
    private final CouponIssuePolicy policy;
    private final SecureRandom random = new SecureRandom();

    private final CouponAllowanceService allowance;

    public CouponAdminService(CouponRepository coupons, CouponUserRepository couponUsers,
                              CouponSalonRepository couponSalons, CouponUsageRepository usage,
                              CouponIssuePolicy policy, CouponAllowanceService allowance) {
        this.coupons = coupons;
        this.couponUsers = couponUsers;
        this.couponSalons = couponSalons;
        this.usage = usage;
        this.policy = policy;
        this.allowance = allowance;
    }

    /** What this role may issue — the console renders its form from this. */
    public CouponIssuePolicy.Limits limitsFor(String role) {
        return policy.limitsFor(role);
    }

    @Transactional
    public CouponResponse issue(CreateCouponRequest req, StaffContext staff) {
        // Authorization first, before anything is generated or written.
        policy.assertMayIssue(
                staff.role(), req.audienceType(), req.discountType(), req.value(),
                req.maxDiscountPaise(), req.activeFrom(), req.activeTo(),
                req.targetUserIds(), req.ticketId());

        /*
         * Session 31 — THE ROLLING ALLOWANCE, checked after the per-coupon limits.
         *
         * assertMayIssue answers "is this ONE coupon within policy?". It cannot answer "is this
         * the eleventh one this week?" — so an agent could previously issue a hundred compliant
         * ₹500 coupons in an afternoon and nobody would know until the month's numbers landed.
         *
         * Admins are exempt: an allowance is a delegation limit, and there is nobody above an
         * admin to escalate to. Giving them one would just mean a limit that has to be raised
         * by the person it constrains, which is not a control.
         *
         * The message names the escape hatch rather than just refusing. This is the whole
         * design: a wall with no gate doesn't enforce a policy, it makes people borrow a login.
         */
        if (!policy.isUnrestricted(staff.role())) {
            String over = allowance.wouldExceed(
                    staff.staffId(), req.discountType(), req.value(), req.maxDiscountPaise());
            if (over != null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ALLOWANCE_EXCEEDED: " + over);
            }
        }

        String code = req.code() == null || req.code().isBlank()
                ? generateCode(req.audienceType())
                : req.code().trim().toUpperCase();

        if (coupons.findByCode(code).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "COUPON_CODE_TAKEN: " + code);
        }

        // commission_base is the funding decision, and it is NOT staff's to choose casually:
        // a salon-specific coupon is always pre_discount (the salon funds it) per V002's own
        // column comment, and a platform campaign is post_discount (BMP funds it). Deriving it
        // here rather than accepting it from the form stops a support agent accidentally
        // charging a salon for BMP's apology.
        boolean salonScoped = CouponIssuePolicy.SCOPE_SELECTED_SALONS.equals(req.salonScope());
        String commissionBase = salonScoped && Boolean.TRUE.equals(req.salonFunded())
                ? "pre_discount"
                : "post_discount";

        Coupon coupon = coupons.save(new Coupon(
                code, req.name(), req.description(), req.couponType(),
                // The legacy single-salon column stays null; the list lives in coupon_salon.
                null,
                commissionBase, req.discountType(), req.value(), req.maxDiscountPaise(),
                Money.ofPaise(req.minSpendPaise() == null ? 0L : req.minSpendPaise()),
                req.perUserLimit() == null ? 1 : req.perUserLimit(),
                req.totalUsageCap() == null ? 0 : req.totalUsageCap(),
                req.activeFrom(), req.activeTo(),
                Boolean.TRUE.equals(req.allowsWalletStacking()),
                req.audienceType(), req.salonScope(), "active",
                staff.staffId(), staff.email(), staff.role(),
                req.ticketId(), req.issueReason()));

        if (CouponIssuePolicy.AUDIENCE_SELECTED_USERS.equals(req.audienceType())) {
            req.targetUserIds().forEach(userId -> couponUsers.save(new CouponUser(coupon.getId(), userId)));
        }
        if (salonScoped && req.targetSalonIds() != null) {
            req.targetSalonIds().forEach(salonId -> couponSalons.save(new CouponSalon(coupon.getId(), salonId)));
        }

        log.info("Coupon issued: code={} audience={} by={} ({}) ticket={}",
                code, req.audienceType(), staff.email(), staff.role(), req.ticketId());

        return toResponse(coupon);
    }

    public List<CouponResponse> list(String status) {
        return coupons.findAll().stream()
                .filter(c -> status == null || status.equalsIgnoreCase(c.getStatus()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toResponse)
                .toList();
    }

    /**
     * Pause or resume.
     *
     * <p>Support can only touch coupons THEY issued — an agent stopping a live marketing
     * campaign would be a surprising amount of damage from one misclick.
     */
    @Transactional
    public CouponResponse setStatus(UUID couponId, String status, StaffContext staff) {
        Coupon coupon = coupons.findById(couponId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "COUPON_NOT_FOUND"));

        if (!policy.isUnrestricted(staff.role())
                && !staff.staffId().equals(coupon.getCreatedByStaffId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only change coupons you issued yourself.");
        }
        if (!List.of("active", "paused", "revoked").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_STATUS: " + status);
        }

        coupon.setStatus(status);
        log.info("Coupon {} set to {} by {}", coupon.getCode(), status, staff.email());
        return toResponse(coupon);
    }

    /**
     * A readable, typeable code.
     *
     * <p>No 0/O or 1/I/l — these get read out over the phone by an agent settling a complaint,
     * and a code the customer can't type is worse than no coupon at all.
     */
    private String generateCode(String audienceType) {
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        String prefix = switch (audienceType) {
            case CouponIssuePolicy.AUDIENCE_NEW_USERS -> "WELCOME";
            case CouponIssuePolicy.AUDIENCE_REFERRED_USERS -> "REFER";
            case CouponIssuePolicy.AUDIENCE_SELECTED_USERS -> "SORRY";
            default -> "BMP";
        };
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < 5; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private CouponResponse toResponse(Coupon c) {
        return new CouponResponse(
                c.getId(), c.getCode(), c.getName(), c.getDescription(), c.getCouponType(),
                c.getAudienceType(), c.getSalonScope(), c.getStatus(),
                c.getDiscountType(), c.getValue(), c.getMaxDiscountPaise(),
                c.getMinSpendPaise() == null ? 0L : c.getMinSpendPaise().paise(),
                c.getPerUserLimit(), c.getTotalUsageCap(),
                c.getActiveFrom(), c.getActiveTo(),
                c.getCommissionBase(),
                couponUsers.findByCouponId(c.getId()).stream().map(CouponUser::getUserId).toList(),
                couponSalons.findByCouponId(c.getId()).stream().map(CouponSalon::getSalonId).toList(),
                c.getCreatedByEmail(), c.getCreatedByRole(), c.getIssuedForTicketId(), c.getIssueReason(),
                usage.countByCouponId(c.getId()),
                c.getCreatedAt());
    }
}
