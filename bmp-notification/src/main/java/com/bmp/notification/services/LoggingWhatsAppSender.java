package com.bmp.notification.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * The only {@link WhatsAppSender} that exists today — it logs what WOULD have been sent.
 *
 * <p><b>Nothing reaches a phone.</b> There is no WhatsApp Business account yet; this class is
 * the placeholder that keeps the call sites honest until there is one. OTPs are delivered for
 * real over <b>email</b> ({@code SmtpEmailSender}), which is the live channel.
 *
 * <h2>Why a stub rather than nothing at all</h2>
 * The alternative was to not call WhatsApp anywhere until it's real. That reliably produces a
 * different bug: the day the gateway is wired, someone has to find every place a message should
 * go out and add a call — and they will miss some, because the list only exists in their head.
 * With the stub in place the call sites are already correct, already reviewed, and already
 * logging; going live is one bean and one config flag, and the log lines tell you in advance
 * exactly what traffic you're about to start paying for.
 *
 * <h2>The OTP is in the log</h2>
 * {@code body} contains the verification code in plain text. That is acceptable here and only
 * here: this bean cannot exist in an environment with real users, because a real environment has
 * a real sender marked {@code @Primary}. If you ever see this line in a deployed log, the
 * deployment is misconfigured — treat it as an incident, not a curiosity.
 */
@Service
@Primary
public class LoggingWhatsAppSender implements WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingWhatsAppSender.class);

    /**
     * Off by default, and honoured rather than ignored.
     *
     * <p>A flag that a stub quietly disregards is worse than no flag — it tells the reader the
     * channel is controllable when it isn't. When this is false the stub says nothing at all, so
     * "disabled" looks like disabled in the log, and turning it on later changes something
     * visible. See {@code bmp.notification.channels.whatsapp.enabled}.
     */
    private final boolean enabled;

    public LoggingWhatsAppSender(
            @Value("${bmp.notification.channels.whatsapp.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            log.warn("WhatsApp channel is ENABLED but only the STUB sender is registered — "
                    + "messages will be logged, NOT delivered. Configuration pending: no WhatsApp "
                    + "Business account / approved templates yet.");
        } else {
            log.info("WhatsApp channel is disabled (configuration pending — no Business account). "
                    + "OTPs are delivered over email.");
        }
    }

    @Override
    public void send(String toPhone, String templateName, String body) {
        if (!enabled) {
            log.debug("WhatsApp disabled — skipping template '{}' to {}", templateName, toPhone);
            return;
        }
        log.info("[WHATSAPP STUB — configuration pending, nothing sent] to={} template={} body=\"{}\"",
                toPhone, templateName, body);
    }
}
