package com.bmp.admin.dto;

import jakarta.validation.constraints.NotBlank;
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
