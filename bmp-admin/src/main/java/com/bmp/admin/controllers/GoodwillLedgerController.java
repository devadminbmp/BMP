package com.bmp.admin.controllers;

import com.bmp.admin.entities.ApprovalRequest;
import com.bmp.admin.repositories.ApprovalRequestRepository;
import com.bmp.admin.repositories.BmpStaffRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Everything the platform has given away, in one place. Session 59.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS EXISTS
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Darshan: <i>"all refund, coupons etc — all details should be displayed for admin and finance
 * admin, they monitor."</i>
 *
 * <p>Goodwill is the easiest money in any consumer business to lose without noticing. Each
 * individual gesture is defensible — a ₹300 apology for a bad haircut always is — and the total is
 * invisible until it appears in a monthly P&L as a number nobody recognises. The audit log already
 * records every act, but an audit log is a search tool: you find things in it when you already
 * suspect something. This is the opposite — a standing view whose whole job is to make a trend
 * visible before anybody goes looking.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * OWNER AND FINANCE ONLY
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Not ops, and deliberately not support leads. Two reasons:
 *
 * <ul>
 *   <li>It is a <b>financial</b> view — totals, trends, cost per agent. Finance owns that.</li>
 *   <li>Per-agent totals are performance data. A lead who can see "Anita has given away ₹12,000
 *       this month" will manage to that number, and the predictable result is agents refusing
 *       reasonable goodwill to keep their figure down — which costs far more in churn than the
 *       goodwill ever did.</li>
 * </ul>
 *
 * <p>A lead who needs to review one agent's decisions can already do it, case by case, through the
 * approvals they signed off. That is a different question from a leaderboard.
 */
@Tag(name = "Goodwill ledger",
     description = "Every coupon, refund, wallet credit and fee waiver — with totals. Owner and finance only.")
@RestController
@RequestMapping("/api/v1/admin/goodwill")
public class GoodwillLedgerController {

    /** Everything that hands value back. Matches GoodwillCapService — one list, one meaning. */
    private static final List<String> VALUE_ACTIONS =
            List.of("coupon.issue", "refund.issue", "wallet.credit", "booking.waive_fee");

    private final ApprovalRequestRepository approvals;
    private final BmpStaffRepository staff;
    /**
     * V012, Session 59 — the half the approval table cannot see.
     *
     * <p>Goodwill given INSIDE somebody's own authority never becomes an approval request, so a
     * ledger built only from approvals shows every escalated gesture and none of the routine ones.
     * That is backwards for monitoring: the routine ones are the volume, and volume is what a
     * monthly total is made of. Darshan asked for <i>all</i> refunds and coupons; this is the rest
     * of "all".
     */
    private final com.bmp.admin.repositories.GoodwillGrantRepository grants;

    public GoodwillLedgerController(ApprovalRequestRepository approvals, BmpStaffRepository staff,
                                     com.bmp.admin.repositories.GoodwillGrantRepository grants) {
        this.approvals = approvals;
        this.staff = staff;
        this.grants = grants;
    }

    /**
     * @param status EXECUTED means the money actually left. Anything else did not — see the note
     *               on {@code totalGivenPaise} for why that distinction is the whole point.
     */
    public record LedgerRow(
            UUID id, String requestRef, String actionType, long valuePaise, String status,
            String requestedByName, String requestedByRole,
            String approvedByName, UUID ticketId,
            String justification, Instant createdAt, Instant executedAt) {}

    /**
     * @param totalGivenPaise ONLY executed requests. A pending or rejected one has cost nothing,
     *                        and counting it would make the headline number wrong in the direction
     *                        that causes an unnecessary panic.
     * @param pendingPaise    what is currently awaiting a decision — the exposure, not the spend.
     */
    public record LedgerSummary(
            long totalGivenPaise,
            long pendingPaise,
            long failedPaise,
            int executedCount,
            Map<String, Long> byAction,
            Map<String, Long> byRole,
            List<AgentTotal> topAgents) {}

    public record AgentTotal(UUID staffId, String name, String role, long totalPaise, int count) {}

    /**
     * The ledger, newest first.
     *
     * @param days how far back. Defaults to 30 — a month is the period a P&L is read in, and an
     *             unbounded default would make the first load of this screen scan everything.
     */
    @Operation(summary = "Every coupon, refund, credit and waiver",
               description = "Owner and finance. Executed rows are money that actually left; pending rows are exposure.")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE_ADMIN')")
    @GetMapping
    public List<LedgerRow> ledger(@RequestParam(defaultValue = "30") int days,
                                   @RequestParam(required = false) String actionType,
                                   @RequestParam(required = false) String status) {
        Instant from = Instant.now().minus(Math.max(1, days), ChronoUnit.DAYS);

        List<LedgerRow> rows = new java.util.ArrayList<>(approvals.findAll().stream()
                .filter(r -> VALUE_ACTIONS.contains(r.getActionType()))
                .filter(r -> r.getCreatedAt().isAfter(from))
                .filter(r -> actionType == null || actionType.equals(r.getActionType()))
                .filter(r -> status == null || status.equals(r.getStatus()))
                .map(this::toRow)
                .toList());

        /*
         * The within-authority grants.
         *
         * Filtered to those with NO approval id — a grant that HAS one is the same gesture as an
         * approval row already in the list above, and including both would show every escalated
         * coupon twice and double the monthly total.
         *
         * They are always 'executed': a grant row only exists because the thing succeeded (see
         * GoodwillCapService.record). So a status filter for anything else correctly excludes them
         * rather than silently dropping the filter.
         */
        if (status == null || "executed".equals(status)) {
            rows.addAll(grants.findAll().stream()
                    .filter(g -> g.getApprovalRequestId() == null)
                    .filter(g -> g.getCreatedAt().isAfter(from))
                    .filter(g -> actionType == null || actionType.equals(g.getActionType()))
                    .map(this::toRow)
                    .toList());
        }

        rows.sort(Comparator.comparing(LedgerRow::createdAt).reversed());
        return rows;
    }

    /**
     * The numbers somebody actually monitors.
     *
     * <h2>Executed, pending and failed are kept apart</h2>
     * Collapsing them into one "total" is how a monitoring screen becomes misleading: pending money
     * has not been spent, and failed money was approved and never delivered — which is not a cost,
     * it is a customer who is still owed something. Three numbers, three meanings.
     */
    @Operation(summary = "Totals by action, by role, and by agent")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE_ADMIN')")
    @GetMapping("/summary")
    public LedgerSummary summary(@RequestParam(defaultValue = "30") int days) {
        Instant from = Instant.now().minus(Math.max(1, days), ChronoUnit.DAYS);

        List<ApprovalRequest> all = approvals.findAll().stream()
                .filter(r -> VALUE_ACTIONS.contains(r.getActionType()))
                .filter(r -> r.getCreatedAt().isAfter(from))
                .toList();

        List<ApprovalRequest> executed = all.stream()
                .filter(r -> ApprovalRequest.EXECUTED.equals(r.getStatus()))
                .toList();

        // Within-authority goodwill — see the note in ledger() on why these must be counted and
        // why only the ones without an approval id.
        List<com.bmp.admin.entities.GoodwillGrant> direct = grants.findAll().stream()
                .filter(g -> g.getApprovalRequestId() == null)
                .filter(g -> g.getCreatedAt().isAfter(from))
                .toList();

        Map<String, Long> byAction = new java.util.HashMap<>(executed.stream().collect(
                Collectors.groupingBy(ApprovalRequest::getActionType,
                        Collectors.summingLong(ApprovalRequest::getValuePaise))));
        direct.forEach(g -> byAction.merge(g.getActionType(), g.getValuePaise(), Long::sum));

        Map<String, Long> byRole = new java.util.HashMap<>(executed.stream().collect(
                Collectors.groupingBy(ApprovalRequest::getRequestedByRole,
                        Collectors.summingLong(ApprovalRequest::getValuePaise))));
        direct.forEach(g -> byRole.merge(g.getGrantedByRole(), g.getValuePaise(), Long::sum));

        /*
         * Per agent, biggest first, capped at ten.
         *
         * Capped because this is a monitoring aid, not a ranking: a full leaderboard of every agent
         * invites managing to the number, and an agent who refuses reasonable goodwill to protect
         * their figure costs the business far more in churn than the goodwill did. Ten surfaces an
         * outlier without publishing a league table.
         */
        // One map keyed by person, fed from both sources — an agent's total is what they gave,
        // not what they had to ask permission for.
        Map<UUID, long[]> perAgent = new java.util.HashMap<>(); // [total, count]
        for (ApprovalRequest r : executed) {
            perAgent.computeIfAbsent(r.getRequestedByStaffId(), k -> new long[2]);
            long[] acc = perAgent.get(r.getRequestedByStaffId());
            acc[0] += r.getValuePaise();
            acc[1]++;
        }
        for (var g : direct) {
            perAgent.computeIfAbsent(g.getGrantedByStaffId(), k -> new long[2]);
            long[] acc = perAgent.get(g.getGrantedByStaffId());
            acc[0] += g.getValuePaise();
            acc[1]++;
        }

        List<AgentTotal> top = perAgent.entrySet().stream()
                .map(e -> {
                    var person = staff.findById(e.getKey()).orElse(null);
                    return new AgentTotal(
                            e.getKey(),
                            person == null ? "(removed)" : person.getName(),
                            person == null ? "—" : person.getRole(),
                            e.getValue()[0],
                            (int) e.getValue()[1]);
                })
                .sorted(Comparator.comparingLong(AgentTotal::totalPaise).reversed())
                .limit(10)
                .toList();

        return new LedgerSummary(
                executed.stream().mapToLong(ApprovalRequest::getValuePaise).sum()
                        + direct.stream().mapToLong(com.bmp.admin.entities.GoodwillGrant::getValuePaise).sum(),
                all.stream().filter(ApprovalRequest::isPending)
                        .mapToLong(ApprovalRequest::getValuePaise).sum(),
                all.stream().filter(r -> ApprovalRequest.FAILED.equals(r.getStatus()))
                        .mapToLong(ApprovalRequest::getValuePaise).sum(),
                executed.size(),
                byAction, byRole, top);
    }

    /**
     * A within-authority grant, as a ledger row.
     *
     * <p>{@code requestRef} borrows the grant's reference (a coupon code) because there is no
     * approval reference to show — and a blank there would read as missing data on a financial
     * screen. {@code approvedByName} is null, which the console renders as "within their own
     * limit": that is the true and useful answer, not an absence.
     */
    private LedgerRow toRow(com.bmp.admin.entities.GoodwillGrant g) {
        return new LedgerRow(
                g.getId(),
                g.getReference() == null ? "—" : g.getReference(),
                g.getActionType(), g.getValuePaise(), "executed",
                nameOf(g.getGrantedByStaffId()), g.getGrantedByRole(),
                null, null, null, g.getCreatedAt(), g.getCreatedAt());
    }

    /**
     * Approved, then failed to execute.
     *
     * <h2>Its own endpoint because it is its own emergency</h2>
     * Somebody signed off, the customer has almost certainly been told they are getting something,
     * and they have not received it. That is a promise outstanding, not a line in a cost report —
     * and burying it among successful rows is how it stays outstanding for a week.
     */
    @Operation(summary = "Approved but never delivered — needs chasing")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE_ADMIN','ADMIN','OPS_ADMIN')")
    @GetMapping("/failed")
    public List<LedgerRow> failed() {
        return approvals.findByStatusOrderByCreatedAtAsc(ApprovalRequest.FAILED).stream()
                .map(this::toRow)
                .toList();
    }

    private LedgerRow toRow(ApprovalRequest r) {
        return new LedgerRow(
                r.getId(), r.getRequestRef(), r.getActionType(), r.getValuePaise(), r.getStatus(),
                nameOf(r.getRequestedByStaffId()), r.getRequestedByRole(),
                r.getDecidedByStaffId() == null ? null : nameOf(r.getDecidedByStaffId()),
                r.getTicketId(), r.getJustification(), r.getCreatedAt(), r.getExecutedAt());
    }

    /** A name, or a marker. Never null — a blank cell reads as a bug on a financial screen. */
    private String nameOf(UUID staffId) {
        if (staffId == null) return "—";
        return staff.findById(staffId).map(s -> s.getName()).orElse("(removed)");
    }
}
