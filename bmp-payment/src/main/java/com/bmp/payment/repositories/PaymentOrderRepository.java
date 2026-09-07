package com.bmp.payment.repositories;

import com.bmp.payment.entities.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {
    Optional<PaymentOrder> findByBookingId(UUID bookingId);
    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Session 50. The create path RETURNS the existing order rather than 409ing, so it needs the
     * row and not just a boolean — a retried booking must not fail for a customer who did
     * nothing wrong.
     */
    java.util.Optional<PaymentOrder> findByIdempotencyKey(String idempotencyKey);

    /** The webhook's lookup: which order does this gateway event refer to? */
    java.util.Optional<PaymentOrder> findByRazorpayOrderId(String razorpayOrderId);
}
