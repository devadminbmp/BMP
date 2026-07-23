package com.bmp.payment.controllers;

import com.bmp.payment.dto.PaymentDtos.*;
import com.bmp.payment.services.PaymentOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** BMP-26: payment_order CRUD — data model only, no real Razorpay call yet. */
@Tag(name = "Payments", description = "payment_order CRUD, 12% commission split. No real payment gateway wired yet — see the dev-only manual status endpoint below.")
@RestController
public class PaymentOrderController {

    private final PaymentOrderService service;

    public PaymentOrderController(PaymentOrderService service) {
        this.service = service;
    }

    @Operation(summary = "Create a payment order for a booking", description = "Computes the 12% platform commission split at creation time.")
    @PostMapping("/api/v1/bookings/{bookingId}/payment-order")
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

    @Operation(
        summary = "[DEV ONLY] Manually set a payment order's status",
        description = "Feature-flagged (bmp.payment.allow-manual-status) — a stand-in for the real payment gateway webhook that doesn't exist yet. Do NOT enable this anywhere real money is involved.")
    @PutMapping("/api/v1/payment-orders/{paymentOrderId}/status")
    public PaymentOrderResponse updateStatus(@PathVariable UUID paymentOrderId, @Valid @RequestBody UpdateStatusRequest req) {
        return service.updateStatusDevOnly(paymentOrderId, req);
    }
}
