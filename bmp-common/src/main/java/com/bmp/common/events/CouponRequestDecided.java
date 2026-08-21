package com.bmp.common.events;

import java.util.UUID;

/**
 * A coupon request was approved or rejected, and the person who asked needs to know.
 *
 * <p>Session 32. Emitted by bmp-rewards on both outcomes — a rejection is at least as important
 * to deliver as an approval, because the requester has a customer waiting and is otherwise
 * still waiting too.
 *
 * <h2>Contact details travel WITH the event</h2>
 * {@code NotificationDispatcher} holds no clients by design; every event carries the address it
 * needs. So {@code email} and {@code phone} are resolved when the request is RAISED and stored
 * on the row, not looked up here. Both are nullable: a staff member has an email and no phone
 * on file, a salon owner has both, and a request raised before this change has neither. The
 * dispatcher sends on whichever channels it actually has.
 *
 * @param aggregateId  the coupon_request row id
 * @param approved     true = approved, false = rejected. One boolean rather than a status
 *                     string, because those are the only two outcomes that notify — cancelled
 *                     is the requester's own action and expired notifies nobody usefully.
 * @param couponCode   the code to hand out, when approved. Null on rejection.
 * @param grantedNote  what changed, if the approver granted less than was asked — the single
 *                     most important thing in the message, because a requester who misses it
 *                     quotes the original figure to a customer.
 * @param decisionNote the approver's own words. On a rejection this is the whole message: a
 *                     refusal with no reason gets re-asked verbatim tomorrow.
 */
public record CouponRequestDecided(
        UUID aggregateId,
        String requestRef,
        boolean approved,
        UUID requesterUserId,
        String requesterName,
        String email,
        String phone,
        String couponCode,
        String grantedNote,
        String decisionNote
) implements DomainEvent {

    @Override
    public String eventType() {
        return "coupon_request.decided";
    }
}
