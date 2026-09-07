package com.bmp.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** BMP-29 DTOs — bmp_staff / support_ticket / support_message / audit_log. */
public final class AdminDtos {
    private AdminDtos() {}

    // ---- staff ----
    public record CreateStaffRequest(@NotBlank String name, @NotBlank String phone, String email,
                                      @NotBlank String password, @NotBlank String role) {}
    public record StaffResponse(UUID id, String name, String phone, String email, String role,
                                 String status, Instant createdAt) {}
    public record StaffStatusRequest(@NotBlank String status) {}

    // ---- support ticket ----
    public record CreateTicketRequest(@NotBlank String raisedByType, UUID raisedById, UUID bookingId,
                                       @NotBlank String category, @NotBlank String subject) {}

    /**
     * A ticket raised BY A USER (customer, owner, manager, stylist), via bmp-user. Session 45.
     *
     * <p>Distinct from {@link CreateTicketRequest} rather than a superset of it, and the
     * difference is the point: every identity field here is filled in by the calling service
     * from a verified JWT, never by the person clicking the button. A record that can only be
     * built from trusted parts is easier to keep honest than one where some fields are trusted
     * and some aren't.
     *
     * @param raisedByType derived from the JWT role at bmp-user. If this were client-supplied a
     *                     customer could post {@code bmp_staff} and impersonate support.
     * @param raisedById   the JWT subject.
     * @param salonId      from the token for owner/manager/stylist; null for a customer. Lets an
     *                     owner see what their managers raised.
     * @param description  the first message of the thread. A ticket with only a subject makes an
     *                     agent's first action "what happened?", which burns the whole SLA window
     *                     on a question the user already knew the answer to.
     */
    /**
     * @param requesterName who is asking, and @param salonName which salon it concerns — SNAPSHOTS,
     *        supplied by the calling service (bmp-user), which already holds the verified identity
     *        from the JWT. Session 64.
     *
     *        Sent rather than looked up here for the reason given in V013: the queue lists fifty
     *        tickets at a time, and resolving names live would mean fifty cross-service calls per
     *        page load, any of which can fail — and a failed name is indistinguishable in the UI
     *        from a ticket with no name recorded.
     *
     *        Trustworthy because the caller is ROLE_SERVICE over the internal network, and
     *        raisedByType is already derived there from the JWT role rather than from the client.
     */
    public record RaiseTicketRequest(@NotBlank String raisedByType, UUID raisedById, UUID salonId,
                                      UUID bookingId, @NotBlank String category,
                                      @NotBlank String subject, @NotBlank String description,
                                      String requesterEmail, String requesterPhone,
                                      String requesterName, String salonName) {}

    /**
     * A user's reply to their own ticket.
     *
     * <p>{@code callerId}/{@code callerSalonId} come from bmp-user's verified JWT and are
     * re-checked against the ticket in bmp-admin. {@code senderType} is likewise derived, so a
     * customer cannot post a message that renders as though support wrote it.
     */
    public record UserReplyRequest(@NotNull UUID callerId, UUID callerSalonId,
                                    @NotBlank String senderType, @NotBlank String body) {}

    /** One message as the REQUESTER may see it — internal notes can never appear here. */
    public record ThreadMessage(UUID id, String senderType, String message, Instant createdAt) {}

    /**
     * A ticket as its raiser sees it.
     *
     * <p>Deliberately NOT {@link TicketResponse}: that one carries {@code assignedStaffId}, which
     * is internal routing, and its message list has no notion of internal notes. Two audiences,
     * two shapes — the alternative is one shape with a "hide some fields" flag, and a flag that
     * must be remembered is a leak waiting for the one call site that forgets.
     *
     * @param awaitingUs true when the ball is in BMP's court, so the UI can say "we're on it"
     *                   rather than leaving someone wondering whether it was received.
     */
    /**
     * @param handlingState machine-readable — QUEUED, ASSIGNED, ACTIVE, WAITING_ON_YOU,
     *                      TRANSFERRED, ESCALATED, RESOLVED, CLOSED. The app picks its icon and
     *                      colour from this, never from the label.
     * @param handlingLabel and @param handlingDetail — what the person actually reads. Session 64.
     *
     *   Added because the chat showed a customer their own message and nothing else: no indication
     *   that anyone had read it, been assigned, or handed it to another team. `status` said "open",
     *   which is true and useless — it says the same thing ninety seconds in and three days in.
     *
     *   Silence in a support chat is not neutral. People read it as being ignored, and the reliable
     *   consequence is a SECOND ticket about the same problem, which lengthens the queue and makes
     *   the wait worse for everyone. A status line is cheaper than the duplicate it prevents.
     *
     *   Every value is derived from stored facts (TicketHandlingState). There is deliberately no
     *   "an agent is typing" and no "connecting…" — we have no live socket, so both would be
     *   comforting animations with nothing behind them, and a status that turns out to be untrue is
     *   worse than no status at all.
     */
    public record MyTicketResponse(UUID id, String ticketRef, String category, String subject,
                                    String status, UUID bookingId, boolean awaitingUs,
                                    String handlingState, String handlingLabel, String handlingDetail,
                                    String handlingDesk,
                                    Instant createdAt, Instant resolvedAt,
                                    List<ThreadMessage> messages) {}
    public record TicketResponse(UUID id, String ticketRef, String raisedByType, UUID raisedById,
                                  UUID bookingId, String category, String subject, String status,
                                  String priority, UUID assignedStaffId, Instant createdAt,
                                  Instant resolvedAt, List<MessageResponse> messages) {}
    public record UpdateTicketRequest(String status, String priority, UUID assignedStaffId) {}

    public record CreateMessageRequest(@NotBlank String senderType, UUID senderId,
                                        @NotBlank String message, String attachmentUrl) {}
    public record MessageResponse(UUID id, UUID ticketId, String senderType, UUID senderId,
                                   String message, String attachmentUrl, Instant createdAt) {}

    // ---- audit log (read-only via API) ----
    public record AuditLogResponse(UUID id, String actorType, UUID actorId, String action,
                                    String entityType, UUID entityId, Map<String, Object> metadata,
                                    Instant createdAt) {}

    public record ErrorResponse(String error, String message) {}
}
