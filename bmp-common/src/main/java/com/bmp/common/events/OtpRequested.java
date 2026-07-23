package com.bmp.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted by bmp-auth-service every time an OTP is generated (login or signup), carrying
 * the plaintext code so bmp-notification-service can actually deliver it. This is the one
 * event type allowed to carry a secret in its payload — it lives in
 * {@code common_schema.outbox} → Kafka topic {@code bmp.events} only briefly (relayed and
 * consumed within seconds under normal operation) and the OTP itself is already short-lived
 * (5 minutes, see bmp-auth's {@code otp-ttl-minutes}) and single-use. Not a pattern to copy
 * for anything longer-lived than an OTP.
 *
 * <p>{@code email} is null when the phone number belongs to an existing user with no email
 * on file yet — delivery falls back to phone-only in that case (see
 * bmp-notification's OTP consumer).
 */
public record OtpRequested(
        UUID aggregateId,   // the otp_requests row id
        String phone,
        String email,       // nullable
        String code,
        Instant expiresAt
) implements DomainEvent {

    @Override
    public String eventType() {
        return "otp.requested";
    }
}
