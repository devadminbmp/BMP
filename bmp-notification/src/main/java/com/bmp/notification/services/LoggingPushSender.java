package com.bmp.notification.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The default push sender: logs, never delivers. Session 64.
 *
 * <p>Same shape as {@link LoggingSmsSender}, for the same reason — a developer with no Expo project
 * still gets a service that starts and a message they can read. It says plainly, on every send, that
 * nothing left the building.
 *
 * <h2>Why {@code @ConditionalOnProperty} rather than {@code @Primary}</h2>
 * The SMS stub carries {@code @Primary} and is displaced by a real bean also carrying it, which
 * works but relies on exactly one bean claiming primacy — and two would fail at startup in a way
 * that names neither the cause nor the fix.
 *
 * <p>Here the two implementations are mutually exclusive on the SAME flag:
 * {@code bmp.notification.push.enabled} false (or absent) registers this one, true registers
 * {@link ExpoPushSender}. One flag, two beans, no ambiguity, and impossible to end up with both or
 * neither.
 */
@Service
@ConditionalOnProperty(name = "bmp.notification.push.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    public LoggingPushSender(@Value("${bmp.notification.push.enabled:false}") boolean enabled) {
        log.info("Push channel is OFF — notifications are logged, NOT delivered. "
                + "Set bmp.notification.push.enabled=true to send via Expo. "
                + "Email remains the durable channel either way.");
    }

    @Override
    public void send(String expoPushToken, String title, String body, Map<String, String> data) {
        /*
         * Logs the token, not the body's arguments.
         *
         * A push token is not a secret — it is a delivery address, and it is useless without our
         * Expo credentials. The title and body are already customer-facing text. Nothing here is
         * worth redacting, and redacting it would make the stub useless for the one job it has.
         */
        log.info("[PUSH STUB — nothing sent] token={} title=\"{}\" body=\"{}\" data={}",
                expoPushToken, title, body, data);
    }
}
