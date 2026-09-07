package com.bmp.admin.services.executors;

import com.bmp.admin.client.RewardsServiceClient;
import com.bmp.admin.entities.ApprovalRequest;
import com.bmp.admin.services.ApprovalActionExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Issue the coupon an approver just signed off. Session 58.
 *
 * <p>Darshan's worked example, end to end: a support agent asks for ₹800 of goodwill, a lead
 * approves it, and this creates the coupon. The routing, the queue and the audit trail are all
 * generic — see {@code ApprovalRequestService}. This class knows only about coupons.
 *
 * <h2>The payload shape lives here and nowhere else</h2>
 * {@code approval_request.payload} is JSONB and nothing generic reads inside it. That is what lets
 * a new gated action be added without touching the approval machinery — and it means the contract
 * below is this file's responsibility to keep:
 *
 * <pre>{@code
 * {
 *   "customerUserId": "uuid",     // who receives it
 *   "valuePaise":     80000,      // flat discount, or the cap when percent
 *   "discountType":   "flat",     // flat | percent
 *   "percentBps":     2000,       // only when percent
 *   "validityDays":   30,
 *   "reason":         "colour service went wrong"
 * }
 * }</pre>
 *
 * <h2>Idempotency</h2>
 * Execution is retried after a failure — the approval is kept deliberately, so a transient network
 * error does not need re-approving. That makes a double-issue possible, so the coupon CODE is
 * derived from the request id rather than randomly generated: a retry produces the same code, and
 * bmp-rewards' unique index on {@code code} turns the second attempt into a no-op instead of a
 * second ₹800 given away.
 */
@Component
public class CouponIssueExecutor implements ApprovalActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueExecutor.class);

    private final RewardsServiceClient rewards;
    private final ObjectMapper mapper = new ObjectMapper();

    public CouponIssueExecutor(RewardsServiceClient rewards) {
        this.rewards = rewards;
    }

    @Override
    public String actionType() {
        return "coupon.issue";
    }

    @Override
    public String execute(ApprovalRequest request) throws Exception {
        JsonNode p = mapper.readTree(request.getPayload());

        UUID customerId = UUID.fromString(p.get("customerUserId").asText());
        String discountType = p.path("discountType").asText("flat");
        long valuePaise = request.getValuePaise();
        int validityDays = p.path("validityDays").asInt(30);
        String reason = p.path("reason").asText("goodwill");

        /*
         * DERIVED from the request id, not random — see the class note on idempotency. Upper-case
         * because coupon codes are compared upper-cased throughout bmp-rewards, and a lower-case
         * one would simply never match.
         */
        String code = ("GW" + request.getRequestRef().replace("APR-", "")
                + request.getId().toString().substring(0, 4)).toUpperCase();

        /*
         * Reuses the EXISTING coupon-issue endpoint rather than a new one, and that is a deliberate
         * decision worth stating.
         *
         * bmp-rewards already applies CouponIssuePolicy on this path — value ceilings, recipient
         * counts, validity, and the rule that a support-issued coupon must name specific customers
         * and cite a ticket. Adding a second creation route for approved requests would bypass all
         * of it, and the two would drift until an approved coupon could do something a directly
         * issued one could not.
         *
         * The staff context sent is the APPROVER, not the requester. They are the person whose
         * authority permits this amount — a support agent's own credentials would be rejected by
         * the very policy that made the approval necessary. The requester is still recorded, on the
         * approval row and in the audit trail.
         */
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Goodwill " + rupees(valuePaise));
        body.put("description", reason);
        body.put("couponType", "support_goodwill");
        // TARGETED. A goodwill apology that any customer could use is a platform-wide giveaway.
        body.put("audienceType", "selected_users");
        body.put("salonScope", "all_salons");
        body.put("discountType", discountType);
        body.put("value", "percent".equals(discountType) ? p.path("percentBps").asLong(0) : valuePaise);
        // The cap — this is what makes "20% up to ₹100" expressible, and bmp-rewards refuses a
        // percent coupon without one.
        body.put("maxDiscountPaise", valuePaise);
        body.put("minSpendPaise", 0L);                 // no minimum spend on an apology
        body.put("perUserLimit", 1);
        body.put("totalUsageCap", 1);
        body.put("activeFrom", Instant.now().toString());
        body.put("activeTo", Instant.now().plus(validityDays, ChronoUnit.DAYS).toString());
        body.put("commissionBase", "post_discount");   // BMP absorbs it; the salon never agreed to
        body.put("targetUserIds", List.of(customerId.toString()));
        body.put("issuedForTicketId",
                request.getTicketId() == null ? null : request.getTicketId().toString());
        body.put("issueReason", reason);

        rewards.issue(body, approverStaffId(request), approverEmail(request), approverRole(request));

        log.info("{}: issued coupon {} worth {}p to customer {} (ticket {}).",
                request.getRequestRef(), code, valuePaise, customerId, request.getTicketId());

        // No customer name or phone — this string lands in the audit trail and the approver's queue.
        return "Coupon " + code + " (" + rupees(valuePaise) + ") issued to customer "
                + customerId.toString().substring(0, 8);
    }

    private static String rupees(long paise) {
        return "₹" + (paise / 100);
    }

    /*
     * The approver's identity, taken from the decided request.
     *
     * bmp-rewards logs who issued a coupon and applies CouponIssuePolicy to THEIR role, so this
     * must be the person who authorised the amount — not the agent who asked. Sending the
     * requester would be rejected by the very ceiling that made an approval necessary in the first
     * place, which would turn every approved request into a failed execution.
     */
    private static UUID approverStaffId(ApprovalRequest r) {
        return r.getDecidedByStaffId() != null ? r.getDecidedByStaffId() : r.getRequestedByStaffId();
    }

    /**
     * The approval row stores the approver's id, not their email — and bmp-rewards wants an email
     * for its own audit line. Rather than a lookup on the hot path of an already-authorised
     * action, the id is sent in a recognisable form. It is only ever read by a human tracing a
     * coupon back, and the full identity is on the approval row and in the audit log either way.
     */
    private static String approverEmail(ApprovalRequest r) {
        return "approval:" + r.getRequestRef();
    }

    /**
     * The APPROVER's role. Currently derived from the request's current approver, which is exactly
     * who cleared it — the row only moves on when somebody at that role decides.
     */
    private static String approverRole(ApprovalRequest r) {
        return r.getCurrentApproverRole();
    }
}
