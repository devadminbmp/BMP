package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A photo or document in a support conversation. V009, Session 57.
 *
 * <h2>What this replaces</h2>
 * {@code support_message.attachment_url VARCHAR(500)} — one attachment, stored as a URL. Three
 * things were wrong with it, and each is why this is a table rather than a wider column:
 *
 * <ul>
 *   <li><b>One.</b> Somebody photographing a bad colour job sends three pictures, not one. The
 *       single column silently made the second and third impossible.</li>
 *   <li><b>A URL, not a key.</b> Everything else that holds an upload in this platform (salon and
 *       service photos, V015) stores a {@code storage_key} and signs a short-lived URL on read, so
 *       the object can stay private. A raw URL column quietly requires a public bucket — and a
 *       support thread contains exactly the things that must not be public: a customer's face, a
 *       receipt, a screenshot of a bank statement.</li>
 *   <li><b>No type or size.</b> A UI cannot render an image inline and a PDF as a chip without
 *       knowing which it is, and an unbounded size is the whole risk of an upload endpoint.</li>
 * </ul>
 *
 * <h2>content_type is what we DETECTED, not what was claimed</h2>
 * Set from magic-byte inspection, following the rule {@code ImageIngest} established in Session 42:
 * a file claiming to be a PNG is not a PNG. Trusting the client's {@code Content-Type} here would
 * let somebody store an executable that a support agent's browser is then invited to open.
 */
@Entity
@Table(name = "support_attachment", schema = "admin_schema")
@Getter
public class SupportAttachment {

    @Id
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    /**
     * Denormalised from the message. One join fewer on the busiest read in the console — "show me
     * everything attached to this ticket" — and it lets attachments be found if a message is ever
     * redacted.
     */
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    /** The object key. NOT a URL — reads sign one, so the bucket stays private. */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    /** What the sender called it. Shown for documents, ignored for images. */
    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "uploaded_by_type", nullable = false, length = 20)
    private String uploadedByType;

    @Column(name = "uploaded_by_id", nullable = false)
    private UUID uploadedById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SupportAttachment() {} // JPA

    public SupportAttachment(UUID messageId, UUID ticketId, String storageKey, String fileName,
                              String contentType, long sizeBytes,
                              String uploadedByType, UUID uploadedById) {
        this.id = UuidV7.generate();
        this.messageId = messageId;
        this.ticketId = ticketId;
        this.storageKey = storageKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedByType = uploadedByType;
        this.uploadedById = uploadedById;
        this.createdAt = Instant.now();
    }

    /** Images render inline in the thread; everything else becomes a download chip. */
    public boolean isImage() {
        return contentType != null && contentType.startsWith("image/");
    }
}
