package com.bmp.admin.services;

import com.bmp.admin.entities.SupportTicket;
import org.springframework.stereotype.Component;

/**
 * What the person waiting is told about their ticket. Session 64.
 *
 * <h2>The problem</h2>
 * A customer opened the chat, typed a message, and saw... their own message. Nothing indicated
 * whether anybody had read it, whether anybody was assigned, or whether it had been handed to a
 * different team. The status column said "open", which is true and useless — it says the same thing
 * ninety seconds in and three days in.
 *
 * <p>Silence in a support chat is not neutral. People read it as being ignored, and the reliable
 * consequence is a second ticket about the same problem, which makes the queue longer and the wait
 * worse for everybody. A status line is cheaper than the duplicate it prevents.
 *
 * <h2>Derived, never stored</h2>
 * Every value here is computed from columns that already exist. Storing a `chat_state` alongside
 * them would create a second source of truth that drifts the first time somebody updates a ticket
 * through a path that forgets to maintain it — and the path most likely to forget is the one added
 * in six months by somebody who has not read this file.
 *
 * <h2>Honest, not reassuring</h2>
 * There is deliberately no "an agent is typing" and no "connecting…". We do not have a live socket,
 * so either would be a comforting animation with nothing behind it. Every state below corresponds
 * to a fact recorded in the database. The one thing worse than an unhelpful status is a status that
 * turns out to be untrue.
 */
@Component
public class TicketHandlingState {

    /** Machine-readable state. The client picks an icon and colour from this, never from the label. */
    public enum State {
        /** Nobody is assigned yet. */
        QUEUED,
        /** Somebody holds it but has not replied yet. */
        ASSIGNED,
        /** A member of staff has replied at least once. */
        ACTIVE,
        /** We have asked the requester something and are waiting on them. */
        WAITING_ON_YOU,
        /** Moved to a different desk — finance, ops, moderation. */
        TRANSFERRED,
        /** Moved up the seniority ladder. */
        ESCALATED,
        /** Done. */
        RESOLVED,
        CLOSED,
    }

    /** What the requester sees. Plain language, no internal vocabulary. */
    public record Handling(State state, String label, String detail, String deskLabel) {}

    public Handling forTicket(SupportTicket t) {
        String status = t.getStatus() == null ? "" : t.getStatus().toLowerCase();
        String desk = t.getHandlingDesk() == null ? "support" : t.getHandlingDesk();

        if ("closed".equals(status)) {
            return new Handling(State.CLOSED, "Closed",
                    "This conversation is closed. Reply to reopen it.", deskLabel(desk));
        }
        if ("resolved".equals(status)) {
            return new Handling(State.RESOLVED, "Resolved",
                    "We think this is sorted. If it isn't, just reply.", deskLabel(desk));
        }
        if ("waiting_on_user".equals(status)) {
            return new Handling(State.WAITING_ON_YOU, "Waiting for you",
                    "We've asked you something — we'll pick this straight back up when you reply.",
                    deskLabel(desk));
        }

        /*
         * Desk and tier are reported BEFORE assignment, deliberately.
         *
         * "With our payments team" answers the question the person is actually asking — who has
         * this — far better than "an agent is assigned". And a transferred ticket that nobody has
         * claimed yet is exactly the one where silence is most alarming and most likely to produce
         * a duplicate.
         */
        if (!"support".equals(desk)) {
            return new Handling(State.TRANSFERRED,
                    "With our " + deskLabel(desk).toLowerCase() + " team",
                    "Your question needed a specialist, so we've passed it on with everything you've "
                            + "already told us. You don't need to explain it again.",
                    deskLabel(desk));
        }
        if (t.getEscalationCount() > 0) {
            return new Handling(State.ESCALATED, "With a senior specialist",
                    "We've moved this to someone more senior, along with the whole conversation.",
                    deskLabel(desk));
        }

        if (t.getAssignedStaffId() == null) {
            return new Handling(State.QUEUED, "Waiting for an agent",
                    "You're in the queue. We usually reply within four hours.", deskLabel(desk));
        }
        if (t.getFirstResponseAt() != null || t.getLastStaffMessageAt() != null) {
            return new Handling(State.ACTIVE, "An agent is on it",
                    "Someone from our team is handling this with you.", deskLabel(desk));
        }
        return new Handling(State.ASSIGNED, "An agent has picked this up",
                "Someone has your ticket and will reply shortly.", deskLabel(desk));
    }

    /**
     * Desk names as a CUSTOMER should read them.
     *
     * "Payments" rather than "finance", because the internal org chart is not the customer's
     * problem and "finance" reads like being sent to accounts payable.
     */
    public String deskLabel(String desk) {
        if (desk == null) return "Support";
        return switch (desk.toLowerCase()) {
            case "finance" -> "Payments";
            case "ops" -> "Operations";
            case "moderation" -> "Trust & safety";
            default -> "Support";
        };
    }

    /** The desks a ticket may be transferred to. Mirrors the CHECK constraint in V014. */
    public boolean isValidDesk(String desk) {
        if (desk == null) return false;
        String d = desk.toLowerCase();
        return d.equals("support") || d.equals("finance") || d.equals("ops") || d.equals("moderation");
    }
}
