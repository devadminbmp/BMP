package com.bmp.notification.services;

/**
 * An interface with two implementations, selected by config (Session 14):
 * {@link LoggingEmailSender} (console log, the default) and {@link SmtpEmailSender} (real
 * JavaMailSender delivery). Which one is active is driven by
 * {@code bmp.notification.email-provider} (log | smtp) via {@code @ConditionalOnProperty} —
 * exactly one bean exists at a time. Set it to {@code smtp} (+ the {@code spring.mail.*}
 * creds) to send real email. See LoggingEmailSender's javadoc for the switch details.
 */
public interface EmailSender {
    void send(String toEmail, String subject, String body);
}
