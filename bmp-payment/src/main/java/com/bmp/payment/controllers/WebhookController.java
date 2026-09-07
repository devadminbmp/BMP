package com.bmp.payment.controllers;

import com.bmp.payment.services.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The gateway's webhook. Session 50.
 *
 * <h2>This endpoint is PUBLIC, and that is not a mistake</h2>
 * Razorpay calls it from its own servers and holds no BMP credential, so there is no token to
 * require. It is therefore in this service's {@code public-paths}, and <b>its only defence is the
 * signature check</b> in {@link WebhookService}. That check is not a hardening task to do later;
 * without it, anyone who learns this URL can mark any booking paid.
 *
 * <h2>Why it takes a raw String and not a parsed object</h2>
 * The signature is computed over the EXACT bytes the gateway sent. Letting Spring deserialise to
 * a DTO and re-serialising to verify would change whitespace and key order, so correct payloads
 * would fail — and the tempting fix for that is to stop checking the signature, which is how this
 * goes badly wrong. {@code @RequestBody String} keeps the original.
 *
 * <h2>Why it almost always returns 200</h2>
 * A non-2xx tells the gateway to retry. Retrying is right for "our database was briefly down" and
 * wrong for everything else: an unparseable body will not parse on the third attempt, and a
 * permanent retry loop buries the real failures in noise. So a bad signature gets 401 (it should
 * stop, and it is worth alerting on), and everything else gets 200 with the outcome in the body
 * and the detail in our logs.
 */
@Tag(name = "Payment webhook", description = "PUBLIC by necessity — defended by signature "
        + "verification, not by a token. The only source of payment truth.")
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    @Operation(
        summary = "Gateway payment webhook",
        description = "Verifies the signature, deduplicates on the gateway's event id, captures "
            + "the payment, writes the commission ledger and publishes payment.captured — all in "
            + "one transaction. Safe to redeliver: the same event applied twice is a no-op.")
    @PostMapping(value = "/api/v1/payment-orders/webhook", consumes = "application/json")
    public ResponseEntity<WebhookAck> receive(
            @RequestBody String rawBody,
            // Razorpay's header. Named explicitly rather than read from a map so the dependency
            // is visible in the signature of the method that depends on it.
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        WebhookService.Outcome outcome;
        try {
            outcome = service.handle(rawBody, signature);
        } catch (Exception e) {
            /*
             * An unexpected failure — a database blip, most likely. This is the ONE case where a
             * retry genuinely helps, so it is also the one case that returns a 5xx.
             */
            log.error("Webhook processing threw ({}). Returning 500 so the gateway retries.",
                    e.toString(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new WebhookAck(false, "RETRY"));
        }

        if (!outcome.accepted()) {
            // 401 rather than 200: a forged or misconfigured caller should be told to stop, and
            // this is the response worth alerting on.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new WebhookAck(false, outcome.reason()));
        }
        return ResponseEntity.ok(new WebhookAck(true, outcome.reason()));
    }

    /** Deliberately says nothing about bookings or amounts — the caller is not a BMP client. */
    public record WebhookAck(boolean ok, String status) {}
}
