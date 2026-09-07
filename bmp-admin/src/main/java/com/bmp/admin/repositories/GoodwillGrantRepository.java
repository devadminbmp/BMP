package com.bmp.admin.repositories;

import com.bmp.admin.entities.GoodwillGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Non-refund goodwill already given on a booking. V012, Session 59. */
public interface GoodwillGrantRepository extends JpaRepository<GoodwillGrant, UUID> {

    /**
     * What has already gone out on this booking, excluding refunds.
     *
     * <p>{@code COALESCE} so an untouched booking returns 0 rather than null — the caller subtracts
     * this from what the customer paid, and a null there would make the whole expression null and
     * the comparison silently false, which fails OPEN. A cap that fails open on the most common
     * input is worse than no cap, because it looks enforced.
     */
    @Query("select coalesce(sum(g.valuePaise), 0) from GoodwillGrant g where g.bookingId = :bookingId")
    long totalForBooking(@Param("bookingId") UUID bookingId);

    /** For showing a customer's goodwill history on the support console. Newest first. */
    List<GoodwillGrant> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    boolean existsByApprovalRequestId(UUID approvalRequestId);
}
