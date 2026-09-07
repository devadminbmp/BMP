package com.bmp.admin.controllers;

import com.bmp.admin.entities.BmpStaff;
import com.bmp.admin.entities.QueueConfig;
import com.bmp.admin.entities.StaffLeave;
import com.bmp.admin.repositories.BmpStaffRepository;
import com.bmp.admin.repositories.QueueConfigRepository;
import com.bmp.admin.security.RoleHierarchy;
import com.bmp.admin.security.StaffPermission;
import com.bmp.admin.security.StaffAccountScope;
import com.bmp.admin.security.StaffPrincipal;
import com.bmp.admin.services.AuditLogService;
import com.bmp.admin.services.LeavePlanService;
import com.bmp.admin.services.LeaveYear;
import com.bmp.admin.services.StaffLeaveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The team: who works here, what they're doing, and when they're off. Session 59.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS IS AND IS NOT
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Darshan: <i>"I need admin and ops admin to have a feature to maintain other members in the team
 * like support etc — mainly I need an employee management system for my team members, including
 * leaves."</i>
 *
 * <p>It IS: the roster, the org chart, joining and leaving dates, working-hours notes, current
 * ticket load, availability, and leave with an approval path.
 *
 * <p>It is NOT payroll. No salary, no bank details, no government identifiers — see the note in
 * {@code BmpStaff}. Those belong in a system with a different access model, and putting them here
 * would turn "read the team page" (which five roles can do) into a payroll breach.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHO CAN DO WHAT
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <ul>
 *   <li><b>Everyone</b> sees the roster and their own leave. A support agent needs to know who is
 *       on shift to hand something over.</li>
 *   <li><b>Ops and the owner</b> edit employee details, decide leave, and set queue modes.</li>
 *   <li><b>Only an ops admin holding {@code can_manage_staff}, or the owner</b>, creates accounts —
 *       enforced in {@code StaffManagementController}, not duplicated here.</li>
 *   <li><b>Nobody</b> approves their own leave, including the owner. See {@code StaffLeaveService}.</li>
 * </ul>
 */
@Tag(name = "Team",
     description = "The roster, availability, leave and queue assignment modes. Not payroll — see the class note.")
@RestController
@RequestMapping("/api/v1/admin/team")
public class TeamController {

    private final BmpStaffRepository staff;
    private final StaffLeaveService leave;
    private final QueueConfigRepository queues;
    private final AuditLogService audit;
    /** Session 65 — entitlements and balances. See LeavePlanService. */
    private final LeavePlanService leavePlans;

    public TeamController(BmpStaffRepository staff, StaffLeaveService leave,
                           QueueConfigRepository queues, AuditLogService audit,
                           LeavePlanService leavePlans) {
        this.staff = staff;
        this.leave = leave;
        this.queues = queues;
        this.audit = audit;
        this.leavePlans = leavePlans;
    }

    // ── shapes ────────────────────────────────────────────────────────────────────────────────

    /**
     * @param openTickets what they are carrying right now — the number that tells a lead whether
     *                    somebody is free, and the same one assignment orders by.
     * @param onLeaveToday resolved per request rather than stored, because "today" changes.
     */
    public record MemberResponse(
            UUID id, String name, String email, String role, short tier,
            String status, String jobTitle, String employeeCode,
            LocalDate joinedOn, LocalDate exitedOn, UUID reportsToStaffId,
            String shiftNote, boolean acceptingTickets, int openTickets,
            boolean canManageStaff, boolean onLeaveToday) {}

    public record UpdateMemberRequest(
            @Size(max = 80) String jobTitle,
            @Size(max = 20) String employeeCode,
            LocalDate joinedOn,
            UUID reportsToStaffId,
            @Size(max = 120) String shiftNote,
            /** End-dating somebody. Their account is suspended separately and deliberately. */
            LocalDate exitedOn) {}

    public record LeaveRequest(
            @NotBlank String leaveType,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn,
            String halfDay,
            @Size(max = 500) String reason) {}

    /**
     * @param days          CALENDAR span — how many days the person is away. What a rota needs.
     * @param daysCharged   BALANCE cost. A half-day is 0.5 here and 1 above, and the difference is
     *                      the whole reason both exist: cover and entitlement are different
     *                      questions and one number cannot answer both honestly.
     * @param balanceWarning Session 65 — non-null when this request exceeds the person's remaining
     *                      allowance, phrased as a sentence with the numbers in it. Darshan's call:
     *                      over-balance WARNS and never blocks, so this is what makes the warning
     *                      reach the human who decides. Null when the request fits.
     */
    public record LeaveResponse(
            UUID id, UUID staffId, String staffName, String leaveType,
            LocalDate startsOn, LocalDate endsOn, String halfDay, long days,
            java.math.BigDecimal daysCharged,
            String reason, String status, String decisionNote, Instant decidedAt,
            String balanceWarning) {}

    public record DecideLeaveRequest(@NotNull Boolean approve, @Size(max = 500) String note) {}

    public record QueueConfigResponse(short tier, String assignmentMode, UUID analystStaffId,
                                       int maxOpenPerAgent) {}

    public record UpdateQueueRequest(
            @NotBlank String assignmentMode,
            UUID analystStaffId,
            int maxOpenPerAgent) {}

    // ── the roster ────────────────────────────────────────────────────────────────────────────

    /**
     * Everyone, with their load and availability.
     *
     * <p>Readable by any staff member. A support agent handing something over at end of shift needs
     * to know who is on — and hiding the roster from the people who work in it produces a WhatsApp
     * group that becomes the real source of truth.
     *
     * <p>No contact details beyond the work email: this is a colleague list, not a directory of
     * personal numbers.
     */
    @Operation(summary = "The team, with current load and who's off today")
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public List<MemberResponse> roster() {
        var offToday = leave.offOn(LocalDate.now()).stream()
                .map(StaffLeave::getStaffId).collect(java.util.stream.Collectors.toSet());

        return staff.findAll().stream()
                // Somebody who has left stays in the database for their audit trail, but they are
                // not "the team" — showing them would make the roster a graveyard.
                .filter(s -> s.getExitedOn() == null)
                .sorted(java.util.Comparator.comparing(BmpStaff::getTier).reversed()
                        .thenComparing(BmpStaff::getName))
                .map(s -> toMember(s, offToday.contains(s.getId())))
                .toList();
    }

    @Operation(summary = "One team member")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{staffId}")
    public MemberResponse member(@PathVariable UUID staffId) {
        BmpStaff s = staff.findById(staffId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));
        boolean off = leave.offOn(LocalDate.now()).stream()
                .anyMatch(l -> l.getStaffId().equals(staffId));
        return toMember(s, off);
    }

    /**
     * Edit somebody's employment details.
     *
     * <h2>Deliberately cannot change role, tier or status</h2>
     * Those are permission changes and live in {@code StaffManagementController}, behind
     * {@code staff:manage}. Letting a team-details screen quietly promote somebody would put a
     * privilege escalation behind a form labelled "job title".
     *
     * <p>Null fields are left alone rather than cleared — a partial edit must not blank the
     * fields it didn't send.
     */
    @Operation(summary = "Update employment details",
               description = "Ops and owner. Cannot change role, tier or account status — those are permission changes and live behind staff:manage.")
    @PreAuthorize("hasAnyRole('SUPPORT_LEAD','ADMIN','OPS_ADMIN','SUPER_ADMIN')")
    @PutMapping("/{staffId}")
    public MemberResponse updateMember(@PathVariable UUID staffId,
                                        @Valid @RequestBody UpdateMemberRequest req,
                                        @AuthenticationPrincipal StaffPrincipal caller) {
        BmpStaff s = staff.findById(staffId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));

        /*
         * ── RANK, and it was MISSING here. Session 65, a bug fix. ──────────────────────────────
         *
         * The annotation proved the caller was ops or above and then stopped asking. So one ops
         * admin could rewrite another ops admin's reporting line, and — worse — anybody at ops
         * could set the PLATFORM OWNER's exitedOn, which sets acceptingTickets false and drops
         * them off the roster entirely (`list` filters on exitedOn == null). Erasing the owner
         * from the team screen behind a form labelled "job title" is exactly the shape of thing
         * StaffAccountScope exists to stop, and this endpoint was simply not calling it.
         *
         * Reads the row FIRST, then authorises against the target's role — the same order as
         * StaffAdminService.changeStatus, and for the same reason: "may this caller act?" is
         * unanswerable until you know whose account it is.
         *
         * SUPPORT_LEAD added to the annotation at the same time, and it is safe precisely BECAUSE
         * of this check: a support manager may now edit the shift notes and job titles of their own
         * agents (Darshan's spec) and rank refuses them everybody else. Adding the role without the
         * check would have handed the desk the owner's record.
         *
         * requireCanEditEmployment, NOT requireCanManage: a lead holds team:edit and deliberately
         * not account:manage_staff, so the account rule would have refused them on every row and
         * left a menu item that can only ever 403.
         */
        StaffAccountScope.requireCanEditEmployment(caller, s.getRole(), s.getId());

        /*
         * A cycle in the org chart — A reports to B reports to A — makes any "who is above this
         * person" walk loop forever. Cheap to prevent, miserable to debug.
         */
        if (req.reportsToStaffId() != null) {
            if (req.reportsToStaffId().equals(staffId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Somebody can't report to themselves.");
            }
            if (createsCycle(staffId, req.reportsToStaffId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That would create a loop in the reporting line.");
            }
            s.setReportsToStaffId(req.reportsToStaffId());
        }
        if (req.jobTitle() != null) s.setJobTitle(req.jobTitle());
        if (req.employeeCode() != null) s.setEmployeeCode(req.employeeCode());
        if (req.joinedOn() != null) s.setJoinedOn(req.joinedOn());
        if (req.shiftNote() != null) s.setShiftNote(req.shiftNote());
        if (req.exitedOn() != null) {
            s.setExitedOn(req.exitedOn());
            /*
             * Somebody leaving stops receiving tickets immediately. Their ACCOUNT is suspended
             * separately, on purpose: end-dating is an HR fact and revoking access is a security
             * action, and conflating them means one screen can quietly do the other.
             */
            s.setAcceptingTickets(false);
        }
        staff.save(s);

        audit.record("bmp_staff", caller.staffId(), "TEAM_MEMBER_UPDATED", "bmp_staff", staffId,
                Map.of("jobTitle", String.valueOf(req.jobTitle()),
                        "exitedOn", String.valueOf(req.exitedOn())),
                null, caller.email(), caller.role(), null);

        return toMember(s, false);
    }

    /**
     * Turn your own ticket intake on or off — end of shift, a meeting, deep work.
     *
     * <p>Yourself, or ops acting for somebody who forgot. Not a leave request: this is minutes and
     * hours, and routing it through an approval would make people simply stop using it.
     */
    @Operation(summary = "Pause or resume receiving tickets")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{staffId}/availability")
    public MemberResponse setAvailability(@PathVariable UUID staffId,
                                           @RequestParam boolean accepting,
                                           @AuthenticationPrincipal StaffPrincipal caller) {
        boolean isSelf = staffId.equals(caller.staffId());
        /*
         * Session 65 — rank, not two hardcoded names. This read
         * `"ops_admin".equals(role) || "super_admin".equals(role)`, which silently excluded the new
         * ADMIN rung: an admin, who outranks ops, could not pause somebody ops could pause.
         *
         * Rank against ops_admin rather than against the TARGET, deliberately. Pausing somebody is
         * a rota decision, not an authority one — a support manager legitimately pauses their own
         * agents — so the question is "are you senior enough to run a rota", not "do you outrank
         * this specific person".
         */
        boolean isSupervisor = RoleHierarchy.rankOf(caller.role())
                >= RoleHierarchy.rankOf(StaffPermission.OPS_ADMIN);
        if (!isSelf && !isSupervisor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only change your own availability.");
        }
        BmpStaff s = staff.findById(staffId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));
        s.setAcceptingTickets(accepting);
        staff.save(s);
        return toMember(s, false);
    }

    // ── leave ─────────────────────────────────────────────────────────────────────────────────

    @Operation(summary = "Request time off",
               description = "For yourself. Overlapping requests are refused; only sick leave may be backdated.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/leave")
    public LeaveResponse requestLeave(@Valid @RequestBody LeaveRequest req,
                                       @AuthenticationPrincipal StaffPrincipal caller) {
        return toLeave(leave.request(req.leaveType(), req.startsOn(), req.endsOn(),
                req.halfDay(), req.reason(), caller));
    }

    @Operation(summary = "My leave history")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/leave/mine")
    public List<LeaveResponse> myLeave(@AuthenticationPrincipal StaffPrincipal caller) {
        return leave.forStaff(caller.staffId()).stream().map(this::toLeave).toList();
    }

    @Operation(summary = "Leave waiting for a decision")
    /*
     * Session 65 — SUPPORT_LEAD added, and the list is filtered per caller.
     *
     * The annotation says who may see A queue; LeaveApprovalScope decides WHOSE rows are in it. A
     * lead sees their own team's requests and not ops's; ops sees the desk and the leads and not
     * another ops admin's; the owner sees everything but their own.
     */
    @PreAuthorize("hasAnyRole('SUPPORT_LEAD','ADMIN','OPS_ADMIN','SUPER_ADMIN')")
    @GetMapping("/leave/pending")
    public List<LeaveResponse> pendingLeave(@AuthenticationPrincipal StaffPrincipal caller) {
        return leave.pendingFor(caller).stream().map(this::toLeave).toList();
    }

    @Operation(summary = "Approve or refuse leave",
               description = "Ops and owner. Nobody decides their own — including the owner. Approving takes the person out of the ticket rotation for the duration.")
    // Session 65 — a lead may decide their own team's leave. LeaveApprovalScope, called inside the
    // service, is what says WHOSE: this only proves the caller is senior enough to be here at all.
    @PreAuthorize("hasAnyRole('SUPPORT_LEAD','ADMIN','OPS_ADMIN','SUPER_ADMIN')")
    @PostMapping("/leave/{leaveId}/decide")
    public LeaveResponse decideLeave(@PathVariable UUID leaveId,
                                      @Valid @RequestBody DecideLeaveRequest req,
                                      @AuthenticationPrincipal StaffPrincipal caller) {
        return toLeave(leave.decide(leaveId, req.approve(), req.note(), caller));
    }

    @Operation(summary = "Withdraw my own pending request")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/leave/{leaveId}/cancel")
    public LeaveResponse cancelLeave(@PathVariable UUID leaveId,
                                      @AuthenticationPrincipal StaffPrincipal caller) {
        return toLeave(leave.cancel(leaveId, caller));
    }

    /** Who is off on a date — the team calendar. Visible to everyone, for handovers. */
    @Operation(summary = "Who is off on a given day")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/leave/on")
    public List<LeaveResponse> offOn(
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso =
                org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date) {
        return leave.offOn(date == null ? LocalDate.now() : date).stream().map(this::toLeave).toList();
    }

    // ── HR: entitlements, balances, and the whole-team view ───────────────────────────────────
    //
    // Darshan, Session 65: "he allocate leave plans for each like sick annual etc because still we
    // dont have hr profile main admin can do it ... he can see how many leaves each have taken and
    // what dates etc by everyone even support".
    //
    // Three audiences, three levels of access, and they are genuinely different questions:
    //   • ANYBODY  — "what is MY balance". Your own numbers, nobody else's.
    //   • APPROVER — "what is THIS person's balance", while deciding their request. Rank-gated.
    //   • MAIN ADMIN — the whole team, and the ability to change the numbers.

    /** A person's allowance, usage and remaining days for one type. */
    public record TypeBalanceResponse(String leaveType, java.math.BigDecimal allowed,
                                      java.math.BigDecimal taken, java.math.BigDecimal remaining,
                                      String source) {}

    /** One person's whole leave year, with the dates. */
    public record BalanceResponse(UUID staffId, String name, String email, String role,
                                  int fyStartYear, String fyLabel,
                                  List<TypeBalanceResponse> types,
                                  java.math.BigDecimal totalTaken,
                                  List<LeavePlanService.TakenEntry> history) {}

    /** One cell of the org-wide plan. */
    public record LeavePlanResponse(String role, String roleLabel, String leaveType,
                                    int fyStartYear, java.math.BigDecimal daysAllowed,
                                    java.math.BigDecimal carryForwardMax) {}

    public record SetPlanRequest(
            @NotBlank String role, @NotBlank String leaveType,
            @NotNull java.math.BigDecimal daysAllowed,
            java.math.BigDecimal carryForwardMax) {}

    public record SetEntitlementRequest(
            @NotBlank String leaveType,
            @NotNull java.math.BigDecimal daysAllowed,
            @Size(max = 300) String note) {}

    /**
     * MY balance. Every role, including support and read-only.
     *
     * <p>Deliberately open to everyone: the commonest reason somebody asks a manager "how many days
     * do I have left" is that no screen tells them, and a leave system that cannot answer that is
     * a form, not a system.
     */
    @Operation(summary = "My leave balance for a financial year")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/leave/balance/mine")
    public BalanceResponse myBalance(@RequestParam(required = false) Integer fy,
                                      @AuthenticationPrincipal StaffPrincipal caller) {
        return toBalance(leavePlans.balanceFor(caller.staffId(), fyOrCurrent(fy)));
    }

    /**
     * SOMEBODY ELSE'S balance — for an approver about to decide, or the main admin.
     *
     * <p>Gated by the SAME rank rule that decides their leave (LeaveApprovalScope): if you may
     * approve this person's time off you may see the balance you are approving against, and if you
     * may not, their leave record is none of your business. Reusing the rule rather than writing a
     * second one is the point — a view permission that drifts from the action permission is how
     * somebody ends up reading records they cannot act on.
     */
    @Operation(summary = "Somebody's leave balance",
               description = "Anyone whose leave you could decide. Same rule as approval.")
    @PreAuthorize("hasAnyRole('SUPPORT_LEAD','ADMIN','OPS_ADMIN','SUPER_ADMIN')")
    @GetMapping("/leave/balance/{staffId}")
    public BalanceResponse balanceOf(@PathVariable UUID staffId,
                                      @RequestParam(required = false) Integer fy,
                                      @AuthenticationPrincipal StaffPrincipal caller) {
        BmpStaff person = staff.findById(staffId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));
        if (!staffId.equals(caller.staffId())
                && !com.bmp.admin.security.LeaveApprovalScope.canDecide(caller, person.getRole(), staffId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only see the leave balance of somebody whose leave you could decide.");
        }
        return toBalance(leavePlans.balanceFor(staffId, fyOrCurrent(fy)));
    }

    /**
     * THE HR OVERVIEW — everybody, every type, with dates. Main admin only.
     *
     * <p>Not rank-filtered like the pending queue: this is the one screen whose entire purpose is
     * seeing the whole organisation at once, and a partial view of it would be worse than none —
     * you cannot plan cover from a list that silently omits people.
     */
    @Operation(summary = "Everyone's leave balances and history",
               description = "Main admin only. The HR view: who has taken what, when, and how much is left.")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/leave/overview")
    public List<BalanceResponse> leaveOverview(@RequestParam(required = false) Integer fy) {
        return leavePlans.overview(fyOrCurrent(fy)).stream().map(this::toBalance).toList();
    }

    /**
     * The org-wide plan. READABLE by any supervisor, WRITABLE by the main admin alone.
     *
     * <p>Split on purpose: a support manager approving leave needs to know what the allowance IS in
     * order to judge a request against it, and hiding the policy from the people applying it just
     * means they approve from memory.
     */
    @Operation(summary = "The leave plan for a financial year")
    @PreAuthorize("hasAnyRole('SUPPORT_LEAD','ADMIN','OPS_ADMIN','SUPER_ADMIN')")
    @GetMapping("/leave/plan")
    public List<LeavePlanResponse> leavePlan(@RequestParam(required = false) Integer fy) {
        return leavePlans.planFor(fyOrCurrent(fy)).stream()
                .map(p -> new LeavePlanResponse(p.getRole(), RoleHierarchy.label(p.getRole()),
                        p.getLeaveType(), p.getFyStartYear(), p.getDaysAllowed(), p.getCarryForwardMax()))
                .toList();
    }

    @Operation(summary = "Set the leave plan for a role",
               description = "Main admin only. Changes the DEFAULT everyone in that role inherits; individual overrides are untouched.")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/leave/plan")
    public LeavePlanResponse setLeavePlan(@Valid @RequestBody SetPlanRequest req,
                                           @RequestParam(required = false) Integer fy,
                                           @AuthenticationPrincipal StaffPrincipal caller,
                                           jakarta.servlet.http.HttpServletRequest http) {
        var saved = leavePlans.setPlan(req.role(), req.leaveType(), fyOrCurrent(fy),
                req.daysAllowed(), req.carryForwardMax(), caller, http.getRemoteAddr());
        return new LeavePlanResponse(saved.getRole(), RoleHierarchy.label(saved.getRole()),
                saved.getLeaveType(), saved.getFyStartYear(), saved.getDaysAllowed(), saved.getCarryForwardMax());
    }

    @Operation(summary = "Set one person's leave entitlement",
               description = "Main admin only. Overrides their role's plan for this type and year.")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/leave/entitlement/{staffId}")
    public BalanceResponse setEntitlement(@PathVariable UUID staffId,
                                           @Valid @RequestBody SetEntitlementRequest req,
                                           @RequestParam(required = false) Integer fy,
                                           @AuthenticationPrincipal StaffPrincipal caller,
                                           jakarta.servlet.http.HttpServletRequest http) {
        int year = fyOrCurrent(fy);
        leavePlans.setEntitlement(staffId, req.leaveType(), year, req.daysAllowed(),
                req.note(), caller, http.getRemoteAddr());
        // Return the whole recomputed card, so the screen never has to guess what changed.
        return toBalance(leavePlans.balanceFor(staffId, year));
    }

    @Operation(summary = "Remove an override, falling back to the role plan")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @DeleteMapping("/leave/entitlement/{staffId}/{leaveType}")
    public BalanceResponse clearEntitlement(@PathVariable UUID staffId, @PathVariable String leaveType,
                                             @RequestParam(required = false) Integer fy,
                                             @AuthenticationPrincipal StaffPrincipal caller,
                                             jakarta.servlet.http.HttpServletRequest http) {
        int year = fyOrCurrent(fy);
        leavePlans.clearEntitlement(staffId, leaveType, year, caller, http.getRemoteAddr());
        return toBalance(leavePlans.balanceFor(staffId, year));
    }

    /**
     * Default to the year we are in.
     *
     * <p>Via LeaveYear.current(), not {@code LocalDate.now().getYear()}: the leave year starts in
     * April, so from January to March those two differ — and the second one is wrong for a quarter
     * of every year, in a way that shows up as somebody's balance resetting three months early.
     */
    private static int fyOrCurrent(Integer fy) {
        return fy == null ? LeaveYear.current() : fy;
    }

    private BalanceResponse toBalance(LeavePlanService.StaffBalance b) {
        return new BalanceResponse(b.staffId(), b.name(), b.email(), b.role(),
                b.fyStartYear(), LeaveYear.label(b.fyStartYear()),
                b.types().stream().map(t -> new TypeBalanceResponse(
                        t.leaveType(), t.allowed(), t.taken(), t.remaining(), t.source())).toList(),
                b.totalTaken(), b.history());
    }

    // ── queue assignment mode ─────────────────────────────────────────────────────────────────

    @Operation(summary = "How each tier's queue is assigned")
    @PreAuthorize("hasAnyRole('SUPPORT_LEAD','ADMIN','OPS_ADMIN','SUPER_ADMIN')")
    @GetMapping("/queues")
    public List<QueueConfigResponse> queueConfig() {
        return queues.findAllByOrderByTierAsc().stream()
                .map(q -> new QueueConfigResponse(q.getTier(), q.getAssignmentMode(),
                        q.getAnalystStaffId(), q.getMaxOpenPerAgent()))
                .toList();
    }

    /**
     * Switch a tier between automatic, manual and analyst-distributed.
     *
     * <p>Ops and owner. A support lead can SEE the mode — it explains why their queue behaves the
     * way it does — but changing how work reaches their own team is an ops decision.
     */
    @Operation(summary = "Set a tier's assignment mode",
               description = "auto = least-loaded on arrival; manual = unassigned pool; analyst = one named person distributes. Analyst mode requires a named, assignable person.")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_ADMIN','SUPER_ADMIN')")
    @PutMapping("/queues/{tier}")
    public QueueConfigResponse setQueueConfig(@PathVariable short tier,
                                               @Valid @RequestBody UpdateQueueRequest req,
                                               @AuthenticationPrincipal StaffPrincipal caller) {
        QueueConfig config = queues.findByTier(tier).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No queue configured for that tier."));

        if (QueueConfig.ANALYST.equals(req.assignmentMode())) {
            if (req.analystStaffId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Name the person who'll be distributing this queue.");
            }
            /*
             * The analyst must actually be able to hold tickets. Pointing a whole tier at a
             * tier-0 analyst or a deactivated account would silently swallow every incoming
             * ticket — assigned to somebody who can never work them.
             */
            BmpStaff analyst = staff.findById(req.analystStaffId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "That person isn't on the team."));
            if (analyst.getTier() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That role can't hold tickets, so it can't distribute a queue either.");
            }
            config.setAnalystStaffId(req.analystStaffId());
        } else {
            // Clear it, so a mode change doesn't leave a stale name that looks meaningful.
            config.setAnalystStaffId(null);
        }

        config.setAssignmentMode(req.assignmentMode());
        config.setMaxOpenPerAgent(Math.max(0, req.maxOpenPerAgent()));
        config.setUpdatedAt(Instant.now());
        config.setUpdatedByStaffId(caller.staffId());
        queues.save(config);

        audit.record("bmp_staff", caller.staffId(), "QUEUE_MODE_CHANGED", "queue_config",
                config.getId(),
                Map.of("tier", String.valueOf(tier), "mode", req.assignmentMode()),
                null, caller.email(), caller.role(), null);

        return new QueueConfigResponse(config.getTier(), config.getAssignmentMode(),
                config.getAnalystStaffId(), config.getMaxOpenPerAgent());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private MemberResponse toMember(BmpStaff s, boolean onLeaveToday) {
        return new MemberResponse(s.getId(), s.getName(), s.getEmail(), s.getRole(), s.getTier(),
                s.getStatus(), s.getJobTitle(), s.getEmployeeCode(), s.getJoinedOn(),
                s.getExitedOn(), s.getReportsToStaffId(), s.getShiftNote(),
                s.isAcceptingTickets(), s.getOpenTicketCount(), s.isCanManageStaff(), onLeaveToday);
    }

    private LeaveResponse toLeave(StaffLeave l) {
        BmpStaff person = staff.findById(l.getStaffId()).orElse(null);
        String name = person == null ? null : person.getName();

        /*
         * The over-balance warning is computed ONLY for requests still awaiting a decision.
         *
         * Two reasons. It costs a query per row, and a history list is long. And on an already
         * decided row it would be actively misleading: it recomputes against TODAY's balance, so an
         * approved leave from April would show a warning derived from months of subsequent leave —
         * a number that was not in front of the person who approved it and does not describe what
         * they saw.
         */
        String warning = null;
        if (person != null && l.isPending()) {
            warning = leavePlans.overBalanceWarning(l.getStaffId(), person.getRole(),
                    l.getLeaveType(), l.getStartsOn(), l.getEndsOn(), l.getHalfDay());
        }

        return new LeaveResponse(l.getId(), l.getStaffId(), name, l.getLeaveType(),
                l.getStartsOn(), l.getEndsOn(), l.getHalfDay(), l.days(), l.daysCharged(),
                l.getReason(), l.getStatus(), l.getDecisionNote(), l.getDecidedAt(), warning);
    }

    /**
     * Would setting {@code candidateManager} as {@code staffId}'s manager create a loop?
     *
     * <p>Walks up from the proposed manager looking for the person being edited. Bounded at ten
     * hops — a reporting line deeper than that is already a data problem, and an unbounded walk on
     * a cycle that somehow exists would hang the request rather than reject it.
     */
    private boolean createsCycle(UUID staffId, UUID candidateManager) {
        UUID cursor = candidateManager;
        for (int hops = 0; hops < 10 && cursor != null; hops++) {
            if (cursor.equals(staffId)) return true;
            cursor = staff.findById(cursor).map(BmpStaff::getReportsToStaffId).orElse(null);
        }
        return false;
    }
}
