package com.bmp.notification.services;

/**
 * Session 6: SMS/WhatsApp delivery is behind an interface on purpose — no gateway account
 * (MSG91, Twilio, etc.) exists yet, so {@link LoggingSmsSender} is the only implementation
 * for now (logs the message instead of sending it). Swap it for a real implementation by
 * adding a new {@code @Service} bean and removing {@code LoggingSmsSender}'s
 * {@code @Primary} — no caller-side changes needed since everything depends on this
 * interface, not a concrete class.
 */
public interface SmsSender {
    void send(String toPhone, String message);
}
