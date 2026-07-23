package com.bmp.notification.services;

/**
 * Same pattern as {@link SmsSender}: an interface with a logging-only default
 * ({@link LoggingEmailSender}) and a real implementation ({@link SmtpEmailSender}) that
 * isn't wired in as the primary bean yet — no SMTP provider account exists. Swap to real
 * delivery by removing {@code @Primary} from LoggingEmailSender (see that class's javadoc).
 */
public interface EmailSender {
    void send(String toEmail, String subject, String body);
}
