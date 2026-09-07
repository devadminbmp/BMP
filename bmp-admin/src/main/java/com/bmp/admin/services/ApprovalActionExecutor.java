package com.bmp.admin.services;

import com.bmp.admin.entities.ApprovalRequest;

/**
 * What actually happens once somebody says yes. Session 58.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * ONE IMPLEMENTATION PER GATED ACTION, DISCOVERED BY SPRING
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * {@code ApprovalRequestService} knows how to route, approve, escalate and audit — and knows
 * NOTHING about coupons, refunds or suspensions. It hands an approved request to whichever
 * executor claims that {@code actionType}.
 *
 * <p>That separation is the reason this design survives the next feature. The alternative — a
 * {@code switch} inside the approval service — means every new gated action edits the file that
 * every other action depends on, and a mistake in the coupon branch can break refunds.
 *
 * <p>Adding an action is therefore: one row in {@code authority_limit}, one class implementing
 * this. No change to the routing, the queue, the audit trail or the console.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THE EXECUTOR OWNS THE PAYLOAD SHAPE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * {@code payload} is JSONB and nothing generic reads inside it. The executor for
 * {@code coupon.issue} knows it contains a customer id, a value and an expiry; the executor for
 * {@code salon.suspend} knows it contains a salon id and a reason. Keeping that knowledge in one
 * place per action is what lets the payload change without touching anything shared.
 *
 * <h2>Implementations must be idempotent where they can be</h2>
 * Execution is retried after a failure — see {@code ApprovalRequest.markFailed}, which keeps the
 * approval rather than discarding it. An executor that issues two coupons when run twice turns a
 * transient network error into money lost.
 */
public interface ApprovalActionExecutor {

    /**
     * The action code this executor handles, e.g. {@code coupon.issue}.
     *
     * <p>Must match {@code authority_limit.action_type} exactly. A mismatch means requests are
     * raised and approved and then find no executor — which
     * {@code ApprovalRequestService} reports loudly rather than silently marking done.
     */
    String actionType();

    /**
     * Carry out the approved action.
     *
     * <p>Throwing marks the request FAILED with the message, leaving the approval intact for a
     * retry. Returning normally marks it EXECUTED. There is no third outcome: an executor that
     * "partly worked" must throw, because a half-done money action recorded as complete is the
     * worst state of the three.
     *
     * @return a short line for the audit trail and the approver's queue — "Coupon SORRY300 issued
     *         to customer 4f2a" — never containing the customer's name or phone.
     */
    String execute(ApprovalRequest request) throws Exception;
}
