package com.bmp.notification.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Real push delivery via Expo's push service. Session 64.
 *
 * <h2>Why Expo rather than FCM directly</h2>
 * The app is an Expo app. Expo relays to FCM and APNs on our behalf, which means no Firebase
 * project, no {@code google-services.json}, no APNs signing key, and no custom dev build before the
 * first notification arrives. The contract this class exposes — token, title, body, data — is the
 * same one FCM would need, so moving to raw FCM later replaces this file and touches nothing else.
 *
 * <h2>No credentials required, and why that is not a security hole</h2>
 * Expo accepts unauthenticated sends to a valid {@code ExponentPushToken}. That sounds alarming and
 * is not: the token IS the capability. Anyone holding it can already notify that device, and it only
 * ever leaves the device to reach us. There is no access token to leak because there is no account
 * to compromise — which is also why the token is treated as a delivery address rather than a secret.
 *
 * <p>(Expo supports an optional access token for rate-limit attribution. Deliberately not wired: it
 * would be a real secret to manage, for a benefit we do not yet need at our volume.)
 *
 * <h2>Failure is expected and must stay quiet</h2>
 * Tokens die constantly — reinstall, device restore, an app left unopened long enough. Expo answers
 * with {@code DeviceNotRegistered}, and that is the normal end of a token's life, not an incident.
 * This class reports it back so the row can be disabled, and never throws: a dead phone must not be
 * able to fail the transaction that was trying to tell somebody their appointment moved.
 */
@Service
@ConditionalOnProperty(name = "bmp.notification.push.enabled", havingValue = "true")
public class ExpoPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushSender.class);

    /** Expo's public send endpoint. */
    private static final String EXPO_URL = "https://exp.host/--/api/v2/push/send";

    private final RestClient http;

    public ExpoPushSender(@Value("${bmp.notification.push.expo-url:" + EXPO_URL + "}") String url) {
        this.http = RestClient.builder().baseUrl(url).build();
        log.info("Push channel is LIVE via Expo ({}). Push is an ADDITION to email, never a "
                + "replacement — anything that matters is still emailed.", url);
    }

    @Override
    public void send(String expoPushToken, String title, String body, Map<String, String> data) {
        /*
         * Reject anything that is not an Expo token BEFORE the call.
         *
         * A raw FCM token, a leftover placeholder, or an empty string all produce a 400 from Expo
         * with a message that reads like our fault. Checking the shape here turns that into one
         * clear warning naming the actual problem.
         */
        if (expoPushToken == null || !expoPushToken.startsWith("ExponentPushToken[")) {
            log.warn("Refusing to push to a token that is not an Expo token: {}. "
                    + "The device registered something else — check the app's registration call.",
                    expoPushToken);
            return;
        }

        Map<String, Object> message = Map.of(
                "to", expoPushToken,
                "title", title,
                "body", body,
                "data", data == null ? Map.of() : data,
                // Wakes the screen and plays a sound. Correct for everything we send: we do not
                // send anything a person would not want interrupting them, and if we ever do, that
                // message should not be push at all.
                "priority", "high",
                "sound", "default");

        try {
            /*
             * Expo answers 200 even for a token it rejects — the per-message outcome is inside the
             * body under `data.status`. Treating HTTP 200 as success would mark every send
             * delivered, including to tokens Expo just told us are dead, and the log would show a
             * healthy push channel reaching nobody.
             */
            Map<?, ?> response = http.post()
                    .body(List.of(message))
                    .retrieve()
                    .body(Map.class);

            Object payload = response == null ? null : response.get("data");
            String outcome = String.valueOf(payload);

            if (outcome.contains("DeviceNotRegistered")) {
                // Normal end of a token's life. INFO, not WARN — alerting on this would train
                // everybody to ignore the channel's alerts.
                log.info("Push token {} is no longer registered; it should be disabled.", expoPushToken);
                throw new DeviceNotRegisteredException(expoPushToken);
            }
            if (outcome.contains("\"status\":\"error\"") || outcome.contains("status=error")) {
                log.warn("Expo rejected a push to {}: {}", expoPushToken, outcome);
                return;
            }
            log.debug("Pushed to {} — {}", expoPushToken, outcome);

        } catch (DeviceNotRegisteredException e) {
            throw e;
        } catch (Exception e) {
            /*
             * Swallowed on purpose. See the class note: a phone we cannot reach must never fail the
             * work that was trying to reach it. The durable channel (email) has already been queued
             * by the dispatcher, and notification_log records the attempt either way.
             */
            log.warn("Push to {} failed ({}). Email is unaffected.", expoPushToken, e.toString());
        }
    }

    /** Signals a token the caller should disable. Not an error condition — see the class note. */
    public static class DeviceNotRegisteredException extends RuntimeException {
        public DeviceNotRegisteredException(String token) {
            super("Push token no longer registered: " + token);
        }
    }
}
