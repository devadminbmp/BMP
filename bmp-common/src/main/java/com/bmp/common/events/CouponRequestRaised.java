package com.bmp.common.events;

import java.util.UUID;

/**
 * Somebody has asked for a coupon they cannot issue themselves, and an admin needs to decide.
 *
 * <p>Session 32. Emitted by bmp-rewards when a support agent goes over their limit or a salon
 * owner requests a promotion.
 *
 * <h2>Why this event exists</h2>
 * The approval queue was built with nothing telling anyone it had anything in it. An approval
 * workflow where the approver is never notified relies entirely on somebody remembering to open
 * a screen — which is precisely the failure the workflow was built to prevent, moved one step
 * along. A request that sits unseen for three days has the same effect on the customer as a
 * refusal, and takes longer.
 *
 * <h2>Who it goes to</h2>
 * The ops address ({@code bmp.notification.ops-email}), not an individual. There is no "admin
 * team" distribution list in the system, and inventing per-admin routing would mean deciding
 * whose problem each request is — which is what the queue is for. One address that a human
 * watches beats a routing rule nobody maintains.
 *
 * @param aggregateId   the coupon_request row id
 * @param requestRef    human reference, e.g. CR-2026-00042 — what gets quoted on a call
 * @param requesterType staff | salon_owner. The two read very differently to an approver.
 * @param requesterName who asked, for the subject line
 * @param summary       one line: what they want and how much. Enough to triage without opening
 *                      the console, which is the difference between "I'll look later" and a
 *                      decision made from a phone.
 */
public record CouponRequestRaised(
        UUID aggregateId,
        String requestRef,
        String requesterType,
        String requesterName,
        String summary,
        String justification
) implements DomainEvent {

    @Override
    public String eventType() {
        return "coupon_request.raised";
    }
}
