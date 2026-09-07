package com.bmp.admin.services;

import com.bmp.admin.entities.BmpStaff;
import com.bmp.admin.entities.StaffLeave;
import com.bmp.admin.repositories.BmpStaffRepository;
import com.bmp.admin.repositories.StaffLeaveRepository;
import com.bmp.admin.security.LeaveApprovalScope;
import com.bmp.admin.security.StaffPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff time off. Session 59.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY LEAVE IS PART OF THE SUPPORT SYSTEM AND NOT AN HR SIDE-PROJECT
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Darshan asked for employee management "including leaves". The reason it belongs HERE rather than
 * in a spreadsheet is one line of behaviour:
 *
 * <p><b>Approved leave takes somebody out of the ticket rotation.</b>
 *
 * <p>Without that, the assignment engine keeps handing tickets to a person on holiday. Those
 * tickets sit assigned — so nobody else picks them up, because the queue shows them as owned — and
 * a customer waits a week for a first reply while the queue looks healthy. A leave calendar that
 * does not affect assignment is decoration.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * TWO RULES THAT ARE NOT OBVIOUS
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <ul>
 *   <li><b>Nobody approves their own leave.</b> Including the owner — see {@link #decide}. It is
 *       the oldest control in expense and HR systems and it exists because self-approval makes the
 *       whole record meaningless.</li>
 *   <li><b>Overlapping requests are refused.</b> A double-tap produces two approved rows for one
 *       absence, and the day count is then wrong in a way nobody notices until year-end.</li>
 * </ul>
 */
@Service
public class StaffLeaveService {

    private static final Logger log = LoggerFactory.getLogger(StaffLeaveService.class);

    /*
     * Session 65 — `annual` added, matching V016's CHECK constraint.
     *
     * Casual and annual are different things in Indian practice: casual is a day or two at short
     * notice, annual (earned/privilege) accrues and is taken in blocks. Darshan asked for "sick
     * annual etc" when describing leave plans, and an allowance for a type nobody can request is
     * an allowance nobody can use.
     *
     * `unpaid` stays in this list and is deliberately absent from LeavePlanService.ALLOCATABLE_TYPES:
     * you may REQUEST it, and there is no ceiling to allocate for it — that is what makes it unpaid.
     */
    private static final List<String> TYPES = List.of("casual", "sick", "annual", "unpaid", "comp_off");

    private final StaffLeaveRepository leaves;
    private final BmpStaffRepository staff;
    private final AuditLogService audit;

    public StaffLeaveService(StaffLeaveRepository leaves, BmpStaffRepository staff,
                              AuditLogService audit) {
        this.leaves = leaves;
        this.staff = staff;
        this.audit = audit;
    }

    /**
     * Ask for time off.
     *
     * <p>Anyone may request for themselves. A manager requesting ON BEHALF of somebody is
     * deliberately not supported: leave is the employee's statement about their own time, and a
     * row created by somebody else is a record nobody can be held to.
     */
    @Transactional
    public StaffLeave request(String leaveType, LocalDate startsOn, LocalDate endsOn,
                               String halfDay, String reason, StaffPrincipal caller) {

        if (!TYPES.contains(leaveType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pick a leave type: casual, sick, annual, unpaid or comp off.");
        }
        if (startsOn == null || endsOn == null || endsOn.isBefore(startsOn)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The end date can't be before the start date.");
        }
        if (halfDay != null && !startsOn.equals(endsOn)) {
            // A half-day across a range means "half of which day?" — the database refuses it too.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A half day has to be a single date.");
        }

        /*
         * Backdating is allowed for SICK leave only.
         *
         * Somebody who woke up ill did not file a request first, and refusing to record it would
         * mean either a falsified date or an absence that never appears — both worse than a
         * back-dated row. Planned leave in the past is a mistake worth catching.
         */
        if (startsOn.isBefore(LocalDate.now()) && !"sick".equals(leaveType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That start date is in the past. Only sick leave can be recorded after the fact.");
        }

        List<StaffLeave> clashes = leaves.overlapping(caller.staffId(), startsOn, endsOn);
        if (!clashes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You already have leave requested or approved over those dates.");
        }

        StaffLeave saved = leaves.save(new StaffLeave(
                caller.staffId(), leaveType, startsOn, endsOn, halfDay, reason));

        log.info("Leave requested by {} for {} to {} ({}).",
                caller.email(), startsOn, endsOn, leaveType);
        return saved;
    }

    /**
     * Approve or refuse.
     *
     * <h2>Nobody approves their own — including the owner</h2>
     * There is no exception for {@code super_admin}, and that is deliberate. Self-approval is the
     * one thing that makes a leave record meaningless, and an owner who genuinely needs to record
     * their own absence can have a co-founder or ops admin clear it. The alternative — a special
     * case for the most senior person — is exactly where controls erode.
     *
     * <p>Approving flips {@code accepting_tickets} off. Refusing leaves it alone.
     */
    @Transactional
    public StaffLeave decide(UUID leaveId, boolean approve, String note, StaffPrincipal caller) {
        StaffLeave leave = leaves.findById(leaveId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "LEAVE_NOT_FOUND"));

        if (!leave.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This has already been " + leave.getStatus() + ".");
        }
        /*
         * ── HIERARCHY, NOT A FLAT "ops or admin". Session 65. ───────────────────────────────────
         *
         * This used to be: not your own, and caller is ops_admin or super_admin. Two things wrong
         * with it, and they pull in opposite directions.
         *
         * TOO NARROW: a support LEAD — a role that exists to run the desk — could not approve a
         * day off for somebody on their own team. Every request went to ops.
         *
         * TOO WIDE: one ops admin could approve another ops admin's leave. Peers approving each
         * other is not an approval; it is two people agreeing, and it is the same hole as
         * approving your own with one extra step.
         *
         * The target's ROLE decides who may act, so the row has to be loaded first — the same
         * load-then-authorise order as AccountScope, and for the same reason.
         */
        BmpStaff subject = staff.findById(leave.getStaffId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));
        LeaveApprovalScope.requireCanDecide(caller, subject.getRole(), leave.getStaffId());

        /*
         * ── A REFUSAL NEEDS A REASON, AND THE SERVER IS WHERE THAT IS TRUE. Session 65. ─────────
         *
         * The console disables its Refuse button until a note is typed. On its own that is a rule
         * the UI claims and the system does not hold — the endpoint accepted a bare refusal from
         * curl, from a stale tab, or from the next client somebody writes, and the person whose
         * week off was cancelled would find out with nothing attached and have to go and ask why.
         *
         * ASYMMETRIC on purpose. "Yes" needs no defence; "no" always does. Requiring a note on an
         * approval would be ceremony that gets filled with "ok" and teaches people the field means
         * nothing — which is how the mandatory note on a REFUSAL would then get treated too.
         *
         * Five characters, matching the reason floor used for account blocks and salon status
         * changes. It stops an empty string and a single full stop; it cannot stop somebody typing
         * "nope", and no validator can.
         */
        if (!approve && (note == null || note.trim().length() < 5)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say why you're refusing — they're told exactly what you write here.");
        }

        leave.decide(approve ? StaffLeave.APPROVED : StaffLeave.REJECTED, caller.staffId(), note);
        leaves.save(leave);

        if (approve) {
            pauseAssignmentFor(leave);
        }

        audit.record("bmp_staff", caller.staffId(),
                approve ? "LEAVE_APPROVED" : "LEAVE_REJECTED", "staff_leave", leave.getId(),
                Map.of("staffId", leave.getStaffId().toString(),
                        "from", leave.getStartsOn().toString(),
                        "to", leave.getEndsOn().toString()),
                null, caller.email(), caller.role(), note);

        log.info("Leave {} {} by {}.", leave.getId(), approve ? "approved" : "rejected", caller.email());
        return leave;
    }

    /**
     * Take an approved person out of the rotation.
     *
     * <h2>Only when the leave is CURRENT</h2>
     * Leave approved for next month must not stop somebody receiving tickets today. The nightly
     * sweep ({@link #applyTodaysLeave}) is what turns future leave on when the day arrives — this
     * only handles the case of approving something that has already started, which happens with
     * sick leave.
     *
     * <p>Non-fatal: a leave decision that fails because a staff row could not be saved is still a
     * decision, and the sweep will correct the rotation within a day.
     */
    private void pauseAssignmentFor(StaffLeave leave) {
        if (!leave.covers(LocalDate.now())) return;
        try {
            staff.findById(leave.getStaffId()).ifPresent(s -> {
                s.setAcceptingTickets(false);
                staff.save(s);
                log.info("{} is on leave today — taken out of the ticket rotation.", s.getEmail());
            });
        } catch (Exception e) {
            log.warn("Approved leave {} but could not pause ticket assignment ({}). The nightly "
                    + "sweep will correct it.", leave.getId(), e.toString());
        }
    }

    /**
     * The daily reconciliation: everyone on leave today is out, everyone else is back in.
     *
     * <h2>Why a sweep rather than only reacting to decisions</h2>
     * Leave is approved in advance, so the moment that matters is the START of the leave, not the
     * approval. And the RETURN matters just as much: without something that puts people back,
     * anybody who ever took a day off would silently stop receiving tickets forever — which looks
     * like a fair queue getting quieter and is actually a desk losing an agent.
     *
     * <h2>What the "back in" branch can and cannot tell apart</h2>
     * It re-enables only people whose approved leave ended YESTERDAY — never everyone who happens
     * to be paused — so somebody who turned themselves off at the end of a shift last Tuesday is
     * left alone.
     *
     * <p>What it genuinely CANNOT distinguish is a person returning from leave who has also paused
     * themselves by hand. This sweep would switch them back on. That is tolerable only because of
     * WHEN it runs: {@code LeaveRotationJob} fires at 00:05, before anybody has started a shift, so
     * the manual pause it could stamp on has not been set yet. If this is ever moved to run during
     * the working day, that stops being true and the staff row needs to record WHY it was paused —
     * do not move the schedule without doing that first.
     */
    @Transactional
    public int applyTodaysLeave() {
        LocalDate today = LocalDate.now();
        int changed = 0;

        for (StaffLeave leave : leaves.approvedOn(today)) {
            var person = staff.findById(leave.getStaffId()).orElse(null);
            if (person != null && person.isAcceptingTickets()) {
                person.setAcceptingTickets(false);
                staff.save(person);
                changed++;
            }
        }

        // Back from leave: anybody whose leave ended yesterday and who has none today.
        for (StaffLeave ended : leaves.approvedOn(today.minusDays(1))) {
            if (leaves.approvedOn(today).stream()
                    .anyMatch(l -> l.getStaffId().equals(ended.getStaffId()))) {
                continue; // still off
            }
            var person = staff.findById(ended.getStaffId()).orElse(null);
            if (person != null && !person.isAcceptingTickets()) {
                person.setAcceptingTickets(true);
                staff.save(person);
                changed++;
            }
        }

        if (changed > 0) {
            log.info("Leave sweep for {}: {} staff moved in or out of the ticket rotation.",
                    today, changed);
        }
        return changed;
    }

    /** Who is off today — the team calendar's headline. */
    @Transactional(readOnly = true)
    public List<StaffLeave> offOn(LocalDate date) {
        return leaves.approvedOn(date);
    }

    @Transactional(readOnly = true)
    /**
     * The pending queue, filtered to what THIS caller can actually decide. Session 65.
     *
     * <p>Uses {@link LeaveApprovalScope#canDecide} — the same predicate that guards the decision —
     * rather than a second copy of the rule written for the query. A queue that lists rows whose
     * buttons return 403 teaches people to ignore the queue, and two hand-maintained copies of one
     * rule drift apart the first time either is edited.
     */
    public List<StaffLeave> pendingFor(StaffPrincipal caller) {
        if (!LeaveApprovalScope.canDecideAnything(caller)) return List.of();
        return pending().stream()
                .filter(l -> {
                    BmpStaff subject = staff.findById(l.getStaffId()).orElse(null);
                    return subject != null
                            && LeaveApprovalScope.canDecide(caller, subject.getRole(), l.getStaffId());
                })
                .toList();
    }

    public List<StaffLeave> pending() {
        return leaves.findByStatusOrderByCreatedAtAsc(StaffLeave.PENDING);
    }

    @Transactional(readOnly = true)
    public List<StaffLeave> forStaff(UUID staffId) {
        return leaves.findByStaffIdOrderByStartsOnDesc(staffId);
    }

    /**
     * Days taken in a window. Summed in Java, not SQL — see the repository note on why date
     * arithmetic in JPQL is not portable and a wrong leave balance is worse than a slow one.
     */
    @Transactional(readOnly = true)
    public long daysTaken(UUID staffId, LocalDate from, LocalDate to) {
        return leaves.approvedBetween(staffId, from, to).stream()
                .mapToLong(StaffLeave::days)
                .sum();
    }

    /** Withdraw your own pending request. */
    @Transactional
    public StaffLeave cancel(UUID leaveId, StaffPrincipal caller) {
        StaffLeave leave = leaves.findById(leaveId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "LEAVE_NOT_FOUND"));
        if (!leave.getStaffId().equals(caller.staffId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That isn't your leave.");
        }
        if (!leave.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Already " + leave.getStatus() + " — ask whoever decided it.");
        }
        leave.decide(StaffLeave.CANCELLED, caller.staffId(), "Withdrawn by the requester.");
        return leaves.save(leave);
    }
}
