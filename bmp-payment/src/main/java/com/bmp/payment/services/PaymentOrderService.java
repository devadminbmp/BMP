package com.bmp.payment.services;

import com.bmp.common.money.Money;
import com.bmp.payment.dto.PaymentDtos.*;
import com.bmp.payment.entities.PaymentOrder;
import com.bmp.payment.repositories.PaymentOrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * BMP-26: payment_order CRUD, data model only — NO real Razorpay API call in this ticket.
 * razorpay_order_id stays NULL; the TODO below marks exactly where the real create-order
 * call goes in Phase 3 (BMP-19).
 *
 * <p>Session 7: {@code @RefreshScope} so {@code allowManualStatus} actually picks up a
 * change pushed via bmp-config-server (config-repo/bmp-payment-service.yml) + a bus
 * refresh, without a redeploy — this is the concrete example that pattern exists for.
 * Without {@code @RefreshScope} here, a bus refresh event would fire but this bean would
 * never be recreated, and the old value would stick until the next full restart.
 */
@Service
@RefreshScope
public class PaymentOrderService {

    /**
     * The fallback rate, used ONLY when the caller sends none — and logged as a fault when it is.
     *
     * <h2>Session 50: this used to be the rate for every salon, and that was a money bug</h2>
     * It read {@code COMMISSION_BPS = 1200} and applied 12% to every order, while Session 48 gave
     * each salon its own {@code salon_policy.commission_bps}, set by an admin at approval,
     * specifically so rates could be negotiated per partner — and made it unsettable by the owner
     * because it is a money field.
     *
     * <p>So BMP could agree 8% with a salon, store 8%, show 8% in the console, and charge 12% on
     * every booking. Nobody would notice until a partner audited a statement, and by then every
     * split in between would be wrong.
     *
     * <p>It survives as a fallback rather than being deleted because a booking must not fail when
     * a rate is missing — the customer is trying to pay. But the fallback is LOUD.
     */
    private static final int DEFAULT_COMMISSION_BPS = 1200;

    /** Guards the dev-only manual status endpoint outside local/dev profiles. Flip this in
     * config-repo/bmp-payment-service.yml + POST /actuator/busrefresh (or a GitHub push
     * once the webhook is wired) to turn it off everywhere without a redeploy. */
    @Value("${bmp.payment.allow-manual-status:true}")
    private boolean allowManualStatus;

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PaymentOrderService.class);

    private final PaymentOrderRepository repo;
    /** Session 50 — behind an interface so the whole money path is testable without a gateway. */
    private final com.bmp.payment.gateway.PaymentGateway gateway;

    public PaymentOrderService(PaymentOrderRepository repo,
                                com.bmp.payment.gateway.PaymentGateway gateway) {
        this.repo = repo;
        this.gateway = gateway;
    }

    /**
     * Open a payment order for a booking.
     *
     * <h2>Idempotent by returning, not by throwing</h2>
     * This used to 409 when an order already existed. That is wrong for the caller it actually
     * has: bmp-booking creates the order as part of booking, and a retried booking request would
     * turn a recoverable duplicate into a failed booking for a customer who did nothing wrong.
     * Returning the existing order is what "already done" should look like.
     */
    @Transactional
    public PaymentOrderResponse create(UUID bookingId, CreatePaymentOrderRequest req) {
        String idempotencyKey = bookingId + ":1";

        var existing = repo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Payment order for booking {} already exists ({}) — returning it rather than "
                    + "failing the caller's retry.", bookingId, existing.get().getId());
            return toResponse(existing.get());
        }

        /*
         * The commission rate comes from the SALON, not from this class.
         *
         * A missing rate is a fault, not a default: it means somebody's negotiated terms were not
         * applied to real money. But it must not fail the booking — the customer is mid-checkout
         * and cannot fix it — so it falls back and shouts.
         */
        int bps = DEFAULT_COMMISSION_BPS;
        if (req.commissionBps() == null) {
            log.error("Payment order for booking {} arrived with NO commission rate. Falling back "
                    + "to the platform default of {} bps. If this salon has a negotiated rate, "
                    + "THIS SPLIT IS WRONG and will need correcting — check that bmp-booking is "
                    + "reading salon_policy.commission_bps.", bookingId, DEFAULT_COMMISSION_BPS);
        } else if (req.commissionBps() < 0 || req.commissionBps() > 5000) {
            // Mirrors V004's CHECK. Refusing outright would fail the booking; clamping silently
            // would be worse. Fall back, loudly, and let the insert succeed.
            log.error("Payment order for booking {} arrived with an out-of-range commission of {} "
                    + "bps. Using {} instead. 5000 bps is 50%; a value like 12 means somebody "
                    + "typed a percentage into a basis-points field.",
                    bookingId, req.commissionBps(), DEFAULT_COMMISSION_BPS);
        } else {
            bps = req.commissionBps();
        }

        Money amount = Money.ofPaise(req.amountPaise());
        Money commission = amount.percentBps(bps);
        // Subtraction, never a second percentBps(10000 - bps). Two independent roundings can
        // disagree by a paise, and the CHECK constraint in V004 asserts they must sum exactly.
        Money salonShare = amount.minus(commission);

        PaymentOrder order = new PaymentOrder(bookingId, req.salonId(), bps, null, idempotencyKey,
                amount, commission, salonShare, null, null, "created");

        /*
         * Ask the gateway for an order, then save.
         *
         * If the gateway fails, the booking fails — deliberately. The alternative is a booking
         * the customer cannot pay for, which they discover at the salon door. Better to fail now,
         * while they are still looking at the screen and can try again.
         */
        try {
            var gw = gateway.createOrder(amount.paise(), req.receipt(), idempotencyKey);
            order.attachGatewayOrder(gw.gatewayOrderId());
        } catch (UnsupportedOperationException e) {
            // The real gateway's create-order isn't written yet. Say so plainly rather than
            // letting a NullPointerException surface three layers up.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (Exception e) {
            log.error("Gateway {} could not create an order for booking {} ({}).",
                    gateway.name(), bookingId, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "PAYMENT_GATEWAY_UNAVAILABLE: we couldn't start the payment. Nothing has been "
                    + "charged — please try again.");
        }

        order = repo.save(order);
        log.info("Payment order {} opened for booking {} — {} paise, {} bps commission = {} to "
                + "BMP, {} to salon {}.", order.getId(), bookingId, amount.paise(), bps,
                commission.paise(), salonShare.paise(), req.salonId());
        return toResponse(order);
    }

    public PaymentOrderResponse getById(UUID id) {
        return repo.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND"));
    }

    public PaymentOrderResponse getByBookingId(UUID bookingId) {
        return repo.findByBookingId(bookingId).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND"));
    }

    /**
     * DEV-ONLY. Exists ONLY so booking flows can be tested locally without a live Razorpay
     * webhook. Directly violates "Razorpay webhook = ONLY source of payment truth" if left
     * reachable in production. Feature-flagged via bmp.payment.allow-manual-status — set to
     * false outside local/dev profiles, or delete this endpoint once BMP-19 wires the real webhook.
     */
    @Transactional
    public PaymentOrderResponse updateStatusDevOnly(UUID id, UpdateStatusRequest req) {
        if (!allowManualStatus) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MANUAL_STATUS_UPDATE_DISABLED");
        }
        PaymentOrder order = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND"));
        order.setStatus(req.status());
        if ("captured".equals(req.status())) {
            order.setPaymentCapturedAt(java.time.Instant.now());
        }
        return toResponse(order);
    }

    private PaymentOrderResponse toResponse(PaymentOrder o) {
        return new PaymentOrderResponse(o.getId(), o.getBookingId(), o.getAmountPaise().paise(),
                o.getCommissionPaise().paise(), o.getSalonSharePaise().paise(), o.getRazorpayOrderId(),
                o.getStatus(), o.getPaymentCapturedAt(), o.getCreatedAt(),
                o.getSalonId(), o.getCommissionBps(), o.getGatewayPaymentId(), o.getFailureReason());
    }
}
