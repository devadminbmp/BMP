package com.bmp.salon.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.UUID;

/**
 * Tells bmp-admin that a salon needs reviewing.
 *
 * <p>Called when a salon is created. Salons are created {@code pending} and are invisible to
 * customers until a human approves them — so if this call never happens, the salon is stranded:
 * not in anyone's queue, not visible to anyone, and indistinguishable (to the owner) from being
 * ignored.
 *
 * <p><b>Why a direct call rather than an outbox event.</b> The outbox → Kafka path in bmp-common
 * is the right mechanism for things that must eventually happen, and moderation arguably
 * qualifies. But it adds a consumer, a topic and a failure mode to a queue that gets checked by
 * a human anyway — and the endpoint is idempotent, so a missed call is recoverable by re-posting
 * it. If the queue ever needs stronger delivery guarantees than "a person notices", this is the
 * first thing to move onto the outbox.
 */
@FeignClient(name = "bmp-admin-service", configuration = com.bmp.salon.config.FeignInternalKeyConfig.class)
public interface AdminServiceClient {

    /** Idempotent — a repeated call returns the existing review rather than creating a second. */
    @PostMapping("/api/v1/admin/salons/{salonId}/enqueue-review")
    void enqueueSalonReview(@PathVariable("salonId") UUID salonId);
}
