package com.bmp.rewards.services;

import com.bmp.rewards.repositories.CouponPolicyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What each kind of staff member may issue.
 *
 * <h2>The rule, in one sentence</h2>
 * Admin can create any coupon. Support can only create a coupon for <b>specific named users</b>,
 * only when it's settling a <b>raised issue</b>, and only within value and validity limits.
 *
 * <h2>Why the restriction is shaped this way</h2>
 * These are two genuinely different activities that happen to share a table.
 *
 * <p><b>Admin issues campaigns.</b> "20% off in Koramangala this Diwali" is a marketing
 * decision with a budget behind it, aimed at people who haven't complained about anything.
 *
 * <p><b>Support issues apologies.</b> "Your stylist didn't turn up, here's ₹300" is a
 * settlement, aimed at one person, in response to one incident. It should be quick — an agent
 * who needs a manager's approval to say sorry will instead say nothing, and the customer leaves.
 *
 * <p>Letting support create the first kind is the problem: an agent who can issue an all-users
 * coupon can, in one mistyped form, give the entire customer base 50% off. Not maliciously —
 * that's the point. The restriction removes the possibility rather than relying on care.
 *
 * <h2>The limits are configuration, not constants</h2>
 * Read from {@code rewards_schema.coupon_policy} so they can be raised on a bad morning without
 * a deploy, and so changing them is itself an audited action. The seeded values are deliberately
 * conservative and marked TODO — they are a starting point, not a business position.
 */
@Service
public class CouponIssuePolicy {

    public static final String AUDIENCE_ALL_USERS = "all_users";
    public static final String AUDIENCE_SELECTED_USERS = "selected_users";
    public static final String AUDIENCE_NEW_USERS = "new_users";
    public static final String AUDIENCE_REFERRED_USERS = "referred_users";

    public static final List<String> AUDIENCE_TYPES =
            List.of(AUDIENCE_ALL_USERS, AUDIENCE_SELECTED_USERS, AUDIENCE_NEW_USERS, AUDIENCE_REFERRED_USERS);

    public static final String SCOPE_ALL_SALONS = "all_salons";
    public static final String SCOPE_SELECTED_SALONS = "selected_salons";

    /** Roles that may issue anything. Matches bmp-admin's StaffPermission vocabulary. */
    private static final List<String> UNRESTRICTED_ROLES = List.of("super_admin", "ops_admin");
    /** Roles restricted to per-user goodwill. */
    private static final List<String> SUPPORT_ROLES = List.of("support_agent");

    private final CouponPolicyRepository policyRepo;

    public CouponIssuePolicy(CouponPolicyRepository policyRepo) {
        this.policyRepo = policyRepo;
    }

    /** A description of the limits, for the console to render before anyone fills a form in. */
    public record Limits(
        boolean unrestricted,
        long maxFlatPaise,
        int maxPercentBasisPoints,
        int maxValidityDays,
        int maxRecipients,
        boolean requiresTicket
    ) {}

    public Limits limitsFor(String role) {
        if (isUnrestricted(role)) {
            return new Limits(true, Long.MAX_VALUE, 10000, 3650, Integer.MAX_VALUE, false);
        }
        return new Limits(
                false,
                policyLong("support_max_flat_paise", 50_000L),
                (int) policyLong("support_max_percent_bps", 2000L),
                (int) policyLong("support_max_validity_days", 30L),
                (int) policyLong("support_max_recipients", 1L),
                true);
    }

    /**
     * Throws unless this role may issue this coupon. Called before anything is written.
     *
     * @param discountType flat | percent
     * @param value        paise if flat, basis points if percent
     */
    public void assertMayIssue(String role, String audienceType, String discountType, long value,
                                Long maxDiscountPaise, Instant activeFrom, Instant activeTo,
                                List<UUID> targetUserIds, UUID ticketId) {

        if (!AUDIENCE_TYPES.contains(audienceType)) {
            throw bad("UNKNOWN_AUDIENCE_TYPE: " + audienceType);
        }
        if (!activeTo.isAfter(activeFrom)) {
            throw bad("ACTIVE_WINDOW_INVALID: the end must be after the start");
        }
        // A percentage with no ceiling is how an ₹8,000 bridal package becomes free.
        if ("percent".equalsIgnoreCase(discountType) && (maxDiscountPaise == null || maxDiscountPaise <= 0)) {
            throw bad("MAX_DISCOUNT_REQUIRED: a percentage coupon must have a cap");
        }
        if (audienceType.equals(AUDIENCE_SELECTED_USERS) && (targetUserIds == null || targetUserIds.isEmpty())) {
            throw bad("NO_RECIPIENTS: a 'selected users' coupon needs at least one user");
        }

        if (isUnrestricted(role)) {
            return;
        }
        if (!isSupport(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role cannot issue coupons.");
        }

        Limits limits = limitsFor(role);

        // The core restriction. Everything else here is a guard rail; this is the wall.
        if (!AUDIENCE_SELECTED_USERS.equals(audienceType)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Support can only issue coupons to specific customers. Campaign coupons "
                    + "(all users, new users, referred users) are created by an admin.");
        }
        if (ticketId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A support-issued coupon must be linked to the ticket it settles.");
        }
        if (targetUserIds.size() > limits.maxRecipients()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Support coupons may target at most %d customer(s). Ask an admin for anything wider."
                            .formatted(limits.maxRecipients()));
        }
        if ("flat".equalsIgnoreCase(discountType) && value > limits.maxFlatPaise()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "That's above the support limit of ₹%d. An admin can issue more."
                            .formatted(limits.maxFlatPaise() / 100));
        }
        if ("percent".equalsIgnoreCase(discountType) && value > limits.maxPercentBasisPoints()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "That's above the support limit of %d%%. An admin can issue more."
                            .formatted(limits.maxPercentBasisPoints() / 100));
        }
        // Goodwill should be used soon or not at all — an open-ended apology coupon is a
        // liability sitting on the books indefinitely.
        long days = Duration.between(activeFrom, activeTo).toDays();
        if (days > limits.maxValidityDays()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Support coupons can last at most %d days.".formatted(limits.maxValidityDays()));
        }
    }

    public boolean isUnrestricted(String role) {
        return role != null && UNRESTRICTED_ROLES.contains(role.toLowerCase());
    }

    public boolean isSupport(String role) {
        return role != null && SUPPORT_ROLES.contains(role.toLowerCase());
    }

    private long policyLong(String key, long fallback) {
        return policyRepo.findByPolicyKey(key)
                .map(p -> {
                    try {
                        return Long.parseLong(p.getPolicyValue());
                    } catch (NumberFormatException e) {
                        // A corrupted policy row must not become an unlimited allowance.
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
