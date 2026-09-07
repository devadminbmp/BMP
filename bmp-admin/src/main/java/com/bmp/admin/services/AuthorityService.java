package com.bmp.admin.services;

import com.bmp.admin.entities.AuthorityLimit;
import com.bmp.admin.repositories.AuthorityLimitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * "May I do this, at this amount?" — the single answer, for every gated action. Session 58.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * ONE MECHANISM, NOT ONE PER FEATURE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Darshan: <i>"support can give discount and cash coupons up to a certain level; if not they pass
 * to a higher person. If a refund is required they pass to ops/finance. Even if ops admin can't,
 * they pass to admin. I've given the example only for discount coupons — <b>next it can be
 * anything</b>."</i>
 *
 * <p>That last clause is the design. A bespoke ladder for coupons, then another for refunds, then
 * another for fee waivers gives three subtly different escalation rules that drift apart — and the
 * fourth feature gets none at all, because by then nobody remembers there was a pattern.
 *
 * <p>So every gated action asks this one method, and the rules live in {@code authority_limit} as
 * DATA. Adding "waive a no-show fee" is an INSERT, reviewed by whoever reviews data changes,
 * not a code path reviewed by whoever happens to be free.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THE APPROVER IS A ROLE, NOT "ONE TIER UP"
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * The tempting model is tier + 1. It is wrong, and Darshan's own example is the counter-example:
 * refunds go to FINANCE, which sits at tier 0 and is off the support ladder entirely (V009).
 *
 * <p>Money is a different axis from seniority. A refund does not become approvable by being handed
 * to a support lead — it needs somebody who owns the money. So the path is named explicitly per
 * action, and coupons climbing the support ladder while refunds jump sideways to finance are both
 * just rows.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * UNCONFIGURED MEANS NO
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * A role with no row for an action is {@code FORBIDDEN}, not "unlimited". This is the whole reason
 * the check exists: a new action added without a matrix row must be impossible for everyone below
 * the owner, rather than accidentally available to the entire desk.
 *
 * <p>And {@code max_value_paise = 0} is a REAL, distinct answer — "may request, may never perform".
 * Support has it for refunds. Without that distinction, "cannot approve" and "not configured"
 * would look identical, and the safe reading of the second is not the useful reading of the first.
 */
@Service
public class AuthorityService {

    private static final Logger log = LoggerFactory.getLogger(AuthorityService.class);

    /** What the caller is told, and what the UI renders a button from. */
    public enum Verdict {
        /** Go ahead — within this role's own ceiling. */
        ALLOWED,
        /** Not at this amount, but somebody above can clear it. Raise an approval request. */
        NEEDS_APPROVAL,
        /** Nobody can, or this role has no business here at all. Do not offer a request. */
        FORBIDDEN
    }

    /**
     * @param approverRole who to ask. Null unless the verdict is {@code NEEDS_APPROVAL}.
     * @param ceilingPaise this role's own limit, so the UI can say "you can approve up to ₹500"
     *                     BEFORE somebody types a number and gets refused.
     * @param reason       written for the person reading it, not for a log.
     */
    public record Decision(
            Verdict verdict,
            String approverRole,
            Long ceilingPaise,
            boolean requiresTicket,
            String reason) {

        public boolean allowed() { return verdict == Verdict.ALLOWED; }
        public boolean needsApproval() { return verdict == Verdict.NEEDS_APPROVAL; }
    }

    private final AuthorityLimitRepository limits;

    public AuthorityService(AuthorityLimitRepository limits) {
        this.limits = limits;
    }

    /**
     * The one question.
     *
     * @param actionType a stable code — {@code coupon.issue}, {@code refund.issue}. Never a
     *                   display string: it is stored on approval rows that outlive any rewording.
     * @param role       the caller's role, from their token. Never from the request body.
     * @param valuePaise the money at stake. Pass 0 for actions with no amount (a suspension); the
     *                   0-vs-NULL ceiling distinction still does the right thing.
     */
    @Transactional(readOnly = true)
    public Decision check(String actionType, String role, long valuePaise) {
        Optional<AuthorityLimit> found = limits.findByActionTypeAndRoleAndActiveTrue(actionType, role);

        if (found.isEmpty()) {
            /*
             * No row = no authority. Logged at INFO rather than swallowed: the commonest cause is
             * a NEW action_type that nobody added rows for, and the symptom — everybody refused —
             * is otherwise indistinguishable from a permissions bug.
             */
            log.info("No authority row for action={} role={} — refusing. If this action is new, "
                    + "seed admin_schema.authority_limit for every role that should have it.",
                    actionType, role);
            return new Decision(Verdict.FORBIDDEN, null, 0L, false,
                    "Your role can't do this. If you think it should, ask an administrator.");
        }

        AuthorityLimit limit = found.get();
        Long ceiling = limit.getMaxValuePaise();

        // NULL = unbounded. The platform owner, and nobody else in the seeded matrix.
        if (ceiling == null) {
            return new Decision(Verdict.ALLOWED, null, null, limit.isRequiresTicket(),
                    "No limit on your role for this.");
        }

        /*
         * Zero is "may request, may never perform" — support and refunds. Checked BEFORE the
         * comparison below, because `0 <= 0` would otherwise read as "allowed at zero rupees",
         * which is true and useless: a zero-value refund is not the case anybody is asking about.
         */
        if (ceiling == 0L) {
            if (limit.getApproverRole() == null) {
                return new Decision(Verdict.FORBIDDEN, null, 0L, limit.isRequiresTicket(),
                        "This isn't something your role can do, and there's no one to escalate to.");
            }
            return new Decision(Verdict.NEEDS_APPROVAL, limit.getApproverRole(), 0L,
                    limit.isRequiresTicket(),
                    "This needs " + humanRole(limit.getApproverRole()) + " to approve it.");
        }

        if (valuePaise <= ceiling) {
            return new Decision(Verdict.ALLOWED, null, ceiling, limit.isRequiresTicket(),
                    "Within your limit of " + rupees(ceiling) + ".");
        }

        if (limit.getApproverRole() == null) {
            return new Decision(Verdict.FORBIDDEN, null, ceiling, limit.isRequiresTicket(),
                    "That's above the maximum for this action (" + rupees(ceiling) + ") and there "
                    + "is nobody above you to approve it.");
        }

        return new Decision(Verdict.NEEDS_APPROVAL, limit.getApproverRole(), ceiling,
                limit.isRequiresTicket(),
                "Above your limit of " + rupees(ceiling) + " — "
                + humanRole(limit.getApproverRole()) + " can approve this.");
    }

    /**
     * The next approver above a role that has just declined to clear something.
     *
     * <p>Used when an approver says "not me either". The request moves up rather than bouncing
     * back to the person who raised it — bouncing back is how a customer waits two days for
     * something nobody ever intended to refuse.
     *
     * @return empty at the top of the path; the caller must then reject rather than re-queue.
     */
    @Transactional(readOnly = true)
    public Optional<String> nextApproverAbove(String actionType, String role) {
        return limits.findByActionTypeAndRoleAndActiveTrue(actionType, role)
                .map(AuthorityLimit::getApproverRole);
    }

    /** The whole path for one action, for the settings screen and for API_ACCESS docs. */
    @Transactional(readOnly = true)
    public List<AuthorityLimit> pathFor(String actionType) {
        return limits.findByActionTypeAndActiveTrueOrderByStepOrderAsc(actionType);
    }

    /** Everything a role may do, so the console can show a staff member their own authority. */
    @Transactional(readOnly = true)
    public List<AuthorityLimit> limitsForRole(String role) {
        return limits.findByRoleAndActiveTrueOrderByActionTypeAsc(role);
    }

    /**
     * Change a band. Session 59 — Darshan: <i>"all ranges can be fixed by admin, owner of BMP."</i>
     *
     * <h2>Only the owner, and why not ops</h2>
     * An ops admin who could raise their own ceiling has no ceiling. The whole matrix would become
     * advisory the moment somebody senior enough to be inconvenienced by it could edit it — which
     * is the same reason a salon owner cannot set their own commission (Session 45).
     *
     * <h2>A band can never exceed the one above it</h2>
     * Letting a lead's limit rise above ops' would mean escalating something makes it LESS likely
     * to be approved, and the ladder stops meaning anything. Refused with the number, so the fix is
     * obvious: raise the tier above first.
     *
     * <p>Every change is audited by the caller — this method returns the saved row and the
     * controller records who moved what.
     */
    @Transactional
    public AuthorityLimit updateLimit(String actionType, String role, Long newCeilingPaise,
                                       UUID byStaffId) {
        AuthorityLimit limit = limits.findByActionTypeAndRoleAndActiveTrue(actionType, role)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "There's no band configured for " + role + " on " + actionType + "."));

        if (newCeilingPaise != null && newCeilingPaise < 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "A limit can't be negative. Use 0 for \"may request but never do it alone\".");
        }

        /*
         * The band above must still be higher. Null above means unbounded, which is always higher,
         * so only a concrete ceiling can conflict.
         */
        if (newCeilingPaise != null && limit.getApproverRole() != null) {
            var above = limits.findByActionTypeAndRoleAndActiveTrue(actionType, limit.getApproverRole());
            if (above.isPresent() && above.get().getMaxValuePaise() != null
                    && newCeilingPaise > above.get().getMaxValuePaise()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "That's higher than " + humanRole(limit.getApproverRole()) + " can approve ("
                        + rupees(above.get().getMaxValuePaise()) + "). Raise their limit first, or "
                        + "escalating would make approval LESS likely, not more.");
            }
        }

        Long previous = limit.getMaxValuePaise();
        limit.setMaxValuePaise(newCeilingPaise);
        limit.setUpdatedAt(java.time.Instant.now());
        limit.setUpdatedByStaffId(byStaffId);
        limits.save(limit);

        log.info("Authority band changed: {} / {} from {} to {} by staff {}.",
                actionType, role, previous, newCeilingPaise, byStaffId);
        return limit;
    }

    /** Every configured action, for the settings screen. */
    @Transactional(readOnly = true)
    public List<String> allActionTypes() {
        return limits.findAllActionTypes();
    }

    /** "₹500" — approvers read rupees, and the database stores paise. */
    private static String rupees(long paise) {
        return "₹" + (paise / 100);
    }

    /** Role codes are for storage; a refusal a human reads should name a team. */
    private static String humanRole(String role) {
        if (role == null) return "someone senior";
        return switch (role) {
            case "support_agent" -> "a support agent";
            case "support_lead" -> "a support manager";
            case "ops_admin" -> "the operations team";
            // Session 65 — without this an admin was described to people as "admin", the raw code.
            case "admin" -> "an admin";
            case "finance_admin" -> "the finance team";
            case "super_admin" -> "the platform owner";
            default -> role.replace('_', ' ');
        };
    }
}
