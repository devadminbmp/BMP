package com.bmp.payment.gateway;

import java.util.UUID;

/**
 * The payment gateway, behind an interface. Session 50.
 *
 * <h2>Why an interface rather than calling Razorpay directly</h2>
 * Everything interesting about taking a payment is <em>ours</em>: freezing the commission split,
 * deduplicating a redelivered webhook, moving a booking to CONFIRMED, turning an invoice into a
 * receipt, writing the ledger. The gateway itself contributes two HTTP calls and a signature.
 *
 * <p>Wiring those two calls directly into the service would mean none of that logic could be
 * exercised without a Razorpay account, network access and a real card — so in practice it would
 * be tested by clicking through a sandbox, occasionally, by hand. The interface lets the entire
 * money path run in a test, and lets the whole product be demonstrated before an account exists.
 *
 * <h2>The rule that keeps this honest</h2>
 * <b>A fake gateway must never be reachable in production.</b> A system that silently pretends
 * payments succeeded is worse than one that cannot take payments at all: the first quietly gives
 * away services and corrupts the ledger, the second fails loudly and gets fixed.
 *
 * <p>So {@link FakePaymentGateway} is selected only by an explicit property, refuses to load
 * under the {@code prod} profile, and says what it is in its own logs on every call. See its
 * class note.
 *
 * <h2>What this interface deliberately does NOT do</h2>
 * It does not decide anything about money. It creates an order, verifies a signature, and reads a
 * payload. Commission, capture and confirmation are the service's job, because those are the
 * parts that must behave identically whichever gateway is behind this.
 */
public interface PaymentGateway {

    /**
     * Ask the gateway to open an order the customer can pay against.
     *
     * @param amountPaise    integer paise, never a float and never rupees
     * @param receipt        our own reference (the booking ref) that the gateway echoes back.
     *                       This is what makes a stray gateway record traceable to a booking
     *                       when something has gone wrong.
     * @param idempotencyKey ours, not the gateway's. A retried create must not open a second
     *                       order — a customer holding two payment links for one booking will
     *                       eventually pay both.
     * @return the gateway's order id, e.g. {@code order_NpsQm3XyZ}
     */
    GatewayOrder createOrder(long amountPaise, String receipt, String idempotencyKey);

    /**
     * Is this webhook genuinely from the gateway?
     *
     * <h2>Why this is not optional and not "later"</h2>
     * The webhook endpoint must be publicly reachable — the gateway calls it from its own
     * servers, with no BMP credential. Without signature verification, <b>anyone on the internet
     * who learns the URL can mark any booking paid</b> by POSTing a plausible body. That is not a
     * hardening task; it is the only thing standing between an open URL and free haircuts.
     *
     * @param rawBody   the EXACT bytes received. Re-serialising parsed JSON changes whitespace
     *                  and key order, and the signature is over the original bytes — a signature
     *                  checked against a re-serialised body fails for correct payloads and, worse,
     *                  tempts the next person to "fix" it by skipping the check.
     * @param signature the header the gateway sent
     */
    boolean verifyWebhookSignature(String rawBody, String signature);

    /** A gateway order. */
    record GatewayOrder(String gatewayOrderId, String status) {}

    /**
     * The parts of a webhook payload this service acts on.
     *
     * <p>A narrow view on purpose: the raw payload is stored whole for audit and replay, and this
     * record is only what the code branches on. Widening it means the service starts depending on
     * a gateway's JSON shape, which is the thing this package exists to contain.
     *
     * @param eventId        the gateway's own event id — the DEDUP key. A gateway that guarantees
     *                       at-least-once delivery will send the same event twice, and it must
     *                       not be applied twice.
     * @param eventType      e.g. {@code payment.captured}
     * @param gatewayOrderId which order it refers to
     * @param paymentId      {@code pay_XXXX} — what appears on the customer's bank statement
     * @param amountPaise    what was actually taken. Checked against the order; a mismatch is a
     *                       refusal, not a correction.
     * @param failureReason  null unless the event is a failure
     */
    record WebhookPayload(
            String eventId,
            String eventType,
            String gatewayOrderId,
            String paymentId,
            long amountPaise,
            String failureReason) {}

    /** Parse a raw webhook body. Throws if it isn't a shape this service understands. */
    WebhookPayload parseWebhook(String rawBody);

    /** For logs and the admin console: which gateway is actually live right now. */
    String name();

    /**
     * Refund money already captured.
     *
     * @param amountPaise partial refunds are legitimate — a late-cancellation fee is kept and the
     *                    rest returned, which is exactly what the salon's policy tiers produce
     * @return the gateway's refund id, {@code rfnd_XXXX}
     */
    String refund(String gatewayPaymentId, long amountPaise, UUID reference);
}
