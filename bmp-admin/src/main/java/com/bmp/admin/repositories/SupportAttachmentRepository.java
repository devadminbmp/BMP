package com.bmp.admin.repositories;

import com.bmp.admin.entities.SupportAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Media in a support thread. Session 57. Backed by idx_attachment_message / idx_attachment_ticket. */
public interface SupportAttachmentRepository extends JpaRepository<SupportAttachment, UUID> {

    /** Rendered under the message that carried them, in the order they were sent. */
    List<SupportAttachment> findByMessageIdOrderByCreatedAtAsc(UUID messageId);

    /**
     * Everything attached to one ticket.
     *
     * <p>Ticket-scoped rather than assembled from the thread's messages: the console shows an
     * "all attachments" strip so an agent can find the photo without scrolling a long conversation,
     * and doing that per-message would be a query per row.
     */
    List<SupportAttachment> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);

    /** For a size report, and for the orphan sweep that MinIO still needs. */
    long countByTicketId(UUID ticketId);
}
