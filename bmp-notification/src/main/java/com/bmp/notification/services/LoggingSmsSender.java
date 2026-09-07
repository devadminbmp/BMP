package com.bmp.notification.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Default {@link SmsSender} until a real gateway (MSG91 / Twilio / Gupshup) is wired — logs
 * instead of sending. <b>Nothing reaches a phone.</b> OTPs are delivered for real over
 * <b>email</b> ({@code SmtpEmailSender}), which is the live channel.
 *
 * <h2>Configuration pending: DLT</h2>
 * Transactional SMS in India can't be switched on by buying an API key. TRAI's DLT regime
 * requires the entity, the sender header ("BMPBLR") and every message template to be registered
 * and approved before a single message will be accepted by any operator. Until that's done there
 * is no configuration that would make this work, which is why the placeholder is a stub rather
 * than an unconfigured real client — an unconfigured real client would imply the only thing
 * missing is a credential.
 *
 * <h2>Session 43: the flag is now honoured</h2>
 * This used to log unconditionally. It now respects
 * {@code bmp.notification.channels.sms.enabled} (default false) so that a channel described as
 * "off" is actually silent, and turning it on produces a visible change. A flag a stub ignores
 * misinforms the next reader about what the system does.
 *
 * <h2>The OTP is in the log</h2>
 * {@code message} contains the verification code in plain text. Acceptable only because this
 * bean cannot exist alongside real users — a real environment registers a real {@code @Primary}
 * sender. Seeing this line in a deployed log means the deployment is misconfigured.
 */
@Service
@Primary
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    private final boolean enabled;

    public LoggingSmsSender(@Value("${bmp.notification.channels.sms.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            log.warn("SMS channel is ENABLED but only the STUB sender is registered — messages "
                    + "will be logged, NOT delivered. Configuration pending: DLT registration.");
        } else {
            log.info("SMS channel is disabled (configuration pending — DLT registration). "
                    + "OTPs are delivered over email.");
        }
    }

    @Override
    public void send(String toPhone, String message) {
        if (!enabled) {
            log.debug("SMS disabled — skipping message to {}", toPhone);
            return;
        }
        log.info("[SMS STUB — no real gateway configured, nothing sent] to={} message=\"{}\"",
                toPhone, message);
    }
}
