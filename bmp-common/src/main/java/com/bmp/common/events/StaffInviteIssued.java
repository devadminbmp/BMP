package com.bmp.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A salon issued a one-time invite code and gave us an address to send it to. Session 65 (V028).
 *
 * <h2>The problem this solves</h2>
 * Darshan:
 *
 *   "while inviting we need take stylist email id also hence we can send him invitation and code
 *    beautifully in email but u r taking only name and number"
 *
 * Until now the code existed only on the owner's screen. Getting it to the invitee therefore meant
 * reading a 32-character token aloud, or retyping it into WhatsApp — and a mistyped token produces
 * "INVITE_NOT_FOUND_OR_ALREADY_USED", which the invitee reasonably reads as the app being broken
 * rather than as a transcription error four characters in.
 *
 * <h2>Why the token travels in the event</h2>
 * The whole purpose of the email IS the token, so there is nothing to protect by withholding it —
 * and bmp-notification must never make a synchronous call back into bmp-salon to render a message.
 * Same rule as every other event here: everything the email needs is carried inline.
 *
 * <p>What that does mean is that this event is SENSITIVE. The token is a credential until it is
 * redeemed or expires: anybody holding it can join this salon's team under the invited phone
 * number. It is short-lived (48h), single-use, and locked to that phone, which is what makes the
 * exposure acceptable — but it must never be logged in full, and the outbox row must not outlive
 * its usefulness.
 *
 * <h2>Why there is no "invite emailed" confirmation event coming back</h2>
 * bmp-salon marks {@code emailed_at} when the send SUCCEEDS, at the point of sending. A round
 * trip through the dispatcher would tell the owner nothing they act on differently, and would
 * leave a window in which the column lies about a channel they are the fallback for.
 *
 * @param aggregateId  the invite id
 * @param salonId      the inviting salon
 * @param salonName    named, because "a salon has invited you" is not actionable
 * @param role         manager | stylist — the email says what they are being invited AS, since
 *                     those mean very different things once redeemed
 * @param inviteeName  what the owner typed. For the greeting only; never trusted as their real name
 * @param inviteeEmail where to send it. This event is not published at all when it is null
 * @param phone        the number the code is LOCKED TO. Stated in the email because signing up with
 *                     a different number is the single commonest way this flow fails
 * @param token        the code itself. See the note above — this is a credential
 * @param expiresAt    so the email can say "expires Thursday" rather than "expires soon"
 */
public record StaffInviteIssued(
        UUID aggregateId,
        UUID salonId,
        String salonName,
        String role,
        String inviteeName,
        String inviteeEmail,
        String phone,
        String token,
        Instant expiresAt
) implements DomainEvent {

    @Override
    public String eventType() {
        return "salon.staff_invite.issued";
    }
}
