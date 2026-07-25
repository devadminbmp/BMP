package com.bmp.notification.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Real email delivery via JavaMailSender (SMTP) — used for OTP and signup/booking mails.
 *
 * <p>Session 14: activated. This bean is created ONLY when
 * {@code bmp.notification.email-provider=smtp} (otherwise {@link LoggingEmailSender} is the
 * active bean — see its javadoc). It reads the standard {@code spring.mail.*} properties
 * (host / port / username / password + STARTTLS, all from {@code BMP_SMTP_*} env vars in
 * application.yml) — so the SMTP password is NEVER committed to the repo, it's supplied per
 * environment as an env var. Works with any SMTP provider: Gmail (with an app password),
 * Brevo, Mailgun, Amazon SES, etc.
 *
 * <p>{@code from} address: for providers that enforce sender identity (Gmail especially),
 * {@code bmp.notification.email-from} should match the authenticated SMTP username, or the
 * send is rejected. For Brevo/SES it must be a verified sender.
 *
 * <p>A send failure throws {@link MailException}; the caller ({@link NotificationDispatcher})
 * catches it and marks the notification_log row FAILED, so one bad address doesn't break the
 * dispatch loop — we log it here too for visibility.
 */
@Service
@ConditionalOnProperty(name = "bmp.notification.email-provider", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpEmailSender(JavaMailSender mailSender,
                            @Value("${bmp.notification.email-from:no-reply@bemyprofessional.in}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        log.info("SmtpEmailSender active — real email delivery enabled (from={})", fromAddress);
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            // Subject only, never the body — an OTP body contains the code.
            log.info("Email sent to={} subject=\"{}\"", toEmail, subject);
        } catch (MailException e) {
            log.warn("Email send FAILED to={} subject=\"{}\": {}", toEmail, subject, e.getMessage());
            throw e; // let NotificationDispatcher mark the log row FAILED
        }
    }
}
