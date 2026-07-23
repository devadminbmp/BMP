package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for admin_schema.support_message.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "support_message", schema = "admin_schema")
public class SupportMessage {

    @Id
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;
    @Column(name = "sender_type", nullable = false, length = 20)
    private String senderType;
    @Column(name = "sender_id", nullable = false)
    private UUID senderId;
    @Column(name = "message_text", nullable = false)
    private String messageText;
    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SupportMessage() {} // JPA

    public SupportMessage(UUID ticketId, String senderType, UUID senderId, String messageText, String attachmentUrl) {
        this.id = UuidV7.generate();
        this.ticketId = ticketId;
        this.senderType = senderType;
        this.senderId = senderId;
        this.messageText = messageText;
        this.attachmentUrl = attachmentUrl;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTicketId() { return ticketId; }
    public String getSenderType() { return senderType; }
    public UUID getSenderId() { return senderId; }
    public String getMessageText() { return messageText; }
    public String getAttachmentUrl() { return attachmentUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
