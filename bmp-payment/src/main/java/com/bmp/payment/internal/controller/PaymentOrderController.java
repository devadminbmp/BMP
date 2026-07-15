package com.bmp.payment.internal.controller;

import com.bmp.payment.internal.dto.PaymentDtos.*;
import com.bmp.payment.internal.service.PaymentOrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** BMP-26: payment_order CRUD — data model only, no real Razorpay call yet. */
@RestController
public class PaymentOrderController {

    private final PaymentOrderService service;

    public PaymentOrderController(PaymentOrderService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/bookings/{bookingId}/payment-order")
    public ResponseEntity<PaymentOrderResponse> create(@PathVariable UUID bookingId,
                                                        @Valid @RequestBody CreatePaymentOrderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(bookingId, req));
    }

    @GetMapping("/api/v1/payment-orders/{paymentOrderId}")
    public PaymentOrderResponse getById(@PathVariable UUID paymentOrderId) {
        return service.getById(paymentOrderId);
    }

    @GetMapping("/api/v1/bookings/{bookingId}/payment-order")
    public PaymentOrderResponse getByBookingId(@PathVariable UUID bookingId) {
        return service.getByBookingId(bookingId);
    }

    /** DEV-ONLY — see PaymentOrderService.updateStatusDevOnly javadoc. */
    @PutMapping("/api/v1/payment-orders/{paymentOrderId}/status")
    public PaymentOrderResponse updateStatus(@PathVariable UUID paymentOrderId, @Valid @RequestBody UpdateStatusRequest req) {
        return service.updateStatusDevOnly(paymentOrderId, req);
    }
}
