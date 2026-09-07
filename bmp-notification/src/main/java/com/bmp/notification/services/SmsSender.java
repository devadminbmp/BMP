package com.bmp.notification.services;

/**
 * Session 6: SMS delivery is behind an interface on purpose — no gateway account (MSG91,
 * Twilio, Gupshup) exists yet, so {@link LoggingSmsSender} is the only implementation for now
 * (logs the message instead of sending it). Swap it for a real one by adding a {@code @Service}
 * bean and moving {@code @Primary} onto it — no caller-side changes, since everything depends
 * on this interface rather than a concrete class.
 *
 * <p><b>Session 43:</b> this used to say "SMS/WhatsApp". WhatsApp now has its own interface —
 * see {@link WhatsAppSender}, which explains why the two can't share one. Configuration pending
 * on both; <b>email is the live channel</b>.
 */
public interface SmsSender {
    void send(String toPhone, String message);
}
