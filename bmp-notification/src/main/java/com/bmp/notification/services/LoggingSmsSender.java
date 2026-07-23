package com.bmp.notification.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Default SmsSender until a real gateway (MSG91/Twilio/etc.) is wired — logs at INFO instead
 * of sending. Fine for local/dev (you can read the OTP straight from the console), NOT fine
 * for staging/prod; there's no guardrail here preventing this from running in prod besides
 * remembering to add a real implementation before then. Flagging loudly rather than quietly.
 */
@Service
@Primary
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void send(String toPhone, String message) {
        log.info("[SMS STUB — no real gateway configured] to={} message=\"{}\"", toPhone, message);
    }
}
