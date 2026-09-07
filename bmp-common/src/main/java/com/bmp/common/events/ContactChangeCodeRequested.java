package com.bmp.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Somebody signed in has asked to change their own phone or email, and needs to confirm it with a
 * code. Session 65.
 *
 * <h2>Why this is not just another {@code OtpRequested}</h2>
 * Reusing the login event would have worked and would have sent the wrong email. The login code
 * arrives as "Your BMP verification code — enter it in the app to continue", which tells somebody
 * confirming a contact change nothing about what they are confirming — and, worse, tells somebody
 * who did NOT request it that a login is being attempted rather than that their address is being
 * taken over. The second reading is the one that matters: this email is often the only warning an
 * account owner gets.
 *
 * <p>So it carries {@link #newPhone} and {@link #newEmail}, and the message names the change.
 *
 * <h2>It carries a plaintext code, like OtpRequested</h2>
 * Same reasoning and same limits: short-lived, single-use, relayed within seconds. Not a pattern
 * to copy for anything longer-lived.
 *
 * @param aggregateId the contact_change_request row id
 * @param sentToEmail where the code goes. For an EMAIL change this is the NEW address, which is
 *                    what makes the code proof of ownership. For a PHONE change it is the address
 *                    already on the account, which proves only that the requester is the account
 *                    holder — SMS cannot deliver until DLT registration completes.
 * @param newPhone    null when only the email is changing.
 * @param newEmail    null when only the phone is changing.
 */
public record ContactChangeCodeRequested(
        UUID aggregateId,
        UUID userId,
        String sentToEmail,
        String newPhone,
        String newEmail,
        String code,
        Instant expiresAt
) implements DomainEvent {

    @Override
    public String eventType() {
        return "contact_change.code_requested";
    }
}
