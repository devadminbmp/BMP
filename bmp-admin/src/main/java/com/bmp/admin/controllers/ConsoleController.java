package com.bmp.admin.controllers;

import com.bmp.admin.dto.ConsoleDtos.*;
import com.bmp.admin.repositories.AuditLogRepository;
import com.bmp.admin.repositories.SupportTicketRepository;
import com.bmp.admin.security.StaffPrincipal;
import com.bmp.admin.client.BookingServiceClient;
import com.bmp.admin.services.ConsoleUserService;
import com.bmp.admin.services.DataRequestService;
import com.bmp.admin.services.PlatformSettingService;
import com.bmp.admin.services.SalonModerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Everything the console reads and writes, apart from auth, staff and coupons.
 *
 * <p>Grouped into one controller because these are all thin — authenticate, delegate, return.
 * Splitting five 20-line controllers across five files would be filing rather than structure.
 * The interesting logic lives in the services, each of which owns one decision.
 *
 * <h2>Authorization is per-endpoint AND re-checked in the services</h2>
 * The annotations here say "you must be some kind of staff"; the services enforce the finer
 * rules (a support agent may view a data request but not fulfil one). Neither replaces the
 * other — an annotation can't express "only if identity has been verified".
 */
@Tag(name = "Console", description = "Ops overview, salon moderation, customer lookup, data requests and the audit log.")
@RestController
@RequestMapping("/api/v1/admin")
public class ConsoleController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ConsoleController.class);

    private final SalonModerationService moderation;
    private final ConsoleUserService userService;
    private final DataRequestService dataRequests;
    private final SupportTicketRepository tickets;
    private final AuditLogRepository auditRepo;
    private final com.bmp.admin.services.PlatformSettingService settings;
    private final com.bmp.admin.services.RefundService refunds;
    private final com.bmp.admin.services.AuditLogService auditLog;
    private final com.bmp.admin.repositories.ContentReportRepository contentReports;
    private final com.bmp.admin.client.BookingServiceClient bookingClient;

    public ConsoleController(SalonModerationService moderation, ConsoleUserService userService,
                             DataRequestService dataRequests, SupportTicketRepository tickets,
                             AuditLogRepository auditRepo,
                             com.bmp.admin.services.PlatformSettingService settings,
                             com.bmp.admin.services.RefundService refunds,
                             com.bmp.admin.services.AuditLogService auditLog,
                             com.bmp.admin.repositories.ContentReportRepository contentReports,
                             com.bmp.admin.client.BookingServiceClient bookingClient) {
        this.moderation = moderation;
        this.userService = userService;
        this.dataRequests = dataRequests;
        this.tickets = tickets;
        this.auditRepo = auditRepo;
        this.settings = settings;
        this.refunds = refunds;
        this.auditLog = auditLog;
        this.contentReports = contentReports;
        this.bookingClient = bookingClient;
    }

    // ---- ops overview ------------------------------------------------------------------------

    @Operation(
        summary = "What needs a person today",
        description = "Every number here is a QUEUE, not a metric — the console makes each one clickable. A dashboard full of figures nobody acts on trains people to ignore the dashboard. Session 23: all seven are now real; four of them used to be hardcoded zeros.")
    @GetMapping("/ops/summary")
    @PreAuthorize("isAuthenticated()")
    public OpsSummaryResponse opsSummary() {
        // Today's bookings comes from another service, so it's the one number that can fail.
        // -1 means "couldn't check" rather than "none" — a zero here would read as a dead
        // platform and send someone looking for an outage that isn't there.
        long bookingsToday;
        try {
            bookingsToday = bookingClient.countToday();
        } catch (Exception e) {
            log.warn("Could not read today's booking count ({})", e.toString());
            bookingsToday = -1;
        }

        return new OpsSummaryResponse(
                moderation.pendingCount(),
                tickets.countByStatusNotIn(List.of("resolved", "closed")),
                tickets.countBreachingSla(java.time.Instant.now()),
                dataRequests.outstandingCount(),
                contentReports.countByStatus("open"),
                bookingsToday,
                settings.flag(PlatformSettingService.NEW_BOOKINGS_ENABLED, true));
    }

    // ---- platform settings -------------------------------------------------------------------

    @Operation(summary = "Platform settings", description = "Feature flags and the booking kill switch.")
    @GetMapping("/settings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN')")
    public SettingsListResponse listSettings() {
        return new SettingsListResponse(settings.list());
    }

    @Operation(
        summary = "Change a setting",
        description = "Ops and superadmin only, and a justification is required. `new_bookings_enabled` is the kill switch: it stops customers making NEW bookings platform-wide without touching any that already exist. Changes are audited, and the kill switch additionally logs at WARN — \"when did bookings stop and who stopped them\" is the first question in an incident review.")
    @PutMapping("/settings/{key}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN')")
    public SettingResponse updateSetting(@PathVariable String key,
                                          @Valid @RequestBody SettingChangeRequest req,
                                          @AuthenticationPrincipal StaffPrincipal caller,
                                          HttpServletRequest http) {
        return settings.update(key, req, caller, clientIp(http));
    }

    // ---- account recovery ---------------------------------------------------------------------

    @Operation(
        summary = "Clear an OTP lockout",
        description = "Removes the barrier so the customer can request a code themselves. Deliberately does NOT send one — keeping the two separate means an agent can help someone locked out without triggering a login the customer didn't ask for. Requires a justification: unlocking on request is exactly what a social engineer asks for.")
    @PostMapping("/users/{userId}/unlock")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    public ResponseEntity<Void> unlock(@PathVariable UUID userId,
                                        @Valid @RequestBody JustifiedActionRequest req,
                                        @AuthenticationPrincipal StaffPrincipal caller,
                                        HttpServletRequest http) {
        userService.unlock(userId, req, caller, clientIp(http));
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Re-send a login code",
        description = "To the address ALREADY on the account. There is no destination parameter here or in bmp-auth — redirecting a login code is account takeover with extra steps, and the way to guarantee it can't happen is for the capability not to exist.")
    @PostMapping("/users/{userId}/resend-otp")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    public ResponseEntity<Void> resendOtp(@PathVariable UUID userId,
                                           @Valid @RequestBody JustifiedActionRequest req,
                                           @AuthenticationPrincipal StaffPrincipal caller,
                                           HttpServletRequest http) {
        userService.resendLoginCode(userId, req, caller, clientIp(http));
        return ResponseEntity.noContent().build();
    }

    // ---- bookings ------------------------------------------------------------------------------

    @Operation(summary = "Find a booking", description = "By reference. Not a free-text search over customer bookings — browsing customer records is what an internal console shouldn't make easy.")
    @GetMapping("/bookings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT','FINANCE_ADMIN','READ_ONLY')")
    public List<BookingServiceClient.SupportBooking> findBookings(@RequestParam("q") String query) {
        return bookingClient.search(query);
    }

    @Operation(summary = "A booking's event trail", description = "Append-only. Settles most disputes without anyone having to be believed.")
    @GetMapping("/bookings/{bookingId}/events")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT','FINANCE_ADMIN')")
    public List<BookingServiceClient.BookingEvent> bookingEvents(@PathVariable UUID bookingId) {
        return bookingClient.events(bookingId);
    }

    @Operation(
        summary = "Cancel on the customer's behalf",
        description = "The state machine models cancellation as a CUSTOMER action — there is no staff-actor cancel — so this acts as the customer. That's exactly why a reason is required and audited: the entry is the only thing distinguishing \"cancelled at the customer's request\" from \"cancelled somebody's appointment\".")
    @PostMapping("/bookings/{bookingId}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    public ResponseEntity<Void> cancelBooking(@PathVariable UUID bookingId,
                                               @Valid @RequestBody JustifiedActionRequest req,
                                               @AuthenticationPrincipal StaffPrincipal caller,
                                               HttpServletRequest http) {
        bookingClient.cancel(bookingId, new BookingServiceClient.CancelRequest(req.justification()));
        auditLog.record("bmp_staff", caller.staffId(), "BOOKING_CANCELLED_BY_STAFF", "booking", bookingId,
                java.util.Map.of(), clientIp(http), caller.email(), caller.role(), req.justification());
        return ResponseEntity.noContent().build();
    }

    // ---- refunds --------------------------------------------------------------------------------

    @Operation(summary = "Refund requests", description = "Newest first. Nothing can be PAID until payments exist — approved requests sit in `blocked`, which is not a rejection.")
    @GetMapping("/refunds")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT','FINANCE_ADMIN')")
    public List<com.bmp.admin.entities.RefundRequest> listRefunds(@RequestParam(required = false) String status) {
        return refunds.list(status);
    }

    @Operation(summary = "Raise a refund request", description = "Needs a reason and cannot exceed the booking total. One open request per booking — two agents on the same complaint get a sentence, not a constraint violation.")
    @PostMapping("/refunds")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    public ResponseEntity<com.bmp.admin.entities.RefundRequest> requestRefund(
            @RequestBody java.util.Map<String, Object> body,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        UUID bookingId = UUID.fromString(String.valueOf(body.get("bookingId")));
        Long amount = body.get("amountPaise") == null ? null
                : Long.valueOf(String.valueOf(body.get("amountPaise")));
        String reason = String.valueOf(body.getOrDefault("reason", ""));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(refunds.request(bookingId, amount, reason, caller, clientIp(http)));
    }

    @Operation(
        summary = "Approve or reject a refund",
        description = "Finance, ops or superadmin. **You cannot approve a refund you raised** — \"two people saw this\" is the cheapest control over money there is, and the day one account is compromised it's the only thing between an attacker and the refund queue. Approval lands in `blocked` until payments exist.")
    @PostMapping("/refunds/{refundId}/decision")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','FINANCE_ADMIN')")
    public com.bmp.admin.entities.RefundRequest decideRefund(
            @PathVariable UUID refundId,
            @RequestBody java.util.Map<String, String> body,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        return refunds.decide(refundId, body.get("decision"), body.get("note"), caller, clientIp(http));
    }

    // ---- content reports --------------------------------------------------------------------------

    @Operation(summary = "Reported content", description = "Open reports oldest-first — a moderation queue is worked in the order things were reported.")
    @GetMapping("/content-reports")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    public List<com.bmp.admin.entities.ContentReport> listContentReports(
            @RequestParam(required = false) String status) {
        return status == null || status.isBlank()
                ? contentReports.findAllByOrderByCreatedAtDesc()
                : contentReports.findByStatusOrderByCreatedAtAsc(status);
    }

    @Operation(
        summary = "Uphold or dismiss a report",
        description = "A note is required on BOTH outcomes. \"Dismissed\" with no reason is indistinguishable from \"nobody looked at it\", and the next moderator seeing the same content reported again has nothing to go on. Upholding records the decision here; the service that owns the content acts on it.")
    @PostMapping("/content-reports/{reportId}/resolve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    public com.bmp.admin.entities.ContentReport resolveReport(
            @PathVariable UUID reportId,
            @RequestBody java.util.Map<String, String> body,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {

        String status = body.get("status");
        String note = body.get("note");
        if (!List.of("upheld", "dismissed").contains(status)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "status must be upheld or dismissed");
        }
        if (note == null || note.trim().length() < 10) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Record why — including when dismissing.");
        }

        com.bmp.admin.entities.ContentReport report = contentReports.findById(reportId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND"));
        report.resolve(status, caller.staffId(), note);
        contentReports.save(report);

        auditLog.record("bmp_staff", caller.staffId(), "CONTENT_REPORT_" + status.toUpperCase(),
                report.getContentType(), report.getContentId(),
                java.util.Map.of("reportId", reportId.toString()),
                clientIp(http), caller.email(), caller.role(), note);

        // TODO(bmp-review / bmp-salon): upholding should also hide the content. Recording the
        // decision without acting on it means a moderator marks something as removed and it
        // stays visible — flagged loudly rather than left to be discovered.
        return report;
    }

    // ---- salon moderation ---------------------------------------------------------------------

    @Operation(summary = "The salon approval queue", description = "Pending oldest-first; anything else newest-first.")
    @GetMapping("/salons/reviews")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT','FINANCE_ADMIN','READ_ONLY')")
    public List<SalonReviewResponse> listSalonReviews(@RequestParam(required = false) String status) {
        return moderation.list(status);
    }

    @Operation(
        summary = "Approve, reject or suspend a salon",
        description = "Rejection REQUIRES a note — 'no' without a reason produces a support ticket every single time, and the owner has no idea what to fix. The checks you tick are recorded against your name.")
    @PostMapping("/salons/reviews/{reviewId}/decision")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN')")
    public SalonReviewResponse decideSalon(
            @PathVariable UUID reviewId,
            @Valid @RequestBody SalonDecisionRequest req,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        return moderation.decide(reviewId, req, caller, clientIp(http));
    }

    @Operation(summary = "[internal] Put a salon in the review queue", description = "Called by bmp-salon when a salon is created. Idempotent — whatever triggers it will eventually fire twice.")
    @PostMapping("/salons/{salonId}/enqueue-review")
    @PreAuthorize("hasRole('SERVICE')")
    public SalonReviewResponse enqueue(@PathVariable UUID salonId) {
        return moderation.enqueue(salonId);
    }

    // ---- customers -----------------------------------------------------------------------------

    @Operation(
        summary = "Find a customer",
        description = "SEARCH ONLY — there is deliberately no browsable list of customers. A console you can page through invites idle browsing of personal data, which is what can't be defended afterwards. The search itself is audited.")
    @GetMapping("/users")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT','FINANCE_ADMIN','READ_ONLY')")
    public List<UserSummaryResponse> searchUsers(@RequestParam("q") String query,
                                                  @AuthenticationPrincipal StaffPrincipal caller) {
        return userService.search(query, caller);
    }

    @Operation(
        summary = "Reveal one masked field",
        description = "Returns exactly one field and writes an audit entry naming you, the customer, the field and your reason. Not a 'show everything' toggle: the narrower the request, the more meaningful the record.")
    @PostMapping("/users/{userId}/reveal")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    public RevealPiiResponse reveal(@PathVariable UUID userId,
                                     @Valid @RequestBody RevealPiiRequest req,
                                     @AuthenticationPrincipal StaffPrincipal caller,
                                     HttpServletRequest http) {
        return userService.reveal(userId, req, caller, clientIp(http));
    }

    @Operation(
        summary = "Why can't this person sign in?",
        description = "PARTIAL: account-level facts are real; OTP lockout and email-delivery state need internal endpoints on bmp-auth and bmp-notification that don't exist yet, and come back empty rather than invented. An agent told 'not locked' by a system that doesn't know will confidently tell a customer the wrong thing.")
    @GetMapping("/users/{userId}/account-health")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    public AccountHealthResponse accountHealth(@PathVariable UUID userId,
                                                @AuthenticationPrincipal StaffPrincipal caller) {
        return userService.accountHealth(userId, caller);
    }

    // ---- data requests (DPDP) ---------------------------------------------------------------------

    @Operation(summary = "Data subject requests", description = "Soonest statutory deadline first.")
    @GetMapping("/data-requests")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    public List<DataRequestResponse> listDataRequests(@RequestParam(required = false) String status) {
        return dataRequests.list(status);
    }

    @Operation(summary = "Raise a data request", description = "Usually on behalf of a customer who asked by email or through support.")
    @PostMapping("/data-requests")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    public ResponseEntity<DataRequestResponse> createDataRequest(
            @Valid @RequestBody CreateDataRequestRequest req,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(dataRequests.create(req, caller, clientIp(http)));
    }

    @Operation(summary = "Confirm the requester's identity", description = "Must happen before fulfilment. Record HOW you confirmed it — that note is the evidence.")
    @PostMapping("/data-requests/{id}/verify-identity")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN')")
    public DataRequestResponse verifyIdentity(@PathVariable UUID id,
                                               @RequestBody DataRequestActionRequest req,
                                               @AuthenticationPrincipal StaffPrincipal caller,
                                               HttpServletRequest http) {
        return dataRequests.verifyIdentity(id, req, caller, clientIp(http));
    }

    @Operation(
        summary = "Fulfil",
        description = "Refuses unless identity is verified — acting on a forged erasure request is itself a breach. PARTIAL: deletion currently deactivates the account; full anonymisation (keeping booking records, removing what identifies the person) is not built, and the note records exactly what was done.")
    @PostMapping("/data-requests/{id}/complete")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN')")
    public DataRequestResponse completeDataRequest(@PathVariable UUID id,
                                                    @RequestBody DataRequestActionRequest req,
                                                    @AuthenticationPrincipal StaffPrincipal caller,
                                                    HttpServletRequest http) {
        return dataRequests.complete(id, req, caller, clientIp(http));
    }

    @Operation(summary = "Reject", description = "Needs a reason — the requester is entitled to know why.")
    @PostMapping("/data-requests/{id}/reject")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN')")
    public DataRequestResponse rejectDataRequest(@PathVariable UUID id,
                                                  @RequestBody DataRequestActionRequest req,
                                                  @AuthenticationPrincipal StaffPrincipal caller,
                                                  HttpServletRequest http) {
        return dataRequests.reject(id, req, caller, clientIp(http));
    }

    // ---- audit -------------------------------------------------------------------------------------

    @Operation(
        summary = "The audit log",
        description = "Read-only, and enforced at the database level — V002 revokes UPDATE and DELETE on this table. An audit log the application can edit is not an audit log. Making it visible IS the control: the deterrent against misuse of a console isn't permissions (staff need the access) but a named, reasoned entry colleagues can read.")
    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','FINANCE_ADMIN')")
    public List<AuditEntryResponse> audit(@RequestParam(required = false) String action,
                                           @RequestParam(required = false) String actor) {
        return auditRepo.findAll().stream()
                .filter(a -> action == null || action.equalsIgnoreCase(a.getAction()))
                .filter(a -> actor == null || (a.getActorEmail() != null
                        && a.getActorEmail().toLowerCase().contains(actor.toLowerCase())))
                .sorted((x, y) -> y.getCreatedAt().compareTo(x.getCreatedAt()))
                // TODO(scale): this reads the whole table and filters in memory, which is fine
                // at current volume and will not be. Move to a paged query with the indexes
                // V003 already created before the log has real history in it.
                .limit(500)
                .map(a -> new AuditEntryResponse(
                        a.getId(), a.getActorEmail(), a.getActorRole(), a.getAction(),
                        a.getEntityType(), a.getEntityId(), a.getJustification(),
                        a.getIpAddress(), a.getCreatedAt()))
                .toList();
    }

    /**
     * The caller's IP, for the audit trail.
     *
     * <p>Trusts {@code X-Forwarded-For} only because nothing security-relevant depends on it:
     * it's recorded for investigation, never used to grant access, so a spoofed header misleads
     * a reader rather than bypassing a control. TODO(infra): trusted-proxy list.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
