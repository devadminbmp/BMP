package com.bmp.admin.repositories;

import com.bmp.admin.entities.ContentReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContentReportRepository extends JpaRepository<ContentReport, UUID> {

    /** Oldest first — a moderation queue is worked in the order things were reported. */
    List<ContentReport> findByStatusOrderByCreatedAtAsc(String status);

    List<ContentReport> findAllByOrderByCreatedAtDesc();

    long countByStatus(String status);

    /**
     * Everything reported about one piece of content.
     *
     * <p>Three complaints about the same review is a different decision from one, and a
     * moderator should see that before dismissing it.
     */
    List<ContentReport> findByContentTypeAndContentId(String contentType, UUID contentId);

    /**
     * Has this person already reported this item? Session 61.
     *
     * <p>Backs the per-reporter idempotency in {@code InternalContentReportController}: one open
     * report per person per item, so a double tap or the same review reported from two screens does
     * not put two items in front of a moderator.
     *
     * <p>Deliberately NOT de-duplicated across users — how many DIFFERENT people reported something
     * is the most useful triage signal there is.
     */
    List<ContentReport> findByContentIdAndReportedByUserId(UUID contentId, UUID reportedByUserId);

    /** Everything about one item, newest first — for the "you already reported this" check. */
    List<ContentReport> findByContentIdOrderByCreatedAtDesc(UUID contentId);
}
