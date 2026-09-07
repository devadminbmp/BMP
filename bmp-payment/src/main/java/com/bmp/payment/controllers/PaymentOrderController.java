package com.bmp.payment.controllers;

import com.bmp.payment.dto.PaymentDtos.*;
import com.bmp.payment.services.PaymentOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * BMP-26: payment_order CRUD — data model only, no real Razorpay call yet.
 *
 * <h2>Session 29: EVERY endpoint here is SERVICE-ONLY</h2>
 * Before this pass, bmp-payment declared no {@code public-paths}, so the {@code /**} default in
 * {@code CommonSecurityConfig} applied and all four endpoints were reachable <b>with no token
 * at all</b>. Including the one below that sets a payment's status.
 *
 * <p>The class-level annotation is deliberate: this is a service whose entire surface is money,
 * and a default-deny at the class means a method added later is protected before anyone
 * remembers to annotate it. The per-method exceptions, if any are ever needed, should be the
 * thing that looks unusual — not the protection.
 *
 * <p>Payment orders are created and read by other SERVICES (bmp-booking creating one alongside
 * a booking; bmp-admin reading one for a refund). A customer never talks to this service
 * directly — their app talks to the payment gateway's SDK and the gateway talks to us. So
 * there is no end-user role that belongs here, not even for reads: a payment order reveals what
 * someone paid, for what, and the commission split on it.
 */
@Tag(name = "Payments", description = "payment_order CRUD. Commission comes from the SALON's own rate, never a platform constant (Session 50). SERVICE role only — no end-user token reaches this service. No real payment gateway wired yet.")
@RestController
@PreAuthorize("hasRole('SERVICE')")
public class PaymentOrderController {

    private final PaymentOrderService service;

    public PaymentOrderController(PaymentOrderService service) {
        this.service = service;
    }

    /**
     * Open a payment order for a booking.
     *
     * <h2>Session 50 — note the SECOND path below, and why</h2>
     * The original mapping is {@code /api/v1/bookings/{id}/payment-order}: a BOOKING-shaped path
     * served by bmp-payment. It works today only because bmp-booking reaches this service through
     * Feign and service discovery, which bypasses the gateway entirely.
     *
     * <p>Through the gateway it would land on bmp-booking, which has no such handler — the same
     * bug class that hid the salon-reviews route and the whole of the stylist-profile namespace
     * in Session 49. It is a landmine for the first person who calls it from anywhere else.
     *
     * <p>(Those paths are not written out here on purpose: an Ant wildcard containing a star
     * followed by a slash ends a Javadoc block early, and this comment did exactly that until the
     * parser caught it.)
     *
     * <p>So a correctly-prefixed alias is added and is what {@code PaymentServiceClient} uses.
     * The old path is kept, not deleted, because deleting a route another service may already be
     * calling is a worse failure than an extra mapping.
     */
    @Operation(summary = "Create a payment order for a booking",
               description = "Freezes the commission split using the SALON'S OWN rate — not a "
                   + "platform constant. Idempotent: a retry returns the existing order rather "
                   + "than failing, because a retried booking must not fail for the customer.")
    @PostMapping({
        "/api/v1/payment-orders/booking/{bookingId}",   // correct prefix — routes to this service
        "/api/v1/bookings/{bookingId}/payment-order"    // legacy; see the note above
    })
    public ResponseEntity<PaymentOrderResponse> create(@PathVariable UUID bookingId,
                                                        @Valid @RequestBody CreatePaymentOrderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(bookingId, req));
    }

    @Operation(summary = "Get a payment order by id")
    @GetMapping("/api/v1/payment-orders/{paymentOrderId}")
    public PaymentOrderResponse getById(@PathVariable UUID paymentOrderId) {
        return service.getById(paymentOrderId);
    }

    @Operation(summary = "Get the payment order for a booking")
    @GetMapping("/api/v1/bookings/{bookingId}/payment-order")
    public PaymentOrderResponse getByBookingId(@PathVariable UUID bookingId) {
        return service.getByBookingId(bookingId);
    }

    /**
     * ⚠️ THE MOST DANGEROUS ENDPOINT IN THE PLATFORM. It marks a payment as captured.
     *
     * <p>It stands in for the Razorpay webhook that does not exist yet, which means it is the
     * only thing that can move a booking from PENDING to CONFIRMED. Until Session 29 it needed
     * no credential whatsoever — <b>on a public server, that is "book anything for free"</b>.
     *
     * <p>SERVICE-only now, by the class-level rule. That is necessary but not sufficient: it
     * should be removed entirely the moment the real webhook lands, because a manual override
     * on payment state is exactly the thing that gets left behind and rediscovered by someone
     * else. Tracked in {@code docs/PENDING_WORK.md}.
     */
    @Operation(
        summary = "[DEV ONLY] Manually set a payment order's status",
        description = "SERVICE role only. Feature-flagged (bmp.payment.allow-manual-status) — a stand-in for the Razorpay webhook that doesn't exist yet. DELETE THIS when the real webhook lands.")
    @PutMapping("/api/v1/payment-orders/{paymentOrderId}/status")
    public PaymentOrderResponse updateStatus(@PathVariable UUID paymentOrderId, @Valid @RequestBody UpdateStatusRequest req) {
        return service.updateStatusDevOnly(paymentOrderId, req);
    }
}
