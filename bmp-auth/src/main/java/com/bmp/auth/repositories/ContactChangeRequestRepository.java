package com.bmp.auth.repositories;

import com.bmp.auth.entities.ContactChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** V006, Session 65. See {@link ContactChangeRequest} for why this is not otp_requests. */
public interface ContactChangeRequestRepository extends JpaRepository<ContactChangeRequest, UUID> {

    /**
     * The newest request for this user, consumed or not.
     *
     * <p>Deliberately NOT filtered to unconsumed rows. Verifying against "the newest unconsumed
     * one" would silently skip past a code that was just used and match an older one still inside
     * its TTL — which is replay by another name. Fetching the newest and then checking its state
     * means a used code fails as used, which is both correct and the message the person needs.
     */
    Optional<ContactChangeRequest> findTopByUserIdOrderByCreatedAtDesc(UUID userId);
}
