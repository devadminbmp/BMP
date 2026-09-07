package com.bmp.common.events;

import java.util.UUID;

/**
 * A support agent replied to a ticket, and the person who raised it needs to know. Session 48.
 *
 * <h2>The silence this ends</h2>
 * {@code SupportDeskController.reply} recorded the message and stopped. The console showed the
 * conversation, the agent believed they had answered, and the customer heard nothing at all —
 * their ticket looked ignored, so the rational next step is to raise another one, or to stop
 * trusting the support channel entirely.
 *
 * <p>That TODO sat in the code for several sessions with an honest comment saying exactly this:
 * "an agent believing they have replied when the customer heard nothing is worse than no reply
 * feature at all". It was right, and this is the fix.
 *
 * <h2>The message body travels with the event</h2>
 * Rather than an id the dispatcher would have to call back for. bmp-notification must never need
 * a synchronous call into bmp-admin to render an email — an outage there would otherwise become
 * an outage here, and this is the message telling somebody their problem is being dealt with.
 *
 * <p>INTERNAL NOTES MUST NEVER PRODUCE ONE OF THESE. An internal note is staff talking to staff
 * about a customer, and mailing it to them is the single worst failure this feature can have.
 * The publisher checks; this record carries no {@code internalNote} flag on purpose, so there is
 * no way to construct one that is "internal but sent" and no flag for a future reader to get
 * backwards.
 *
 * @param aggregateId  the ticket id
 * @param ticketRef    TCK-2026-00042 — what the person quotes back to us
 * @param subject      the ticket's subject, so the email can name what it is about
 * @param messageText  the agent's reply, verbatim. This IS the email.
 * @param agentName    who replied. A named human reads very differently from "BMP Support"
 * @param requesterEmail where to send it. Null means we hold no address — logged, not guessed
 * @param requesterName  for the greeting; null falls back to a neutral one
 */
public record SupportTicketReplied(
        UUID aggregateId,
        String ticketRef,
        String subject,
        String messageText,
        String agentName,
        String requesterEmail,
        String requesterName
) implements DomainEvent {

    @Override
    public String eventType() {
        return "support_ticket.replied";
    }
}
