package com.bmp.salon.repositories;

import com.bmp.salon.entities.StylistJoinRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StylistJoinRequestRepository extends JpaRepository<StylistJoinRequest, UUID> {

    /** The owner's inbox — oldest first, because somebody has been waiting longest. */
    List<StylistJoinRequest> findBySalonIdAndStatusOrderByCreatedAtAsc(UUID salonId, String status);

    /** Everything an owner has ever decided at this salon, newest first. */
    List<StylistJoinRequest> findBySalonIdOrderByCreatedAtDesc(UUID salonId);

    /** Where a stylist has applied, newest first. */
    List<StylistJoinRequest> findByStylistIdOrderByCreatedAtDesc(UUID stylistId);

    /**
     * The live request for this pair, if any. Backed by the partial unique index in V020, so there
     * can never be more than one — {@code findFirst} is describing that guarantee, not hiding a
     * possible second row.
     */
    /**
     * A stylist's INBOX — invitations waiting on them. V028, Session 65.
     *
     * <p>Filtered on direction as well as status because the same table holds both halves of the
     * conversation: without it a stylist's inbox would also list the requests THEY sent, which are
     * waiting on somebody else and which they cannot accept.
     */
    List<StylistJoinRequest> findByStylistIdAndStatusAndDirectionOrderByCreatedAtDesc(
            UUID stylistId, String status, String direction);

    /** Any open conversation between this pair, whichever way it was proposed. Backs uq_join_request_one_open_per_pair. */
    Optional<StylistJoinRequest> findFirstByStylistIdAndSalonIdAndStatus(
            UUID stylistId, UUID salonId, String status);
}
