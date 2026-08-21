package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Reported content (admin_schema.content_report, V003).
 *
 * <p>Generic on purpose: {@code content_type} + {@code content_id} rather than a table per
 * reportable thing, so adding "report a stylist photo" doesn't need a migration.
 *
 * <p>Upholding a report doesn't delete anything here — this service records the DECISION, and
 * the service that owns the content acts on it. A moderation table that also stores content
 * ends up as a second, diverging copy of it.
 */
@Entity
@Table(name = "content_report", schema = "admin_schema")
public class ContentReport {

    @Id
    private UUID id;

    /** review | salon_photo | salon_profile | stylist_profile */
    @Column(name = "content_type", nullable = false, length = 40)
    private String contentType;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(name = "salon_id")
    private UUID salonId;

    /** Null when raised internally rather than by a customer. */
    @Column(name = "reported_by_user_id")
    private UUID reportedByUserId;

    @Column(name = "reason", nullable = false, length = 60)
    private String reason;

    @Column(name = "detail")
    private String detail;

    /** open | upheld | dismissed */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ContentReport() {} // JPA

    public ContentReport(String contentType, UUID contentId, UUID salonId,
                         UUID reportedByUserId, String reason, String detail) {
        this.id = UuidV7.generate();
        this.contentType = contentType;
        this.contentId = contentId;
        this.salonId = salonId;
        this.reportedByUserId = reportedByUserId;
        this.reason = reason;
        this.detail = detail;
        this.status = "open";
        this.createdAt = Instant.now();
    }

    /**
     * One-way resolution.
     *
     * <p>A note is required by the service on BOTH outcomes, not just on upholding. "Dismissed"
     * with no reason is indistinguishable from "nobody looked at it", and the next person to see
     * the same content reported again has nothing to go on.
     */
    public void resolve(String status, UUID by, String note) {
        this.status = status;
        this.resolvedBy = by;
        this.resolutionNote = note;
        this.resolvedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getContentType() { return contentType; }
    public UUID getContentId() { return contentId; }
    public UUID getSalonId() { return salonId; }
    public UUID getReportedByUserId() { return reportedByUserId; }
    public String getReason() { return reason; }
    public String getDetail() { return detail; }
    public String getStatus() { return status; }
    public UUID getResolvedBy() { return resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolutionNote() { return resolutionNote; }
    public Instant getCreatedAt() { return createdAt; }
}
