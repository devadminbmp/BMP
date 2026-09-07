package com.bmp.notification.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.nio.charset.StandardCharsets;

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

    /**
     * Prove we can actually reach and authenticate against the SMTP server, at startup.
     * Session 47.
     *
     * <h2>Why this is worth a few seconds of boot time</h2>
     * Without it, a wrong or revoked app password is indistinguishable from working right up
     * until the first person tries to sign up — and then it surfaces as "the OTP didn't arrive",
     * which sends you looking at the app, the gateway, Kafka and the outbox before you get to
     * SMTP. The credentials are known at boot; the failure should be too.
     *
     * <p>Deliberately NOT fatal. bmp-notification also handles SMS and WhatsApp stubs and writes
     * the notification_log; refusing to start over email would take all of that down as well, and
     * a service that won't boot is a bigger outage than one that boots and says loudly what it
     * can't do.
     *
     * <p>{@code testConnection()} does a full connect + AUTH + quit. It is the same handshake a
     * real send performs, so passing here means the credentials genuinely work — not merely that
     * the host resolves.
     */
    @PostConstruct
    void verifyConnection() {
        if (!(mailSender instanceof JavaMailSenderImpl impl)) {
            log.info("Mail sender is not JavaMailSenderImpl — skipping the startup connection test.");
            return;
        }
        if (impl.getUsername() == null || impl.getUsername().isBlank()) {
            log.error("SMTP USERNAME IS EMPTY. email-provider=smtp is set but BMP_SMTP_USERNAME is "
                    + "not — every send will fail authentication. Load local-secrets.ps1 in the "
                    + "shell that starts this service (or use .\\run-service.ps1 bmp-notification).");
            return;
        }
        try {
            impl.testConnection();
            log.info("SMTP OK — connected and authenticated to {}:{} as {}. Real email will be sent.",
                    impl.getHost(), impl.getPort(), impl.getUsername());
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            log.error("SMTP CONNECTION FAILED to {}:{} as {} — NO EMAIL WILL BE DELIVERED. "
                    + "Cause: {}: {}",
                    impl.getHost(), impl.getPort(), impl.getUsername(),
                    cause.getClass().getSimpleName(), cause.getMessage());
            log.error("  · AuthenticationFailedException -> the Gmail app password is wrong or "
                    + "revoked. Generate a new one at myaccount.google.com > App passwords.");
            log.error("  · ConnectException / timeout   -> the network or a firewall is blocking "
                    + "outbound port {}.", impl.getPort());
        }
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

    /**
     * Designed HTML, with the plain-text alternative in the same message. Session 48.
     *
     * <p>{@code MimeMessageHelper(msg, true, "UTF-8")} builds {@code multipart/alternative}, and
     * {@code setText(text, html)} sets both parts in the correct order — text first, HTML second,
     * which is what the standard requires for a client to prefer the HTML. Passing them the wrong
     * way round produces a message that silently shows as raw markup in some clients.
     *
     * <p>UTF-8 is not optional here: the rupee sign and the en dashes in our copy are outside
     * Latin-1, and without the charset they arrive as mojibake in exactly the mail our owners see
     * first.
     */
    @Override
    public void sendHtml(String toEmail, String subject, String htmlBody, String textBody) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            // `true` = multipart. Without it, setText(text, html) throws rather than degrading.
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(textBody, htmlBody);   // (plain, html) — order matters
            mailSender.send(mime);
            log.info("HTML email sent to={} subject=\"{}\"", toEmail, subject);
        } catch (MessagingException | MailException e) {
            log.warn("HTML email send FAILED to={} subject=\"{}\": {}", toEmail, subject, e.toString());
            // Wrapped, because MessagingException is checked and the caller
            // (NotificationDispatcher) treats any throw as "mark this log row FAILED".
            throw new MailPreparationException("Could not send HTML email to " + toEmail, e);
        }
    }
}
