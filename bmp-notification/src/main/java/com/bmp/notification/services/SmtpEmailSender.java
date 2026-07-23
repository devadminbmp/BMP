package com.bmp.notification.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Real email delivery via JavaMailSender (SMTP). Not {@code @Primary} — {@link
 * LoggingEmailSender} is the active bean until an SMTP provider account exists (Gmail app
 * password, or a transactional-email provider like Brevo/Mailgun's free tier both work).
 * Configure with the standard {@code spring.mail.*} properties (host/port/username/password
 * — see application.yml's BMP_MAIL_* env vars) once one is chosen, then flip which class
 * carries {@code @Primary}.
 */
@Service
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpEmailSender(JavaMailSender mailSender,
                            @Value("${bmp.notification.email-from:no-reply@bemyprofessional.in}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
