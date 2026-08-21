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
}
