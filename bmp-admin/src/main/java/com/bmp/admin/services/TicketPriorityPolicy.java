package com.bmp.admin.services;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Decides a ticket's priority from who is asking and what about. Session 64.
 *
 * <h2>Why this is a policy object and not an {@code if} in the raise method</h2>
 * Priority is a business rule that will change — the first time a salon complains that their
 * outage sat behind three refund questions, this table gets edited. Keeping it in one named class
 * with one entry point means that edit happens in one place, is reviewable on its own, and is
 * testable without standing up a ticket.
 *
 * <h2>The ranking, and the reasoning behind it</h2>
 * Two axes: <b>who</b> is blocked, and <b>how badly</b>.
 *
 * <p><b>A salon outranks a customer, and that is a commercial judgement, not a snobbish one.</b>
 * A customer with a booking problem has one spoiled appointment. A salon whose listing is down has
 * every appointment it would have taken today spoiled, plus the customers who quietly went
 * elsewhere and will never report it. The blast radius differs by orders of magnitude, so the
 * queue position should too.
 *
 * <p><b>Money outranks everything except a salon being unable to trade.</b> A payout that has not
 * arrived is somebody's staff not being paid this week. It is the one category where the cost of
 * waiting compounds daily rather than resolving itself.
 *
 * <p><b>Nothing here is ever set to 'urgent' automatically.</b> Urgent means a human looked and
 * decided this jumps the queue — if the system can mint it, every category eventually becomes
 * urgent, agents learn to ignore the flag, and the top of the queue stops meaning anything. The
 * policy tops out at 'high' and leaves 'urgent' to a person. This is the important line in the
 * file.
 *
 * <h2>An agent's decision always wins</h2>
 * {@code priority_auto} (V013) records whether the current value came from here or from a person.
 * Re-derivation skips anything a human touched. Without that flag the only safe policy would be
 * "derive once at creation and never again", which would mean a ticket that later gets linked to a
 * salon keeps a priority computed when we thought it was a customer.
 */
@Component
public class TicketPriorityPolicy {

    public static final String URGENT = "urgent";
    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";

    /**
     * Requester kinds that represent a BUSINESS rather than a consumer.
     *
     * Matches the `raised_by_type` vocabulary from V002: customer / salon_owner / manager /
     * bmp_staff. Manager counts — a manager reporting the salon cannot take bookings is reporting
     * the same outage the owner would, and making them wait behind consumer tickets because of
     * their job title helps nobody.
     */
    private static final Set<String> SALON_SIDE = Set.of("salon_owner", "manager", "salon");

    /** Categories where waiting costs money that compounds. */
    private static final Set<String> MONEY = Set.of("payment_issue", "refund_dispute", "payout");

    /** Categories where the requester currently cannot use the platform at all. */
    private static final Set<String> BLOCKING = Set.of("account_issue", "listing_down", "app_broken");

    /**
     * The derived priority for a new ticket.
     *
     * @param raisedByType the `raised_by_type` value — never null in practice, but treated as an
     *                     unknown consumer if it is, because a null must not crash ticket creation.
     *                     Losing a ticket is far worse than mis-ranking one.
     */
    public String derive(String raisedByType, String category) {
        String who = raisedByType == null ? "" : raisedByType.trim().toLowerCase();
        String what = category == null ? "" : category.trim().toLowerCase();

        boolean salonSide = SALON_SIDE.contains(who);
        boolean money = MONEY.contains(what);
        boolean blocking = BLOCKING.contains(what);

        // A business that cannot trade, or cannot be paid. The top of what the system may assign.
        if (salonSide && (money || blocking)) return HIGH;

        // A salon asking anything else still sits above consumer traffic: their questions are
        // usually about many bookings rather than one.
        if (salonSide) return MEDIUM;

        // A consumer whose money or access is affected. Same tier as a salon's routine question —
        // deliberately, because "my refund hasn't arrived" and "how do I change my hours" are about
        // equally pressing, and pretending otherwise in either direction is unfair to somebody.
        if (money || blocking) return MEDIUM;

        return LOW;
    }

    /**
     * Whether {@link #derive} may overwrite the value currently on the ticket.
     *
     * <p>Split out from derive() rather than folded in, so the caller reads as the rule it is
     * enforcing: <em>the system does not overrule a person</em>.
     */
    public boolean mayOverwrite(boolean currentValueWasAutomatic) {
        return currentValueWasAutomatic;
    }

    /** True if a person is allowed to set this value at all. Guards the API against typos. */
    public boolean isValidPriority(String priority) {
        if (priority == null) return false;
        String p = priority.trim().toLowerCase();
        return p.equals(URGENT) || p.equals(HIGH) || p.equals(MEDIUM) || p.equals(LOW);
    }

    /**
     * A short, honest explanation of why a ticket sits where it does, shown in the console.
     *
     * <p>Agents distrust invisible ranking, and rightly — a queue that reorders itself for reasons
     * nobody can see gets worked around rather than worked. One sentence removes that.
     */
    public String explain(String raisedByType, String category, boolean automatic) {
        if (!automatic) return "Set by a member of the team.";
        String who = raisedByType == null ? "" : raisedByType.trim().toLowerCase();
        boolean salonSide = SALON_SIDE.contains(who);
        String what = category == null ? "" : category.trim().toLowerCase();

        if (salonSide && MONEY.contains(what)) return "A salon is waiting on money.";
        if (salonSide && BLOCKING.contains(what)) return "A salon may be unable to trade.";
        if (salonSide) return "Raised by a salon, which usually affects many bookings.";
        if (MONEY.contains(what)) return "Money is involved.";
        if (BLOCKING.contains(what)) return "The customer is blocked from using the app.";
        return "Routine customer question.";
    }
}
