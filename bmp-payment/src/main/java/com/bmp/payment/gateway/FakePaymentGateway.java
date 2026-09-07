package com.bmp.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.UUID;

/**
 * A gateway that takes no money. Session 50.
 *
 * <h2>What it is for</h2>
 * The entire payment path — freezing the split, deduplicating a webhook, confirming the booking,
 * turning the invoice into a receipt, writing the ledger — is BMP's logic, not Razorpay's. This
 * lets all of it be built, demonstrated and tested before an account exists, and keeps it
 * testable afterwards without a card.
 *
 * <h2>THE SAFETY PROPERTY, AND WHY IT IS BELT AND BRACES</h2>
 * A fake gateway reachable in production is worse than no payments at all. It would silently
 * confirm bookings nobody paid for, credit a commission ledger with money that never arrived, and
 * do it invisibly — because from every screen's point of view the payment simply worked.
 *
 * <p>Three independent things have to be true before this bean exists:
 * <ol>
 *   <li>{@code bmp.payment.gateway=fake} is set explicitly. There is no default that selects it —
 *       {@code matchIfMissing} is absent on purpose, the same rule the outbox relay follows.</li>
 *   <li>The {@code prod} profile is NOT active. Even with the property set, this bean is not
 *       created in production; the context fails to start instead, loudly, at deploy time.</li>
 *   <li>Every call logs at WARN naming itself. A grep of production logs for "FAKE PAYMENT"
 *       is the last line of defence and costs nothing.</li>
 * </ol>
 *
 * <p>Any one of those could be got wrong by a hurried config change. All three at once is hard to
 * do by accident, which is the point.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "bmp.payment.gateway", havingValue = "fake")
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

    private final ObjectMapper mapper;

    public FakePaymentGateway(ObjectMapper mapper) {
        this.mapper = mapper;
        log.warn("═══ FAKE PAYMENT GATEWAY ACTIVE ═══ No money will be taken or moved. Bookings "
                + "will be confirmed against payments that did not happen. This must never be the "
                + "case in production; set bmp.payment.gateway=razorpay there.");
    }

    @Override
    public GatewayOrder createOrder(long amountPaise, String receipt, String idempotencyKey) {
        /*
         * The id is DERIVED from the idempotency key, not random.
         *
         * That makes the fake reproduce the property that actually matters about a real gateway's
         * idempotency: calling create twice with the same key gives you back the same order. A
         * random id would let a retry bug pass in dev and fail in production, which is the exact
         * class of bug a fake is supposed to catch rather than hide.
         */
        String id = "order_fake_" + shortHash(idempotencyKey);
        log.warn("FAKE PAYMENT: pretending to create order {} for {} paise (receipt {}). "
                + "No gateway was contacted.", id, amountPaise, receipt);
        return new GatewayOrder(id, "created");
    }

    /**
     * Always true, and that is exactly why this class must not reach production.
     *
     * <p>Not "verify against a dev secret": a fake that verifies nothing makes the danger obvious
     * to anyone reading it, whereas a fake that appears to check something invites the belief
     * that it does.
     */
    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        log.warn("FAKE PAYMENT: accepting a webhook WITHOUT verifying its signature. Anyone who "
                + "can reach this endpoint can mark a booking paid.");
        return true;
    }

    /**
     * Reads the same shape the real gateway sends, so a fixture captured from Razorpay's docs
     * parses here unchanged. Deliberately strict — a fake that accepts anything would let a
     * malformed payload pass in dev and fail in production.
     */
    @Override
    public WebhookPayload parseWebhook(String rawBody) {
        try {
            JsonNode root = mapper.readTree(rawBody);
            JsonNode entity = root.path("payload").path("payment").path("entity");
            long amount = entity.path("amount").asLong(-1);
            if (amount < 0) {
                throw new IllegalArgumentException(
                        "payload.payment.entity.amount is missing — refusing to guess an amount");
            }
            return new WebhookPayload(
                    require(root, "id"),
                    require(root, "event"),
                    entity.path("order_id").asText(null),
                    entity.path("id").asText(null),
                    amount,
                    entity.path("error_description").asText(null));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable webhook body: " + e, e);
        }
    }

    private static String require(JsonNode node, String field) {
        String v = node.path(field).asText(null);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Webhook is missing required field '" + field + "'");
        }
        return v;
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public String refund(String gatewayPaymentId, long amountPaise, UUID reference) {
        String id = "rfnd_fake_" + shortHash(reference + ":" + amountPaise);
        log.warn("FAKE PAYMENT: pretending to refund {} paise against {} (ref {}). NO MONEY HAS "
                + "MOVED — if a customer is expecting this back, they will not receive it.",
                amountPaise, gatewayPaymentId, reference);
        return id;
    }

    /** Stable, short, and not security-relevant — it only has to be deterministic. */
    private static String shortHash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes());
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
