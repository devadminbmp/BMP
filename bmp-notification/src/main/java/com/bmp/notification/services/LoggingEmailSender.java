package com.bmp.notification.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Fallback EmailSender — logs at INFO instead of sending, same reasoning as
 * {@link LoggingSmsSender}. This is what makes OTP/signup testable end-to-end with zero
 * external accounts: read the code from the console instead of an inbox.
 *
 * <p>Session 14: which email sender is active is now config-driven, not a hardcoded
 * {@code @Primary}. This bean is created when {@code bmp.notification.email-provider} is
 * {@code log} OR unset (the default) — so it stays the default for any dev without SMTP
 * credentials. Set {@code bmp.notification.email-provider=smtp} (+ the {@code spring.mail.*}
 * creds) to switch to real delivery via {@link SmtpEmailSender} instead. Exactly one of the
 * two beans exists at a time, so there's no {@code @Primary} ambiguity anymore.
 */
@Service
@ConditionalOnProperty(name = "bmp.notification.email-provider", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String toEmail, String subject, String body) {
        log.info("[EMAIL STUB — no SMTP provider configured] to={} subject=\"{}\" body=\"{}\"",
                toEmail, subject, body);
    }
}
