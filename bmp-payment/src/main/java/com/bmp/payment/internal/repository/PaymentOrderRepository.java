package com.bmp.payment.internal.repository;

import com.bmp.payment.internal.entity.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {
    Optional<PaymentOrder> findByBookingId(UUID bookingId);
    boolean existsByIdempotencyKey(String idempotencyKey);
}
