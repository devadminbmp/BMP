package com.bmp.notification.services;

/**
 * WhatsApp delivery — a channel of its own, not a flavour of SMS.
 *
 * <h2>Session 43: why this isn't just {@link SmsSender} with a different transport</h2>
 * It was tempting to reuse {@code SmsSender}, since today both stubs do the same thing (log).
 * That would have been a mistake the moment either becomes real, because the two channels
 * differ in ways that reach all the way back to the caller:
 *
 * <ul>
 *   <li><b>Templates are pre-approved.</b> WhatsApp Business will not deliver arbitrary text to
 *       a user who hasn't messaged you in the last 24 hours — it has to be a template registered
 *       with and approved by Meta, referenced by name, with positional variables. So the real
 *       implementation needs {@code (templateName, variables)}, not a finished string. SMS takes
 *       a finished string.</li>
 *   <li><b>Delivery is asynchronous and reported back.</b> WhatsApp acknowledges receipt and
 *       then reports sent/delivered/read over a webhook, minutes later. SMS via most Indian
 *       gateways is fire-and-forget with a DLR poll.</li>
 *   <li><b>Failure means different things.</b> "No WhatsApp account for this number" is a
 *       permanent, expected outcome that should fall back to SMS. An SMS failure is usually
 *       transient and should retry.</li>
 *   <li><b>They are separately regulated.</b> SMS in India needs DLT registration of both the
 *       sender header and the template; WhatsApp needs a verified Business account. Either can
 *       be live while the other isn't — which is exactly the state we're in.</li>
 * </ul>
 *
 * <p>Collapsing them now would mean a caller written against "send this text" and a rewrite of
 * every call site later. One interface per channel costs a file and buys the seam.
 *
 * <h2>Current state: CONFIGURATION PENDING</h2>
 * No WhatsApp Business account exists yet, so {@link LoggingWhatsAppSender} is the only
 * implementation and nothing is actually delivered. <b>Email is the live channel</b> — see
 * {@code SmtpEmailSender}; OTPs really do arrive there. To go live: register the Business
 * account, get the {@code otp_code} template approved, add a real {@code @Service} implementing
 * this interface, mark it {@code @Primary}, and flip
 * {@code bmp.notification.channels.whatsapp.enabled} to true. No caller changes.
 */
public interface WhatsAppSender {

    /**
     * Send a templated WhatsApp message.
     *
     * @param toPhone      E.164, e.g. {@code +919876500003}
     * @param templateName the Meta-approved template's registered name, e.g. {@code otp_code}.
     *                     Passed rather than a finished string because that is what the real API
     *                     takes — see the class note. The stub logs it so the intent is visible.
     * @param body         the rendered human-readable text. Used by the stub for logging and by
     *                     a future fallback path; the real sender sends the template, not this.
     */
    void send(String toPhone, String templateName, String body);
}
