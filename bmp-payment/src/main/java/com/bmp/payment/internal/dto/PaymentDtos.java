package com.bmp.payment.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/** BMP-26 DTOs — payment_schema.payment_order (Phase 1: data model only, no real Razorpay call). */
public final class PaymentDtos {
    private PaymentDtos() {}

    /**
     * amountPaise is accepted directly in this ticket's scope. Production version should derive
     * it from a live call to bmp-booking-service (Feign) instead of trusting the client —
     * TODO(Phase 3 / inter-service).
     */
    public record CreatePaymentOrderRequest(@NotNull long amountPaise) {}

    public record PaymentOrderResponse(
        UUID id, UUID bookingId, long amountPaise, long commissionPaise, long salonSharePaise,
        String razorpayOrderId, String status, Instant paymentCapturedAt, Instant createdAt
    ) {}

    /** DEV-ONLY. See PaymentOrderService javadoc — must not be reachable in production. */
    public record UpdateStatusRequest(@NotBlank String status) {}

    public record ErrorResponse(String error, String message) {}
}
