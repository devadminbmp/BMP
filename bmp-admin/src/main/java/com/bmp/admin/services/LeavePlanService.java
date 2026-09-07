package com.bmp.admin.services;

import com.bmp.admin.entities.BmpStaff;
import com.bmp.admin.entities.LeaveEntitlement;
import com.bmp.admin.entities.LeavePlan;
import com.bmp.admin.entities.StaffLeave;
import com.bmp.admin.repositories.BmpStaffRepository;
import com.bmp.admin.repositories.LeaveEntitlementRepository;
import com.bmp.admin.repositories.LeavePlanRepository;
import com.bmp.admin.repositories.StaffLeaveRepository;
import com.bmp.admin.security.RoleHierarchy;
import com.bmp.admin.security.StaffPermission;
import com.bmp.admin.security.StaffPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LEAVE ENTITLEMENTS AND BALANCES — the main admin as HR. V016, Session 65.
 *
 * <p>Darshan: <i>"he allocate leave plans for each like sick annual etc because still we dont have
 * hr profile main admin can do it ... he can see how many leaves each have taken and what dates
 * etc by everyone even support"</i>
 *
 * <h2>The one resolution rule, written once</h2>
 * <pre>
 *   this person's entitlement row   →  if absent, their role's plan   →  if absent, zero
 * </pre>
 * Every balance, every screen, every warning goes through {@link #allowanceFor}. The alternative —
 * each caller doing its own fallback — is the same shape as the duplicated role lists Session 65
 * spent its first task removing.
 *
 * <h2>Zero is a real answer, not a missing one</h2>
 * A role with no plan resolves to zero days, and a request against it is over-balance from the
 * first day. That is deliberate: inventing a default here would mean the number on the screen came
 * from a Java constant nobody agreed to, and an entitlement nobody agreed to is worse than a
 * visible zero somebody fixes.
 *
 * <h2>Over-balance WARNS, never blocks</h2>
 * Darshan's call, Session 65. A request that exceeds the balance still goes through and the
 * approver sees "12 of 12 casual already used — this would be 3 over". A hard block would mean
 * somebody genuinely ill with no sick days left cannot even ask, which is not a policy anybody
 * wants enforced by a server at 6am.
 */
@Service
public class LeavePlanService {

    private static final Logger log = LoggerFactory.getLogger(LeavePlanService.class);

    /**
     * The types an allowance can exist for.
     *
     * <p>{@code unpaid} is deliberately absent: unpaid leave has no ceiling to allocate — that is
     * what makes it unpaid — so a balance for it would be a number with no meaning. It is still a
     * valid leave TYPE to request (chk_leave_type in V016); it just never has an entitlement.
     */
    public static final List<String> ALLOCATABLE_TYPES = List.of("casual", "sick", "annual", "comp_off");

    private final LeavePlanRepository plans;
    private final LeaveEntitlementRepository entitlements;
    private final StaffLeaveRepository leaves;
    private final BmpStaffRepository staff;
    private final AuditLogService audit;

    public LeavePlanService(LeavePlanRepository plans, LeaveEntitlementRepository entitlements,
                            StaffLeaveRepository leaves, BmpStaffRepository staff, AuditLogService audit) {
        this.plans = plans;
        this.entitlements = entitlements;
        this.leaves = leaves;
        this.staff = staff;
        this.audit = audit;
    }

    // ── what a screen gets back ────────────────────────────────────────────────────────────────

    /**
     * One person, one leave type, one year.
     *
     * @param allowed   what they may take
     * @param taken     what they have had approved, half-days counted as 0.5
     * @param remaining allowed − taken. MAY BE NEGATIVE, and is shown as such: clamping it to zero
     *                  would hide exactly the situation the main admin needs to see.
     * @param source    {@code override}, {@code plan} or {@code none} — so a screen can mark an
     *                  individually-agreed number as deliberate rather than inherited.
     */
    public record TypeBalance(String leaveType, BigDecimal allowed, BigDecimal taken,
                              BigDecimal remaining, String source) {}

    /** A person's whole year: who they are, and where each type stands. */
    public record StaffBalance(UUID staffId, String name, String email, String role,
                               int fyStartYear, List<TypeBalance> types,
                               BigDecimal totalTaken, List<TakenEntry> history) {}

    /** One absence, for the "what dates" half of Darshan's request. */
    public record TakenEntry(UUID leaveId, String leaveType, LocalDate startsOn, LocalDate endsOn,
                             String halfDay, BigDecimal days, String reason) {}

    // ── reading ────────────────────────────────────────────────────────────────────────────────

    /**
     * How many days this person may take of this type this year.
     *
     * <p>THE resolution rule. Everything else calls this.
     */
    @Transactional(readOnly = true)
    public BigDecimal allowanceFor(UUID staffId, String role, String leaveType, int fyStartYear) {
        Optional<LeaveEntitlement> mine =
                entitlements.findByStaffIdAndLeaveTypeAndFyStartYear(staffId, leaveType, fyStartYear);
        if (mine.isPresent()) return mine.get().getDaysAllowed();

        return plans.findByRoleAndLeaveTypeAndFyStartYear(role, leaveType, fyStartYear)
                .map(LeavePlan::getDaysAllowed)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * One person's balance card, with their history.
     *
     * <p>Used by "my leave" (yourself), by an approver looking at a request, and by the HR screen
     * drilling into somebody. Same numbers in all three, because it is the same method — a balance
     * that reads differently depending on who is looking is a bug report waiting to happen.
     */
    @Transactional(readOnly = true)
    public StaffBalance balanceFor(UUID staffId, int fyStartYear) {
        BmpStaff person = staff.findById(staffId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));

        LocalDate from = LeaveYear.startOf(fyStartYear);
        LocalDate to = LeaveYear.endOf(fyStartYear);

        List<StaffLeave> taken = leaves.approvedOverlapping(staffId, from, to);
        return assemble(person, fyStartYear, from, to, taken,
                entitlements.findByStaffIdAndFyStartYear(staffId, fyStartYear),
                plans.findByFyStartYear(fyStartYear));
    }

    /**
     * EVERYBODY's balances. The main admin's HR overview.
     *
     * <p>Three queries total, not three per person: one for the year's leave, one for the year's
     * entitlements, one for the plans. Everything else is grouping in memory. Written this way
     * deliberately — the obvious per-person loop is an N+1 that is invisible on a five-person team
     * and unusable on a fifty-person one, by which point the screen is load-bearing.
     */
    @Transactional(readOnly = true)
    public List<StaffBalance> overview(int fyStartYear) {
        LocalDate from = LeaveYear.startOf(fyStartYear);
        LocalDate to = LeaveYear.endOf(fyStartYear);

        Map<UUID, List<StaffLeave>> leaveByStaff = leaves.approvedOverlappingAll(from, to).stream()
                .collect(Collectors.groupingBy(StaffLeave::getStaffId));
        Map<UUID, List<LeaveEntitlement>> entByStaff = entitlements.findByFyStartYear(fyStartYear).stream()
                .collect(Collectors.groupingBy(LeaveEntitlement::getStaffId));
        List<LeavePlan> yearPlans = plans.findByFyStartYear(fyStartYear);

        return staff.findAllByOrderByCreatedAtDesc().stream()
                /*
                 * Offboarded people are excluded — they have no leave to plan. Suspended people are
                 * NOT: a suspension is usually temporary and their year-to-date record is exactly
                 * what somebody needs while deciding what happens next.
                 */
                .filter(s -> !"offboarded".equalsIgnoreCase(s.getStatus()))
                .map(s -> assemble(s, fyStartYear, from, to,
                        leaveByStaff.getOrDefault(s.getId(), List.of()),
                        entByStaff.getOrDefault(s.getId(), List.of()),
                        yearPlans))
                .toList();
    }

    /** Shared by the one-person and everybody paths so the two can never compute differently. */
    private StaffBalance assemble(BmpStaff person, int fyStartYear, LocalDate from, LocalDate to,
                                  List<StaffLeave> taken, List<LeaveEntitlement> mine,
                                  List<LeavePlan> yearPlans) {

        Map<String, LeaveEntitlement> byType = mine.stream()
                .collect(Collectors.toMap(LeaveEntitlement::getLeaveType, e -> e, (a, b) -> a));
        Map<String, LeavePlan> planByType = yearPlans.stream()
                .filter(p -> p.getRole().equalsIgnoreCase(person.getRole()))
                .collect(Collectors.toMap(LeavePlan::getLeaveType, p -> p, (a, b) -> a));

        // Charged WITHIN the window, so a leave straddling 31 March is split across the two years
        // rather than counted twice or lost entirely.
        Map<String, BigDecimal> takenByType = new HashMap<>();
        for (StaffLeave l : taken) {
            takenByType.merge(l.getLeaveType(), l.daysChargedWithin(from, to), BigDecimal::add);
        }

        List<TypeBalance> types = ALLOCATABLE_TYPES.stream().map(type -> {
            LeaveEntitlement e = byType.get(type);
            LeavePlan p = planByType.get(type);
            BigDecimal allowed;
            String source;
            if (e != null)      { allowed = e.getDaysAllowed(); source = e.getSource(); }
            else if (p != null) { allowed = p.getDaysAllowed(); source = LeaveEntitlement.SOURCE_PLAN; }
            else                { allowed = BigDecimal.ZERO;    source = "none"; }

            BigDecimal used = takenByType.getOrDefault(type, BigDecimal.ZERO);
            return new TypeBalance(type, allowed, used, allowed.subtract(used), source);
        }).toList();

        /*
         * Total taken includes UNPAID, which has no allowance and so appears in no TypeBalance row.
         * Summed from the leave itself rather than from the rows above, or unpaid days would be
         * absent from the one number somebody scans the HR screen for.
         */
        BigDecimal totalTaken = taken.stream()
                .map(l -> l.daysChargedWithin(from, to))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<TakenEntry> history = taken.stream()
                .sorted(Comparator.comparing(StaffLeave::getStartsOn).reversed())
                .map(l -> new TakenEntry(l.getId(), l.getLeaveType(), l.getStartsOn(), l.getEndsOn(),
                        l.getHalfDay(), l.daysChargedWithin(from, to), l.getReason()))
                .toList();

        return new StaffBalance(person.getId(), person.getName(), person.getEmail(), person.getRole(),
                fyStartYear, types, totalTaken, history);
    }

    /**
     * The warning an approver sees, or null when the request fits.
     *
     * <p>Returns a SENTENCE rather than a boolean because the approver needs the numbers to decide,
     * and "over balance: true" tells them nothing they can act on.
     */
    @Transactional(readOnly = true)
    public String overBalanceWarning(UUID staffId, String role, String leaveType,
                                     LocalDate startsOn, LocalDate endsOn, String halfDay) {
        if (!ALLOCATABLE_TYPES.contains(leaveType)) return null;   // unpaid has no ceiling

        int fy = LeaveYear.of(startsOn);
        BigDecimal allowed = allowanceFor(staffId, role, leaveType, fy);
        LocalDate from = LeaveYear.startOf(fy), to = LeaveYear.endOf(fy);

        BigDecimal used = leaves.approvedOverlapping(staffId, from, to).stream()
                .filter(l -> l.getLeaveType().equals(leaveType))
                .map(l -> l.daysChargedWithin(from, to))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal asking = (halfDay != null && !halfDay.isBlank() && startsOn.equals(endsOn))
                ? new BigDecimal("0.5")
                : BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(startsOn, endsOn) + 1);

        BigDecimal over = used.add(asking).subtract(allowed);
        if (over.signum() <= 0) return null;

        return "%s of %s %s days already used this year (%s). This request of %s would be %s over."
                .formatted(plain(used), plain(allowed), leaveType, LeaveYear.label(fy),
                           plain(asking), plain(over));
    }

    /** "12" not "12.0"; "0.5" stays "0.5". Nobody writes twelve point zero on a leave form. */
    private static String plain(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }

    // ── writing. MAIN ADMIN ONLY. ──────────────────────────────────────────────────────────────

    /**
     * Darshan's call, Session 65: only the main admin allocates.
     *
     * <p>Checked here, in the service, and not only by an annotation on the controller — an
     * entitlement is somebody's pay in another form, and the rule that guards it should sit next to
     * the write rather than one layer away where a new endpoint can miss it.
     */
    private void requireMainAdmin(StaffPrincipal caller, String what) {
        if (caller == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!StaffPermission.SUPER_ADMIN.equalsIgnoreCase(caller.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the main admin can " + what + ".");
        }
    }

    /** The year's plan for every role and type — what the HR screen edits. */
    @Transactional(readOnly = true)
    public List<LeavePlan> planFor(int fyStartYear) {
        return plans.findByFyStartYear(fyStartYear).stream()
                .sorted(Comparator
                        .comparingInt((LeavePlan p) -> -RoleHierarchy.rankOf(p.getRole()))
                        .thenComparing(LeavePlan::getLeaveType))
                .toList();
    }

    /** Set or change the org-wide allowance for a role. */
    @Transactional
    public LeavePlan setPlan(String role, String leaveType, int fyStartYear,
                             BigDecimal days, BigDecimal carryForward, StaffPrincipal caller, String ip) {
        requireMainAdmin(caller, "change a leave plan");
        validateType(leaveType);
        validateDays(days);
        if (!RoleHierarchy.isStaffRole(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a console role: " + role);
        }
        if (carryForward != null && carryForward.compareTo(days) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Carry-forward can't exceed the allowance itself.");
        }

        LeavePlan plan = plans.findByRoleAndLeaveTypeAndFyStartYear(role, leaveType, fyStartYear)
                .orElseGet(() -> new LeavePlan(role, leaveType, fyStartYear, days, carryForward, caller.staffId()));
        BigDecimal previous = plan.getDaysAllowed();
        plan.setDaysAllowed(days);
        plan.setCarryForwardMax(carryForward == null ? BigDecimal.ZERO : carryForward);
        plan.setSetByStaffId(caller.staffId());
        plan.touch();
        plan = plans.save(plan);

        audit.record("bmp_staff", caller.staffId(), "LEAVE_PLAN_SET", "leave_plan", plan.getId(),
                Map.of("role", role, "type", leaveType, "fy", String.valueOf(fyStartYear),
                       "from", String.valueOf(previous), "to", String.valueOf(days)),
                ip, caller.email(), caller.role(), null);
        log.info("Leave plan set: {} {} {} = {} days by {}", role, leaveType, fyStartYear, days, caller.email());
        return plan;
    }

    /**
     * Set one person's allowance, overriding their role's plan.
     *
     * <p>Always written as {@code override} — reaching this method means a human typed a number for
     * a named individual, which is the definition of the flag. A future plan rollout will leave it
     * alone, which is the whole point of recording it.
     */
    @Transactional
    public LeaveEntitlement setEntitlement(UUID staffId, String leaveType, int fyStartYear,
                                           BigDecimal days, String note, StaffPrincipal caller, String ip) {
        requireMainAdmin(caller, "change somebody's leave entitlement");
        validateType(leaveType);
        validateDays(days);
        BmpStaff person = staff.findById(staffId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));

        LeaveEntitlement row = entitlements
                .findByStaffIdAndLeaveTypeAndFyStartYear(staffId, leaveType, fyStartYear)
                .orElseGet(() -> new LeaveEntitlement(staffId, leaveType, fyStartYear, days,
                        LeaveEntitlement.SOURCE_OVERRIDE, note, caller.staffId()));
        BigDecimal previous = row.getDaysAllowed();
        row.setDaysAllowed(days);
        row.setSource(LeaveEntitlement.SOURCE_OVERRIDE);
        row.setNote(note);
        row.setSetByStaffId(caller.staffId());
        row.touch();
        row = entitlements.save(row);

        audit.record("bmp_staff", caller.staffId(), "LEAVE_ENTITLEMENT_SET", "bmp_staff", staffId,
                Map.of("who", String.valueOf(person.getEmail()), "type", leaveType,
                       "fy", String.valueOf(fyStartYear),
                       "from", String.valueOf(previous), "to", String.valueOf(days),
                       "note", note == null ? "" : note),
                ip, caller.email(), caller.role(), null);
        log.info("Leave entitlement set: {} {} {} = {} days by {}",
                person.getEmail(), leaveType, fyStartYear, days, caller.email());
        return row;
    }

    /**
     * Drop an override so the person falls back to their role's plan.
     *
     * <p>Deleting the row rather than writing the plan's number into it: copying the number would
     * freeze today's plan against them forever, so a later org-wide raise would silently skip the
     * one person whose override was "removed".
     */
    @Transactional
    public void clearEntitlement(UUID staffId, String leaveType, int fyStartYear,
                                 StaffPrincipal caller, String ip) {
        requireMainAdmin(caller, "change somebody's leave entitlement");
        entitlements.findByStaffIdAndLeaveTypeAndFyStartYear(staffId, leaveType, fyStartYear)
                .ifPresent(row -> {
                    entitlements.delete(row);
                    audit.record("bmp_staff", caller.staffId(), "LEAVE_ENTITLEMENT_CLEARED",
                            "bmp_staff", staffId,
                            Map.of("type", leaveType, "fy", String.valueOf(fyStartYear),
                                   "was", String.valueOf(row.getDaysAllowed())),
                            ip, caller.email(), caller.role(), null);
                });
    }

    private static void validateType(String leaveType) {
        if (!ALLOCATABLE_TYPES.contains(leaveType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Leave allowances exist for " + String.join(", ", ALLOCATABLE_TYPES)
                    + ". Unpaid leave has no ceiling to allocate — that is what makes it unpaid.");
        }
    }

    /** Half-days are the only fraction. 12.3 is a typo, and the database rejects it too. */
    private static void validateDays(BigDecimal days) {
        if (days == null || days.signum() < 0 || days.compareTo(BigDecimal.valueOf(365)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Days must be between 0 and 365.");
        }
        BigDecimal halves = days.multiply(BigDecimal.valueOf(2));
        if (halves.stripTrailingZeros().scale() > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use whole days or halves — 12 or 12.5, not " + days.toPlainString() + ".");
        }
    }
}
