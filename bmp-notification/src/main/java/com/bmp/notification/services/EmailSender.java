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

    /** Plain text only. Still used for internal/diagnostic mail that no customer reads. */
    void send(String toEmail, String subject, String body);

    /**
     * A designed HTML email, with a plain-text alternative in the same message. Session 48.
     *
     * <h2>Why both parts, always</h2>
     * This sends {@code multipart/alternative}: the client picks. That is not politeness, it is
     * deliverability — a message with an HTML part and no text part is a well-known spam signal,
     * and Gmail's own guidance is to send both. It also covers screen readers, watch previews,
     * the inbox preview line, and anyone who has images and HTML switched off.
     *
     * <p><b>The text part must carry the actual information, not "view this in a browser".</b>
     * The one thing that matters in our OTP mail is a six-digit number; a fallback that omits it
     * to advertise the HTML version is worse than no fallback at all.
     *
     * @param textBody the same message as prose — a real fallback, not a stub
     */
    void sendHtml(String toEmail, String subject, String htmlBody, String textBody);
}
