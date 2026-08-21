package com.bmp.admin.services;

import com.bmp.admin.dto.AdminDtos.*;
import com.bmp.admin.entities.SupportMessage;
import com.bmp.admin.entities.SupportTicket;
import com.bmp.admin.repositories.SupportMessageRepository;
import com.bmp.admin.repositories.SupportTicketRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** BMP-29: support_ticket + support_message CRUD. */
@Service
public class SupportTicketService {

    private final SupportTicketRepository tickets;
    private final SupportMessageRepository messages;
    private final PlatformSettingService settings;

    public SupportTicketService(SupportTicketRepository tickets, SupportMessageRepository messages,
                                PlatformSettingService settings) {
        this.tickets = tickets;
        this.messages = messages;
        this.settings = settings;
    }

    @Transactional
    public TicketResponse create(CreateTicketRequest req) {
        int year = Instant.now().atZone(ZoneOffset.UTC).getYear();
        String prefix = "TCK-" + year + "-";
        // NOTE: a real Postgres sequence per BMP-2's design note is the production approach;
        // this count-based generation is a simplification for Phase 1 CRUD and is not
        // safe under concurrent writes — replace with a DB sequence before go-live.
        long seq = tickets.countByTicketRefStartingWith(prefix) + 1;
        String ticketRef = prefix + String.format("%05d", seq);

        SupportTicket t = new SupportTicket(ticketRef, req.raisedByType(), req.raisedById(), req.bookingId(),
                req.category(), req.subject(), "open", "medium", null, null);

        // Session 23: start the SLA clock.
        //
        // Without this, first_response_due_at is null on every ticket, the breach query matches
        // nothing, and the console's "overdue" counter reads zero forever — while customers wait.
        // A metric that is structurally incapable of being non-zero is worse than no metric,
        // because people trust it.
        //
        // The window is configurable (platform_setting.support_first_response_hours) so it can
        // be tightened as the team grows without a deploy.
        long hours = settings.number(PlatformSettingService.SUPPORT_FIRST_RESPONSE_HOURS, 4);
        t.setFirstResponseDueAt(Instant.now().plus(hours, ChronoUnit.HOURS));

        t = tickets.save(t);
        return toResponse(t, List.of());
    }

    public TicketResponse getById(UUID id, boolean includeMessages) {
        SupportTicket t = tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND"));
        List<MessageResponse> msgs = includeMessages
                ? messages.findByTicketIdOrderByCreatedAtAsc(id).stream().map(this::toMessageResponse).toList()
                : List.of();
        return toResponse(t, msgs);
    }

    public List<TicketResponse> list(String status, UUID assignedStaffId) {
        List<SupportTicket> found;
        if (status != null && assignedStaffId != null) {
            found = tickets.findByStatusAndAssignedStaffId(status, assignedStaffId);
        } else if (status != null) {
            found = tickets.findByStatus(status);
        } else if (assignedStaffId != null) {
            found = tickets.findByAssignedStaffId(assignedStaffId);
        } else {
            found = tickets.findAll();
        }
        return found.stream().map(t -> toResponse(t, List.of())).toList();
    }

    @Transactional
    public TicketResponse update(UUID id, UpdateTicketRequest req) {
        SupportTicket t = tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND"));
        if (req.status() != null) {
            t.setStatus(req.status());
            if ("resolved".equals(req.status())) {
                t.setResolvedAt(Instant.now());
            }
        }
        if (req.priority() != null) t.setPriority(req.priority());
        if (req.assignedStaffId() != null) t.setAssignedStaffId(req.assignedStaffId());
        t.touch();
        return toResponse(t, List.of());
    }

    @Transactional
    public MessageResponse addMessage(UUID ticketId, CreateMessageRequest req) {
        tickets.findById(ticketId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND"));
        SupportMessage m = new SupportMessage(ticketId, req.senderType(), req.senderId(), req.message(), req.attachmentUrl());
        m = messages.save(m);
        return toMessageResponse(m);
    }

    private TicketResponse toResponse(SupportTicket t, List<MessageResponse> msgs) {
        return new TicketResponse(t.getId(), t.getTicketRef(), t.getRaisedByType(), t.getRaisedById(),
                t.getBookingId(), t.getCategory(), t.getSubject(), t.getStatus(), t.getPriority(),
                t.getAssignedStaffId(), t.getCreatedAt(), t.getResolvedAt(), msgs);
    }

    private MessageResponse toMessageResponse(SupportMessage m) {
        return new MessageResponse(m.getId(), m.getTicketId(), m.getSenderType(), m.getSenderId(),
                m.getMessageText(), m.getAttachmentUrl(), m.getCreatedAt());
    }
}
