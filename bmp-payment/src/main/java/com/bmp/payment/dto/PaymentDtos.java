package com.bmp.payment.dto;

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
    public record CreatePaymentOrderRequest(
        @NotNull long amountPaise,
        /**
         * Whose salon share this is. Required from Session 50 — a payout needs to know who is
         * owed, and deriving it later means a cross-service join the architecture forbids.
         */
        UUID salonId,
        /**
         * The salon's OWN commission rate, from {@code salon_policy.commission_bps}.
         *
         * <p>Passed in by bmp-booking, which already loads that policy to freeze the
         * cancellation snapshot, rather than re-fetched here — one booking, one read of the
         * salon's terms, and the split can never disagree with the snapshot taken beside it.
         *
         * <p>Null falls back to the platform default and is logged as a fault, because a
         * missing rate means somebody's negotiated terms were not applied. It does NOT silently
         * become 12%: see PaymentOrderService.create.
         */
        Integer commissionBps,
        /** Our own reference (the booking ref) echoed to the gateway, so a stray gateway record
         *  can be traced back to a booking when something has gone wrong. */
        String receipt) {}

    public record PaymentOrderResponse(
        UUID id, UUID bookingId, long amountPaise, long commissionPaise, long salonSharePaise,
        String razorpayOrderId, String status, Instant paymentCapturedAt, Instant createdAt,
        // ---- Session 50 ----
        UUID salonId, Integer commissionBps, String gatewayPaymentId, String failureReason
    ) {}

    /** DEV-ONLY. See PaymentOrderService javadoc — must not be reachable in production. */
    public record UpdateStatusRequest(@NotBlank String status) {}

    public record ErrorResponse(String error, String message) {}
}
