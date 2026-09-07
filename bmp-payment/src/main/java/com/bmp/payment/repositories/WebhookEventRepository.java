package com.bmp.payment.repositories;

import com.bmp.payment.entities.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    /**
     * Have we already processed this gateway event? Session 50.
     *
     * <p>The fast path for the common case. It is NOT the guarantee — two deliveries arriving
     * concurrently both pass this check and both proceed. The UNIQUE index on
     * {@code razorpay_event_id} is what actually prevents double-application, and
     * {@code WebhookService} relies on the insert failing rather than on this returning true.
     *
     * <p>Gateways deliver at least once by design, so this is an ordinary event, not an edge case.
     */
    boolean existsByRazorpayEventId(String razorpayEventId);
}
