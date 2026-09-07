package com.bmp.notification.services;

/**
 * Push delivery, behind an interface for the same reason as {@link SmsSender}. Session 64.
 *
 * <p>{@link LoggingPushSender} is the default and logs instead of sending, so a developer with no
 * Expo project still gets a working service and a visible message. {@link ExpoPushSender} takes
 * over when {@code bmp.notification.push.enabled=true}.
 *
 * <h2>Why push, when email already works</h2>
 * Email is fine for things a person is actively waiting for — an OTP, with the app open. It is a
 * poor channel for everything that matters LATER: your appointment is tomorrow, the salon just
 * cancelled, support replied. Those need to interrupt, and push is the only channel we have that
 * does, at no per-message cost.
 *
 * <h2>Push is a NOTIFICATION, never a delivery guarantee</h2>
 * A phone can be off, out of storage, or have notifications denied at the OS level, and none of
 * those are reported back to us in any useful time frame. So push is always an ADDITION to a
 * durable channel, never a replacement: anything that matters is still emailed and still visible in
 * the app. Treating a push as "they have been told" is how a salon cancellation ends with somebody
 * standing outside a locked door.
 */
public interface PushSender {

    /**
     * @param title shown bold on the lock screen. Short — Android truncates around 40 characters
     *              and iOS around 35, and the useful half of a truncated title is the first half.
     * @param body  one or two lines. Assume it is read at a glance, in a queue with other apps.
     * @param data  opened when the notification is tapped, e.g. {@code {"screen":"booking",
     *              "id":"..."}} . Never put personal data here: the payload is stored by the OS,
     *              relayed through Expo and Google/Apple, and is not ours once it leaves.
     */
    void send(String expoPushToken, String title, String body, java.util.Map<String, String> data);
}
