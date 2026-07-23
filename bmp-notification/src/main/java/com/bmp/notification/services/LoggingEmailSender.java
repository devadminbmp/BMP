package com.bmp.notification.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Default EmailSender until an SMTP/transactional-email provider account exists — logs at
 * INFO instead of sending, same reasoning as {@link LoggingSmsSender}. This is what makes
 * OTP/signup testable end-to-end right now with zero external accounts: read the code
 * straight from the console instead of an inbox. Swap to {@link SmtpEmailSender} for real
 * delivery by removing {@code @Primary} here (and adding it there, or deleting this class).
 */
@Service
@Primary
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String toEmail, String subject, String body) {
        log.info("[EMAIL STUB — no SMTP provider configured] to={} subject=\"{}\" body=\"{}\"",
                toEmail, subject, body);
    }
}
