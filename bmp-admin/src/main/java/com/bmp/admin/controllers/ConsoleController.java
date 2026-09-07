package com.bmp.admin.controllers;

import com.bmp.admin.dto.ConsoleDtos.*;
import com.bmp.admin.repositories.AuditLogRepository;
import com.bmp.admin.repositories.SupportTicketRepository;
import com.bmp.admin.security.StaffPrincipal;
import com.bmp.admin.client.BookingServiceClient;
import com.bmp.admin.client.SalonServiceClient;
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
import org.springframework.web.server.ResponseStatusException;

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
    /** Session 46 — the moderation preview: what a customer would actually see. */
    private final SalonServiceClient salonClient;
    /** Session 60 — so upholding a report actually takes the review down. See enactModeration. */
    private final com.bmp.admin.client.ReviewServiceClient reviewClient;
    /** Session 65 — the salon-help row masks the owner's phone. Support reveals it separately, audited. */
    private final com.bmp.admin.services.PiiMasker masker;

    public ConsoleController(SalonModerationService moderation, ConsoleUserService userService,
                             DataRequestService dataRequests, SupportTicketRepository tickets,
                             AuditLogRepository auditRepo,
                             com.bmp.admin.services.PlatformSettingService settings,
                             com.bmp.admin.services.RefundService refunds,
                             com.bmp.admin.services.AuditLogService auditLog,
                             com.bmp.admin.repositories.ContentReportRepository contentReports,
                             com.bmp.admin.client.BookingServiceClient bookingClient,
                             SalonServiceClient salonClient,
                             com.bmp.admin.client.ReviewServiceClient reviewClient,
                             com.bmp.admin.services.PiiMasker masker) {
        this.masker = masker;
        this.salonClient = salonClient;
        this.reviewClient = reviewClient;
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    public SettingsListResponse listSettings() {
        return new SettingsListResponse(settings.list());
    }

    @Operation(
        summary = "Change a setting",
        description = "Ops and superadmin only, and a justification is required. `new_bookings_enabled` is the kill switch: it stops customers making NEW bookings platform-wide without touching any that already exist. Changes are audited, and the kill switch additionally logs at WARN — \"when did bookings stop and who stopped them\" is the first question in an incident review.")
    @PutMapping("/settings/{key}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
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
    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  ACCOUNT ADMINISTRATION — scoped by whose account it is. Session 65.
    // ══════════════════════════════════════════════════════════════════════════════════════════
    /*
     *   support agent / lead  →  customers only
     *   ops admin             →  customers, salon-side people, support staff
     *   super admin           →  anyone, including other admins
     *
     * The rule lives in AccountScope and the work in ConsoleUserService, which already holds the
     * user client, the PII masker and the audit logger. `@PreAuthorize` here only proves the caller
     * is staff at all — it cannot express "may act on THIS account", because that depends on the
     * TARGET's role, which is unknown until the user is loaded.
     */
    @PostMapping("/users/{userId}/clear-otp-lockout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> clearOtpLockout(@PathVariable UUID userId,
                                                 @Valid @RequestBody AccountActionRequest req,
                                                 @AuthenticationPrincipal StaffPrincipal caller) {
        userService.clearOtpLockout(userId, req.reason(), caller);
        return ResponseEntity.noContent().build();
    }

    public record ChangeContactRequest(String phone, String email,
                                        @jakarta.validation.constraints.NotBlank String reason) {}

    public record AccountActionRequest(@jakarta.validation.constraints.NotBlank String reason) {}

    @Operation(summary = "Change a user's phone and/or email",
               description = "Changing the phone changes who can log in. Support may do this for customers; ops for staff; the owner for anyone.")
    @PatchMapping("/users/{userId}/contact")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> changeContact(@PathVariable UUID userId,
                                               @Valid @RequestBody ChangeContactRequest req,
                                               @AuthenticationPrincipal StaffPrincipal caller) {
        userService.changeContact(userId, req.phone(), req.email(), req.reason(), caller);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Block an account",
               description = "Reversible. The person cannot sign in until unblocked; bookings and history are untouched.")
    @PostMapping("/users/{userId}/block")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> blockAccount(@PathVariable UUID userId,
                                              @Valid @RequestBody AccountActionRequest req,
                                              @AuthenticationPrincipal StaffPrincipal caller) {
        userService.blockAccount(userId, req.reason(), caller);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Lift a block",
               description = "Same authority as blocking. Does not reactivate an account the person deactivated themselves.")
    @PostMapping("/users/{userId}/unblock")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> unblockAccount(@PathVariable UUID userId,
                                                @Valid @RequestBody AccountActionRequest req,
                                                @AuthenticationPrincipal StaffPrincipal caller) {
        userService.unblockAccount(userId, req.reason(), caller);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove an account",
               description = "IRREVERSIBLE. Anonymises identifying fields; bookings survive for the salon's own accounting.")
    @PostMapping("/users/{userId}/remove")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> removeAccount(@PathVariable UUID userId,
                                               @Valid @RequestBody AccountActionRequest req,
                                               @AuthenticationPrincipal StaffPrincipal caller) {
        userService.removeAccount(userId, req.reason(), caller);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{userId}/unlock")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT','FINANCE_ADMIN','READ_ONLY')")
    public List<BookingServiceClient.SupportBooking> findBookings(@RequestParam("q") String query) {
        return bookingClient.search(query);
    }

    @Operation(summary = "A booking's event trail", description = "Append-only. Settles most disputes without anyone having to be believed.")
    @GetMapping("/bookings/{bookingId}/events")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT','FINANCE_ADMIN')")
    public List<BookingServiceClient.BookingEvent> bookingEvents(@PathVariable UUID bookingId) {
        return bookingClient.events(bookingId);
    }

    @Operation(
        summary = "Cancel on the customer's behalf",
        description = "The state machine models cancellation as a CUSTOMER action — there is no staff-actor cancel — so this acts as the customer. That's exactly why a reason is required and audited: the entry is the only thing distinguishing \"cancelled at the customer's request\" from \"cancelled somebody's appointment\".")
    @PostMapping("/bookings/{bookingId}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT','FINANCE_ADMIN')")
    public List<com.bmp.admin.entities.RefundRequest> listRefunds(@RequestParam(required = false) String status) {
        return refunds.list(status);
    }

    @Operation(summary = "Raise a refund request", description = "Needs a reason and cannot exceed the booking total. One open request per booking — two agents on the same complaint get a sentence, not a constraint violation.")
    @PostMapping("/refunds")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','FINANCE_ADMIN')")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
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

        /*
         * Session 60 — ACT on the decision, don't just record it.
         *
         * Deliberately after the report is saved and audited. If the downstream call fails, the
         * decision still exists and can be retried; the reverse order would leave content removed
         * with no record of who decided it.
         */
        if ("upheld".equals(status)) {
            enactModeration(report, note, caller);
        }

        return report;
    }

    /**
     * Make an upheld report true.
     *
     * <h2>Two kinds of content, two different actions — and one that is deliberately manual</h2>
     * <ul>
     *   <li><b>review</b> — hidden in bmp-review. Kept, not deleted: moderation is reversible and
     *       {@code booking_id} is unique, so a delete would let the same customer write a
     *       replacement.</li>
     *   <li><b>salon_photo</b> — genuinely deleted in bmp-salon. Nothing references a photo row and
     *       nobody will restore one, so a hidden-photo state would add a filter to every gallery
     *       read to preserve something with no use.</li>
     *   <li><b>salon_profile / stylist_profile</b> — <b>nothing happens automatically.</b></li>
     * </ul>
     *
     * <h2>Why a profile report does not auto-suspend</h2>
     * The remedy for a bad profile is to take the business or the stylist off the platform, which
     * ends somebody's ability to earn. That decision has its own screen, its own required note and
     * its own narrower role — and it emails the owner. Wiring it to a moderation click would mean a
     * support agent resolving a queue item could take a salon offline as a side effect of clearing
     * their list. The report is recorded and the moderator is told, in the log, what to use instead.
     *
     * <h2>Never throws</h2>
     * The decision is already recorded and audited. Failing the request here would show the
     * moderator an error for a judgement that WAS saved, and they would make it again — producing a
     * second audit row for one decision. A downstream failure is logged at ERROR with the report id
     * so it can be re-run, which is the recoverable shape.
     */
    private void enactModeration(com.bmp.admin.entities.ContentReport report, String note,
                                  StaffPrincipal caller) {
        String type = report.getContentType();
        try {
            switch (type == null ? "" : type) {
                case "review" -> {
                    var result = reviewClient.hide(report.getContentId(),
                            new com.bmp.admin.client.ReviewServiceClient.HideRequest(note, caller.staffId()));
                    log.info("Report {} upheld — review {} is now hidden (since {}).",
                            report.getId(), report.getContentId(), result.hiddenAt());
                }
                case "salon_photo" -> {
                    var result = salonClient.removePhoto(report.getContentId(),
                            new SalonServiceClient.RemovePhotoRequest(note));
                    log.info("Report {} upheld — photo {} removed from salon {}.",
                            report.getId(), report.getContentId(), result.salonId());
                }
                case "salon_profile", "stylist_profile" -> log.warn(
                        "Report {} upheld against {} {} — NOTHING was removed automatically. "
                        + "The remedy for a profile is suspension, which is its own decision with "
                        + "its own note and its own role. Use the salon or stylist screen.",
                        report.getId(), type, report.getContentId());
                default -> log.error(
                        "Report {} upheld against content type '{}', which nothing knows how to act "
                        + "on. The decision is recorded and the content is UNCHANGED. Either the "
                        + "type is wrong or enactModeration needs a case for it.",
                        report.getId(), type);
            }
        } catch (Exception e) {
            log.error("Report {} was upheld and recorded, but removing the {} {} FAILED ({}). The "
                    + "content is still visible. Re-resolve the report once the downstream service "
                    + "is healthy — the decision itself is already saved.",
                    report.getId(), type, report.getContentId(), e.toString(), e);
        }
    }

    // ---- salon moderation ---------------------------------------------------------------------

    @Operation(
        summary = "Every salon on the platform",
        description = "The moderation queue only lists salons awaiting review. This is how you find one that is already live — to suspend it, or to look it up for support.")
    @GetMapping("/salons")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT','FINANCE_ADMIN','READ_ONLY')")
    public java.util.List<SalonServiceClient.SalonAdminRow> allSalons(
            @RequestParam(required = false) String status) {
        return salonClient.allSalons(status);
    }

    /**
     * Salon help — "why is this salon not getting bookings?" Session 65.
     *
     * <h2>The page existed, the server work existed, the wire between them did not</h2>
     * bmp-salon has exposed {@code /internal/{salonId}/support-summary} for several sessions —
     * stylist count, how many are working today, how many have hours set, and a {@code
     * configWarning} naming the usual cause. Nothing in bmp-admin ever called it.
     *
     * <p>Meanwhile the console's Salon help page called {@code GET /salons} — the ADMIN list — and
     * parsed the answer with a schema expecting the diagnostic fields. Those fields were never in
     * that response, so Zod rejected every reply and the page showed
     * {@code "findSalons: unexpected response shape"} for every search. The screen has never
     * worked.
     *
     * <h2>Why enrichment is per-match rather than one bulk call</h2>
     * bmp-salon exposes the summary per salon and support searches by name or area, so a query
     * returns a handful of rows, not a page of them. N small calls for N∈[0,10] is fine; a bulk
     * endpoint would be the right answer if this ever fed a dashboard, and it does not.
     *
     * <p>A salon whose summary cannot be fetched still appears, with the diagnostics null — the
     * agent gets the salon they searched for and an honest blank rather than a failed page. Same
     * rule as the moderation packet.
     */
    @Operation(
        summary = "Find a salon, with the numbers that explain a quiet diary",
        description = "Search by name or area. Each match carries its stylist counts and a config warning naming the usual cause of 'we get no bookings' — most often no bookable hours set.")
    @GetMapping("/salons/support")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT','FINANCE_ADMIN','READ_ONLY')")
    public java.util.List<SupportSalonRow> findSalonsForSupport(@RequestParam(required = false) String q) {
        String needle = q == null ? "" : q.trim().toLowerCase();

        java.util.List<SalonServiceClient.SalonAdminRow> matches = salonClient.allSalons(null).stream()
                .filter(r -> needle.isEmpty()
                        || (r.name() != null && r.name().toLowerCase().contains(needle))
                        || (r.area() != null && r.area().toLowerCase().contains(needle))
                        || (r.reference() != null && r.reference().toLowerCase().contains(needle)))
                // Bounded: a blank query would otherwise enrich every salon on the platform.
                .limit(20)
                .toList();

        return matches.stream().map(r -> {
            SalonServiceClient.SalonSupportSummary d = null;
            try {
                d = salonClient.supportSummary(r.salonId());
            } catch (Exception e) {
                log.warn("No support summary for salon {} — showing the row without diagnostics ({})",
                        r.salonId(), e.toString());
            }
            return new SupportSalonRow(
                    r.salonId(), r.name(), r.area(), r.reference(), r.status(),
                    r.ownerName(),
                    // Masked, always. Support sees a shape, and uses the audited reveal for the rest.
                    masker.phone(r.ownerPhone()),
                    d == null ? null : d.stylistCount(),
                    d == null ? null : d.activeStylistsToday(),
                    d == null ? null : d.stylistsWithHours(),
                    d == null ? null : d.configWarning());
        }).toList();
    }

    /**
     * @param configWarning the answer to "why no bookings", when there is one. Null means the
     *                      salon looks configured — which is itself worth showing, because it
     *                      redirects the conversation away from the console.
     */
    public record SupportSalonRow(
            UUID id, String name, String area, String reference, String status,
            String ownerName, String ownerPhoneMasked,
            Integer stylistCount, Integer activeStylistsToday, Integer stylistsWithHours,
            String configWarning) {}

    @Operation(
        summary = "Freeze, restore or remove a salon",
        description = """
            OPS_ADMIN and SUPER_ADMIN only \u2014 this takes a business off the site.

            suspended = frozen: hidden from customers and unbookable, reversible, the owner is emailed and told to contact us.
            active    = restore a frozen salon.
            deleted   = removed from the site. A SOFT delete: the row stays so bookings, payouts and audit history survive. Reversible by an admin, not by the owner.

            A note is REQUIRED \u2014 the owner is told what happened, and 'suspended' with no reason produces a support ticket every single time.""")
    @PostMapping("/salons/{salonId}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    public void setSalonStatus(@PathVariable UUID salonId,
                                @Valid @RequestBody SalonStatusRequest req,
                                @AuthenticationPrincipal StaffPrincipal caller,
                                HttpServletRequest http) {
        moderation.setStatusDirect(salonId, req, caller, clientIp(http));
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  SALON PROFILE EDITING BY CONSOLE STAFF. Session 65.
    // ══════════════════════════════════════════════════════════════════════════════════════════
    /*
     * Until now there was NO way for anybody in the console to correct a salon's details. A salon
     * with a wrong address or a bouncing booking-alert email could only be fixed by its owner —
     * so support's answer to "your alerts are going nowhere" was "please log in and change it
     * yourself", which is the opposite of support.
     *
     * The tier decides WHICH FIELDS, not whether. See SalonEditScope: support fixes the contact
     * plumbing, ops edits the public listing, and nobody touches status (moderation) or the map
     * pin (the owner, standing in the shop).
     */

    /** Null means unchanged, on every field — so a client that omits one cannot blank it. */
    public record EditSalonRequest(
            String name, String area, String pincode, String address, String about,
            java.util.List<String> categories,
            String bookingNotifyEmail, String bookingNotifyPhone,
            @jakarta.validation.constraints.NotBlank String reason) {}

    @Operation(summary = "Edit a salon's profile",
               description = "Support may change where booking alerts go. Ops may also edit the listing. Status and location are not editable here.")
    @PatchMapping("/salons/{salonId}/profile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> editSalonProfile(@PathVariable UUID salonId,
                                                  @Valid @RequestBody EditSalonRequest req,
                                                  @AuthenticationPrincipal StaffPrincipal caller) {
        /*
         * Which fields is this request ACTUALLY changing? Only the non-null ones.
         *
         * Built from the request rather than from the form, because a console that sends every
         * field on every save — most do — would otherwise look like an attempt to change all of
         * them, and support would be refused for touching a field they left alone.
         */
        java.util.Set<String> changing = new java.util.LinkedHashSet<>();
        if (req.name() != null) changing.add("name");
        if (req.area() != null) changing.add("area");
        if (req.pincode() != null) changing.add("pincode");
        if (req.address() != null) changing.add("address");
        if (req.about() != null) changing.add("about");
        if (req.categories() != null) changing.add("categories");
        if (req.bookingNotifyEmail() != null) changing.add("bookingNotifyEmail");
        if (req.bookingNotifyPhone() != null) changing.add("bookingNotifyPhone");

        if (changing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to change.");
        }
        com.bmp.admin.security.SalonEditScope.requireCanEdit(caller, changing);

        salonClient.editProfile(salonId, new SalonServiceClient.EditSalonProfileRequest(
                req.name(), req.area(), req.pincode(), req.address(), req.about(),
                req.categories(), req.bookingNotifyEmail(), req.bookingNotifyPhone()));

        /*
         * The audit entry names the FIELDS, not the values.
         *
         * Values would be more useful and would put a salon's address history into a table read by
         * every reviewer; the field list plus the reason answers "who changed what and why", which
         * is what the log is for. The previous values are still recoverable from the salon row's
         * own history if that is ever needed.
         */
        auditLog.record("bmp_staff", caller.staffId(), "SALON_PROFILE_EDITED", "salon", salonId,
                java.util.Map.of("fields", String.join(", ", changing)),
                null, caller.email(), caller.role(), req.reason());

        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "The salon approval queue", description = "Pending oldest-first; anything else newest-first.")
    @GetMapping("/salons/reviews")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT','FINANCE_ADMIN','READ_ONLY')")
    public List<SalonReviewResponse> listSalonReviews(@RequestParam(required = false) String status) {
        return moderation.list(status);
    }

    @Operation(
        summary = "Approve, reject or suspend a salon",
        description = "Rejection REQUIRES a note — 'no' without a reason produces a support ticket every single time, and the owner has no idea what to fix. The checks you tick are recorded against your name.")
    @PostMapping("/salons/reviews/{reviewId}/decision")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    public SalonReviewResponse decideSalon(
            @PathVariable UUID reviewId,
            @Valid @RequestBody SalonDecisionRequest req,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        return moderation.decide(reviewId, req, caller, clientIp(http));
    }

    /**
     * The latest review for a salon, for the OWNER's own eyes. Session 46.
     *
     * <p>Service-only, called by bmp-salon, which has already checked the caller owns this salon.
     * It exists because until now <b>the owner was never told their own status</b>: the login
     * response carried none, and the dashboard rendered a full working desk whether the salon was
     * pending, rejected or approved. A rejected owner would build a service menu, add staff, and
     * wait for bookings that could never arrive — a failure that looks exactly like success.
     *
     * <p>Returns 204 when the salon has never been submitted, which is a real state rather than
     * an error (a salon created before the moderation queue existed, or an enqueue that failed).
     */
    /**
     * The salon as a CUSTOMER would see it, for the moderator about to judge it. Session 46.
     *
     * <p>Moderators were approving blind. The review panel showed a name, an id and an area —
     * nothing a customer actually looks at — while the decision checklist literally includes
     * "photos genuine". Judging that without seeing the photos is guessing, and an approval based
     * on a guess is the one that later becomes a content report.
     *
     * <p>Degrades rather than fails: if bmp-salon is unreachable this returns an empty list and
     * the panel renders without the preview, exactly as it did before. A moderation queue that
     * errors out entirely during a partial outage is worse than one that is temporarily less
     * informative.
     */
    @Operation(summary = "The salon's photos, for the moderation preview",
            description = "What a customer would see. Empty when the salon has no photos — which "
                    + "is itself worth knowing before approving it.")
    @GetMapping("/salons/{salonId}/preview-photos")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT','READ_ONLY')")
    public List<SalonServiceClient.SalonPhotoDto> previewPhotos(@PathVariable UUID salonId) {
        try {
            return salonClient.getSalonPhotos(salonId);
        } catch (Exception e) {
            log.warn("Could not load photos for salon {} in the moderation preview ({})",
                    salonId, e.toString());
            return List.of();
        }
    }

    @Operation(summary = "[internal] The latest review for a salon",
            description = "For showing an owner their own approval status and, on rejection, the "
                    + "moderator's reason. 204 when never submitted.")
    @GetMapping("/salons/{salonId}/review")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<SalonReviewResponse> latestReview(@PathVariable UUID salonId) {
        return moderation.latestFor(salonId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * A rejected salon comes back after being fixed. Session 46.
     *
     * <p>Service-only for the same reason as the read above: bmp-salon owns the "is this really
     * the owner?" question, bmp-admin owns "is this salon actually in a state that can be
     * resubmitted?". Neither takes the other's word for its own half.
     */
    @Operation(summary = "[internal] Resubmit a rejected salon for review",
            description = "Creates a NEW review row rather than reopening the rejected one, so the "
                    + "rejection and its reason are never overwritten. 409 if the salon isn't "
                    + "rejected.")
    @PostMapping("/salons/{salonId}/resubmit")
    @PreAuthorize("hasRole('SERVICE')")
    public SalonReviewResponse resubmit(@PathVariable UUID salonId,
                                         @RequestBody(required = false) ResubmitRequest req) {
        return moderation.resubmit(salonId, req == null ? null : req.note());
    }

    /** @param note what the owner says they changed. Optional but strongly encouraged. */
    public record ResubmitRequest(String note) {}

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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT','FINANCE_ADMIN','READ_ONLY')")
    public List<UserSummaryResponse> searchUsers(@RequestParam("q") String query,
                                                  @AuthenticationPrincipal StaffPrincipal caller) {
        return userService.search(query, caller);
    }

    @Operation(
        summary = "Reveal one masked field",
        description = "Returns exactly one field and writes an audit entry naming you, the customer, the field and your reason. Not a 'show everything' toggle: the narrower the request, the more meaningful the record.")
    @PostMapping("/users/{userId}/reveal")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    public AccountHealthResponse accountHealth(@PathVariable UUID userId,
                                                @AuthenticationPrincipal StaffPrincipal caller) {
        return userService.accountHealth(userId, caller);
    }

    // ---- data requests (DPDP) ---------------------------------------------------------------------

    @Operation(summary = "Data subject requests", description = "Soonest statutory deadline first.")
    @GetMapping("/data-requests")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    public List<DataRequestResponse> listDataRequests(@RequestParam(required = false) String status) {
        return dataRequests.list(status);
    }

    @Operation(summary = "Raise a data request", description = "Usually on behalf of a customer who asked by email or through support.")
    @PostMapping("/data-requests")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    public ResponseEntity<DataRequestResponse> createDataRequest(
            @Valid @RequestBody CreateDataRequestRequest req,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(dataRequests.create(req, caller, clientIp(http)));
    }

    @Operation(summary = "Confirm the requester's identity", description = "Must happen before fulfilment. Record HOW you confirmed it — that note is the evidence.")
    @PostMapping("/data-requests/{id}/verify-identity")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    public DataRequestResponse verifyIdentity(@PathVariable UUID id,
                                               @RequestBody DataRequestActionRequest req,
                                               @AuthenticationPrincipal StaffPrincipal caller,
                                               HttpServletRequest http) {
        return dataRequests.verifyIdentity(id, req, caller, clientIp(http));
    }

    @Operation(
        summary = "Fulfil",
        description = "Refuses unless identity is verified — acting on a forged erasure request is itself a breach. Deletion anonymises the account (Session 56): personal fields cleared, booking and invoice records retained without identifiers. For an export, generate the bundle first — see GET /data-requests/{id}/export.")
    @PostMapping("/data-requests/{id}/complete")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    public DataRequestResponse completeDataRequest(@PathVariable UUID id,
                                                    @RequestBody DataRequestActionRequest req,
                                                    @AuthenticationPrincipal StaffPrincipal caller,
                                                    HttpServletRequest http) {
        return dataRequests.complete(id, req, caller, clientIp(http));
    }

    /**
     * Everything we hold about the subject, as JSON. Session 60.
     *
     * <h2>Gated on identity verification, exactly like fulfilment</h2>
     * Generating the bundle IS the disclosure — once it exists on a laptop, the control has already
     * been exercised. Gating only the "complete" button and leaving the data one GET away would
     * make the verification step decorative.
     *
     * <h2>Audited as a disclosure, not as a read</h2>
     * A staff member obtaining a full copy of a customer's history is among the most sensitive
     * actions in this console, and it is the one an access review will ask about. The audit row
     * names the staff member, the subject and the request it was performed under.
     *
     * <h2>Why the console downloads it rather than us emailing the customer</h2>
     * See DataExportService: the whole flow is human-verified on purpose, and auto-sending to the
     * address on the account would route around that — which matters most when the reason for the
     * request is that the account was taken over.
     */
    @Operation(summary = "Generate the data export bundle",
               description = "Identity must be verified first. Returns the whole bundle as JSON; a failed source is declared inside it rather than omitted. Audited as a disclosure.")
    @GetMapping("/data-requests/{id}/export")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    public com.bmp.admin.services.DataExportService.ExportBundle exportDataRequest(
            @PathVariable UUID id,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        return dataRequests.export(id, caller, clientIp(http));
    }

    @Operation(summary = "Reject", description = "Needs a reason — the requester is entitled to know why.")
    @PostMapping("/data-requests/{id}/reject")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','FINANCE_ADMIN')")
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
    // ══ stylists: platform power. V025 (Session 51). ══════════════════════════════════════════
    //
    // Until now the console could moderate salons, users and tickets, and could not touch a
    // stylist at all. Three actions, and the console must not blur the first two:
    //
    //   remove   — employment. The same act an owner performs; they work elsewhere tomorrow.
    //   suspend  — the platform barring them from BMP entirely.
    //   reinstate— lifting that bar.
    //
    // All three are audited like every other admin action, because "who barred this person, and
    // why?" is the first question when it is disputed, and the answer has to outlive the staff
    // member who made the call.

    public record SuspendStylistRequest(String reason) {}

    @Operation(summary = "One stylist, with their suspension state and current salons")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    @GetMapping("/api/v1/admin/stylists/{stylistId}")
    public SalonServiceClient.StylistAdminDto stylist(@PathVariable UUID stylistId) {
        return salonClient.getStylist(stylistId);
    }

    @Operation(summary = "Every currently-suspended stylist")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    @GetMapping("/api/v1/admin/stylists/suspended")
    public java.util.List<SalonServiceClient.StylistAdminDto> suspendedStylists() {
        return salonClient.suspendedStylists();
    }

    /**
     * Bar a stylist from BMP entirely.
     *
     * <h2>Not SUPPORT_AGENT</h2>
     * Reading a stylist's record is support work; ending their ability to earn on the platform is
     * not. A support agent handling an angry call at 9pm should not be able to bar the person
     * being complained about — that is a decision to take deliberately, by someone accountable
     * for it. Same split the refund and salon-suspension endpoints already use.
     */
    @Operation(
        summary = "Suspend a stylist from BMP",
        description = "They cannot be added to any salon, cannot be accepted from a join request, "
            + "and produce no bookable slots anywhere. Existing team memberships are LEFT INTACT "
            + "— this is not a deletion of anyone's history. Reversible. A reason is required and "
            + "is shown to the stylist.")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    @PostMapping("/api/v1/admin/stylists/{stylistId}/suspend")
    public SalonServiceClient.StylistAdminDto suspendStylist(
            @PathVariable UUID stylistId,
            @RequestBody SuspendStylistRequest req,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {

        if (req.reason() == null || req.reason().trim().length() < 5) {
            // Checked here as well as in bmp-salon and in V025's CHECK. Three layers is not
            // paranoia for this one: the reason is the only thing the stylist can act on, and a
            // 500 from a constraint violation would tell the admin nothing useful.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "REASON_REQUIRED: say why, in a sentence the stylist can read. They are shown "
                    + "this, and a bar they cannot contest is not a decision.");
        }

        var result = salonClient.suspendStylist(stylistId,
                new SalonServiceClient.SuspendStylistBody(req.reason().trim(), caller.staffId()));

        auditLog.record("bmp_staff", caller.staffId(), "STYLIST_SUSPENDED",
                "stylist", stylistId,
                java.util.Map.of("activeSalonCount", result.activeSalonCount()),
                clientIp(http), caller.email(), caller.role(), req.reason().trim());
        return result;
    }

    @Operation(
        summary = "Lift a stylist's suspension",
        description = "Bookable again wherever they are still on a team. Salons that removed them "
            + "meanwhile must re-add them — this does not undo a salon's own decision.")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    @PostMapping("/api/v1/admin/stylists/{stylistId}/reinstate")
    public SalonServiceClient.StylistAdminDto reinstateStylist(
            @PathVariable UUID stylistId,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        var result = salonClient.reinstateStylist(stylistId);
        auditLog.record("bmp_staff", caller.staffId(), "STYLIST_REINSTATED",
                "stylist", stylistId, java.util.Map.of(),
                clientIp(http), caller.email(), caller.role(), null);
        return result;
    }

    /**
     * Remove a stylist from ONE salon's team.
     *
     * <p>For when a salon cannot or will not — one that has gone quiet, or one whose owner is the
     * subject of the complaint. It is employment, not a ban: they can join another salon
     * tomorrow. Use suspend for the other thing.
     */
    @Operation(
        summary = "Remove a stylist from one salon (admin)",
        description = "Same effect as the owner's own remove: the link becomes alumni and the "
            + "history is kept. Does NOT bar them from BMP.")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    @PostMapping("/api/v1/admin/salons/{salonId}/stylists/{stylistId}/remove")
    public void adminRemoveStylistFromSalon(
            @PathVariable UUID salonId, @PathVariable UUID stylistId,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        salonClient.removeStylistFromSalon(salonId, stylistId, caller.staffId());
        auditLog.record("bmp_staff", caller.staffId(), "STYLIST_REMOVED_FROM_SALON",
                "stylist", stylistId,
                java.util.Map.of("salonId", salonId.toString()),
                clientIp(http), caller.email(), caller.role(), null);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
