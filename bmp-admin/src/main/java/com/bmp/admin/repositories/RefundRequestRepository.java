package com.bmp.admin.repositories;

import com.bmp.admin.entities.RefundRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {

    List<RefundRequest> findByStatusOrderByCreatedAtDesc(String status);

    List<RefundRequest> findAllByOrderByCreatedAtDesc();

    /**
     * An existing open request for this booking.
     *
     * <p>Backed by a partial unique index (V005), but checked here too so the customer gets
     * "there's already a request for this booking" rather than a constraint violation. Two
     * agents working the same complaint is normal, not exceptional.
     */
    Optional<RefundRequest> findFirstByBookingIdAndStatusIn(UUID bookingId, List<String> statuses);

    long countByStatusIn(List<String> statuses);
}
