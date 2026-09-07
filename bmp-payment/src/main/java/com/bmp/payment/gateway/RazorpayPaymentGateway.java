package com.bmp.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Razorpay. Session 50 — <b>signature verification and parsing are real; the two HTTP calls are
 * not yet written.</b>
 *
 * <h2>What is real here and what is not</h2>
 * <table>
 *   <tr><td>{@link #verifyWebhookSignature}</td><td><b>REAL.</b> HMAC-SHA256, constant-time
 *       compare. This is the security boundary and there is no version of it worth stubbing.</td></tr>
 *   <tr><td>{@link #parseWebhook}</td><td><b>REAL.</b> Reads Razorpay's documented payload
 *       shape.</td></tr>
 *   <tr><td>{@link #createOrder}</td><td>NOT WRITTEN — throws. Needs the Razorpay SDK or a REST
 *       call to {@code POST /v1/orders}.</td></tr>
 *   <tr><td>{@link #refund}</td><td>NOT WRITTEN — throws. {@code POST /v1/payments/{id}/refund}.</td></tr>
 * </table>
 *
 * <h2>Why the unwritten methods THROW rather than return something plausible</h2>
 * The alternative — returning a made-up order id and logging a warning — produces a system that
 * appears to work: bookings get created, screens render, and nothing is wrong until a customer
 * tries to pay against an order that does not exist at the gateway. By then the failure is a
 * support conversation with a person who has already turned up at a salon.
 *
 * <p>Throwing means the booking fails at the moment of booking, with a message naming the cause.
 * Nobody is misled and nothing is silently half-done.
 *
 * <h2>Credentials</h2>
 * Read from the environment, defaulting to empty, and <b>never committed</b> — they belong in the
 * gitignored local secrets file alongside the mail password, following the rule already
 * established in this repo. An empty secret makes {@link #verifyWebhookSignature} refuse
 * everything rather than accept everything, which is the safe direction for that particular bug.
 */
@Component
@ConditionalOnProperty(name = "bmp.payment.gateway", havingValue = "razorpay")
public class RazorpayPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentGateway.class);

    @Value("${bmp.payment.razorpay.key-id:}")
    private String keyId;

    @Value("${bmp.payment.razorpay.key-secret:}")
    private String keySecret;

    @Value("${bmp.payment.razorpay.webhook-secret:}")
    private String webhookSecret;

    private final ObjectMapper mapper;

    public RazorpayPaymentGateway(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public GatewayOrder createOrder(long amountPaise, String receipt, String idempotencyKey) {
        requireConfigured();
        /*
         * POST https://api.razorpay.com/v1/orders
         *   Basic auth: keyId : keySecret
         *   Body: { "amount": <paise>, "currency": "INR", "receipt": <receipt> }
         *   Header: X-Razorpay-Idempotency-Key: <idempotencyKey>
         *
         * Deliberately not half-written. A partial implementation that returns a fabricated id
         * would let a booking succeed against an order the gateway has never heard of, and the
         * customer discovers it when they try to pay.
         */
        throw new UnsupportedOperationException(
                "RAZORPAY_CREATE_ORDER_NOT_IMPLEMENTED: the Razorpay create-order call has not "
                + "been written yet. Set bmp.payment.gateway=fake to run the full payment flow "
                + "without a gateway, or implement this method. It deliberately throws rather "
                + "than returning a fake order id, which would fail later and less clearly.");
    }

    /**
     * REAL. HMAC-SHA256 over the exact received bytes, compared in constant time.
     *
     * <h2>Why constant-time comparison</h2>
     * {@code String.equals} returns as soon as two bytes differ, so how long it takes leaks how
     * many leading bytes were right. An attacker who can send many webhooks and time the replies
     * can recover a valid signature byte by byte. {@link MessageDigest#isEqual} does not
     * short-circuit. This is cheap and the alternative is subtly broken.
     *
     * <h2>Why an unset secret refuses everything</h2>
     * An empty secret would otherwise produce a valid HMAC of the body under an empty key — which
     * an attacker can compute too. Refusing outright turns a misconfiguration into "payments stop
     * working", which someone notices in minutes, instead of "anyone can mark bookings paid",
     * which nobody notices at all.
     */
    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("bmp.payment.razorpay.webhook-secret is NOT SET. Refusing every webhook. "
                    + "Payments will not be captured until it is configured — which is the safe "
                    + "failure: the alternative is accepting forged webhooks.");
            return false;
        }
        if (signature == null || signature.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(computed.length * 2);
            for (byte b : computed) hex.append(String.format("%02x", b));
            return MessageDigest.isEqual(
                    hex.toString().getBytes(StandardCharsets.UTF_8),
                    signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Webhook signature verification threw ({}). Treating as INVALID.", e.toString());
            return false;
        }
    }

    /** REAL. Razorpay's documented {@code payment.captured} / {@code payment.failed} shape. */
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
                    root.path("id").asText(null),
                    root.path("event").asText(null),
                    entity.path("order_id").asText(null),
                    entity.path("id").asText(null),
                    amount,
                    entity.path("error_description").asText(null));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable Razorpay webhook body: " + e, e);
        }
    }

    @Override
    public String name() {
        return "razorpay";
    }

    @Override
    public String refund(String gatewayPaymentId, long amountPaise, UUID reference) {
        requireConfigured();
        // POST /v1/payments/{gatewayPaymentId}/refund  { "amount": <paise> }
        throw new UnsupportedOperationException(
                "RAZORPAY_REFUND_NOT_IMPLEMENTED: refunds must be issued from the Razorpay "
                + "dashboard until this is written. It throws rather than reporting success for "
                + "a refund that never happened — a customer told they have been refunded and "
                + "then not refunded is the worst outcome available here.");
    }

    private void requireConfigured() {
        if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
            throw new IllegalStateException(
                    "RAZORPAY_NOT_CONFIGURED: bmp.payment.razorpay.key-id / key-secret are unset. "
                    + "They belong in the gitignored local secrets file, never in application.yml.");
        }
    }
}
