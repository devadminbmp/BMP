package com.bmp.payment.services;

import com.bmp.common.events.PaymentCaptured;
import com.bmp.common.outbox.OutboxPublisher;
import com.bmp.payment.entities.CommissionLedger;
import com.bmp.payment.entities.PaymentOrder;
import com.bmp.payment.entities.WebhookEvent;
import com.bmp.payment.gateway.PaymentGateway;
import com.bmp.payment.repositories.CommissionLedgerRepository;
import com.bmp.payment.repositories.PaymentOrderRepository;
import com.bmp.payment.repositories.WebhookEventRepository;
import com.bmp.common.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The gateway webhook — the only source of payment truth. Session 50.
 *
 * <h2>Why nothing else may confirm a payment</h2>
 * The client cannot be trusted: a browser saying "I paid" is a request, not a fact, and anyone
 * can send one. The create-order call cannot be trusted either — it only means a payment was
 * <em>started</em>, and a card declined a second later would still have produced a confirmed
 * booking. Only the gateway knows whether money moved, and this is where it tells us.
 *
 * <h2>The three properties this class must have</h2>
 * <ol>
 *   <li><b>Authenticated.</b> The endpoint is publicly reachable by necessity — the gateway calls
 *       it with no BMP credential. Without signature verification, anyone who learns the URL can
 *       mark any booking paid.</li>
 *   <li><b>Idempotent.</b> Gateways deliver at least once. The same capture WILL arrive twice, and
 *       applying it twice would double the ledger and mail the customer two receipts.</li>
 *   <li><b>Fast and durable.</b> Store the raw payload and return 200 quickly; a gateway that
 *       times out retries, which multiplies the load exactly when something is already wrong.</li>
 * </ol>
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookEventRepository events;
    private final PaymentOrderRepository orders;
    private final CommissionLedgerRepository ledger;
    private final PaymentGateway gateway;
    private final OutboxPublisher outbox;

    public WebhookService(WebhookEventRepository events, PaymentOrderRepository orders,
                           CommissionLedgerRepository ledger, PaymentGateway gateway,
                           OutboxPublisher outbox) {
        this.events = events;
        this.orders = orders;
        this.ledger = ledger;
        this.gateway = gateway;
        this.outbox = outbox;
    }

    /** What the controller should return, and why — so the HTTP layer holds no policy. */
    public record Outcome(boolean accepted, String reason) {}

    /**
     * Handle one webhook delivery.
     *
     * @param rawBody   the EXACT bytes received. Never a re-serialised object: the signature is
     *                  over the original, and re-serialising changes whitespace and key order.
     * @param signature the gateway's header
     */
    @Transactional
    public Outcome handle(String rawBody, String signature) {
        // ── 1. is it really from the gateway? ────────────────────────────────────────────────
        if (!gateway.verifyWebhookSignature(rawBody, signature)) {
            // WARN, not ERROR: on a public endpoint this is also what a random internet scan
            // looks like. It is only alarming in volume, which is a monitoring question.
            log.warn("Rejected a webhook with an INVALID SIGNATURE ({} bytes). Nothing was "
                    + "processed.", rawBody == null ? 0 : rawBody.length());
            return new Outcome(false, "INVALID_SIGNATURE");
        }

        PaymentGateway.WebhookPayload p;
        try {
            p = gateway.parseWebhook(rawBody);
        } catch (RuntimeException e) {
            /*
             * Signature valid but the body is a shape we don't understand — a gateway API change,
             * or an event type we never subscribed to. ERROR, because a correctly-signed message
             * we cannot read is our problem, not an attacker's.
             *
             * Returns accepted=true so the gateway stops retrying. Retrying will not make an
             * unparseable body parseable, and a permanent retry loop buries real failures.
             */
            log.error("A correctly-signed webhook could not be parsed ({}). Body kept in the log "
                    + "for replay. NOT retrying.", e.toString());
            return new Outcome(true, "UNPARSEABLE_BUT_ACCEPTED");
        }

        // ── 2. have we already seen this exact event? ────────────────────────────────────────
        //
        // Checked first for the common case, but the UNIQUE index on razorpay_event_id is what
        // actually guarantees it — two deliveries arriving concurrently both pass this check.
        if (events.existsByRazorpayEventId(p.eventId())) {
            log.info("Webhook {} ({}) already processed — ignoring the redelivery.",
                    p.eventId(), p.eventType());
            return new Outcome(true, "DUPLICATE_IGNORED");
        }

        try {
            events.save(new WebhookEvent(p.eventId(), p.eventType(), rawBody, false));
            // Force the insert now so a concurrent duplicate hits the constraint HERE, before any
            // money is moved, rather than after the ledger has been written twice.
            events.flush();
        } catch (DataIntegrityViolationException e) {
            log.info("Webhook {} lost the race to a concurrent delivery — the other one is "
                    + "handling it. This is the unique index doing its job.", p.eventId());
            return new Outcome(true, "DUPLICATE_IGNORED");
        }

        // ── 3. apply it ──────────────────────────────────────────────────────────────────────
        switch (p.eventType() == null ? "" : p.eventType()) {
            case "payment.captured" -> applyCapture(p);
            case "payment.failed" -> applyFailure(p);
            default -> log.info("Webhook {} is of type '{}', which this service does not act on. "
                    + "Stored for audit.", p.eventId(), p.eventType());
        }
        return new Outcome(true, "PROCESSED");
    }

    private void applyCapture(PaymentGateway.WebhookPayload p) {
        PaymentOrder order = orders.findByRazorpayOrderId(p.gatewayOrderId()).orElse(null);
        if (order == null) {
            /*
             * Signed, valid, and about an order we have never heard of. That is either a gateway
             * account shared with another system, or a payment created outside BMP.
             *
             * Loud, and NOT an exception: the money is real and somebody has to reconcile it by
             * hand. Throwing would roll back the webhook_event row and lose the only record that
             * it ever arrived.
             */
            log.error("PAYMENT RECEIVED FOR AN UNKNOWN ORDER. Gateway order {}, payment {}, {} "
                    + "paise. Money has moved and BMP has no booking for it — this needs manual "
                    + "reconciliation.", p.gatewayOrderId(), p.paymentId(), p.amountPaise());
            return;
        }

        /*
         * The amount must match what we asked for.
         *
         * A mismatch is refused rather than "corrected" to the amount received. If they differ,
         * one of two systems is wrong about a price, and quietly accepting the gateway's number
         * would confirm a booking at a price nobody agreed and write a split against it.
         */
        if (p.amountPaise() != order.getAmountPaise().paise()) {
            log.error("AMOUNT MISMATCH on order {}: we asked for {} paise, the gateway captured "
                    + "{}. Refusing to confirm. The booking stays unpaid and this needs a human.",
                    order.getId(), order.getAmountPaise().paise(), p.amountPaise());
            return;
        }

        Instant capturedAt = Instant.now();
        if (!order.capture(p.paymentId(), capturedAt)) {
            // Already captured by an earlier delivery whose event id differed (a gateway resend
            // under a new id). The dedup above cannot catch that; this can.
            log.info("Order {} was already captured — not applying it twice.", order.getId());
            return;
        }
        orders.save(order);

        /*
         * The ledger entry, written in the SAME transaction as the capture.
         *
         * Separating them would create a window in which BMP has been paid and its books don't
         * say so — and a crash inside that window leaves a discrepancy nobody can find later,
         * because nothing records that it should have been written.
         */
        ledger.save(new CommissionLedger("commission_earned",
                order.getCommissionPaise(), order.getBookingId()));

        try {
            outbox.publish(new PaymentCaptured(
                    order.getId(), order.getBookingId(), order.getSalonId(),
                    p.paymentId(),
                    order.getAmountPaise().paise(),
                    order.getCommissionPaise().paise(),
                    order.getSalonSharePaise().paise(),
                    order.getCommissionBps(),
                    capturedAt.toEpochMilli()));
        } catch (Exception e) {
            /*
             * The money is captured and the ledger is written — both correct and both committed
             * with this transaction. What failed is telling bmp-booking, so the booking stays
             * PENDING and the invoice keeps saying "due" for a payment that happened.
             *
             * ERROR and loud, because the customer has paid and the salon does not know.
             */
            log.error("Payment {} captured for booking {} but payment.captured could NOT be "
                    + "queued ({}). The booking is still PENDING and its invoice still says due, "
                    + "for money that has actually arrived.",
                    p.paymentId(), order.getBookingId(), e.toString());
        }

        log.info("Captured {} paise for booking {} (payment {}) — {} commission, {} to salon {}.",
                p.amountPaise(), order.getBookingId(), p.paymentId(),
                order.getCommissionPaise().paise(), order.getSalonSharePaise().paise(),
                order.getSalonId());
    }

    private void applyFailure(PaymentGateway.WebhookPayload p) {
        orders.findByRazorpayOrderId(p.gatewayOrderId()).ifPresentOrElse(order -> {
            if (order.isCaptured()) {
                // A failure arriving after a capture is out-of-order delivery, not a reversal.
                // Acting on it would un-pay a paid booking.
                log.warn("A 'failed' webhook arrived for order {}, which is already captured. "
                        + "Ignoring — out-of-order delivery, not a refund.", order.getId());
                return;
            }
            order.fail(p.failureReason());
            orders.save(order);
            // INFO, not ERROR: a declined card is an ordinary event, and the customer can retry
            // on the same order. Logging it as an error trains people to ignore errors.
            log.info("Payment failed for booking {} ({}). The order stays open for a retry.",
                    order.getBookingId(), p.failureReason());
        }, () -> log.warn("Failure webhook for unknown gateway order {} — ignoring.",
                p.gatewayOrderId()));
    }
}
