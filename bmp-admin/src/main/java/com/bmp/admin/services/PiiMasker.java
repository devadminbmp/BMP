package com.bmp.admin.services;

import org.springframework.stereotype.Component;

/**
 * Masks personal data before it leaves this service.
 *
 * <h2>Why masking happens on the server</h2>
 * If the console masked values in the browser, the real phone number would still have crossed
 * the network, sat in the HTTP cache, and appeared in anyone's devtools. Masking is only
 * meaningful if the unmasked value never leaves the server without a justification attached.
 *
 * <h2>How much to leave visible</h2>
 * Enough for a support agent to confirm they have the right person on the phone — "does your
 * number end 210?" — and not enough to write down. Last three digits of a phone, first two
 * characters of an email local part. Anything more and the mask is decorative; anything less
 * and agents start revealing every record just to identify someone, which defeats the point.
 */
@Component
public class PiiMasker {

    /** {@code +919876543210} → {@code +91 98••• ••210} */
    public String phone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.length() < 6) return "•••";
        String country = digits.startsWith("+") ? digits.substring(0, 3) : "";
        String rest = digits.substring(country.length());
        if (rest.length() < 5) return country + " •••";
        String head = rest.substring(0, 2);
        String tail = rest.substring(rest.length() - 3);
        return "%s %s••• ••%s".formatted(country, head, tail).trim();
    }

    /** {@code ananya.rao@gmail.com} → {@code an•••@gmail.com} */
    public String email(String email) {
        if (email == null || email.isBlank()) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "•••";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        // The domain stays visible: it's rarely identifying on its own, and it's genuinely
        // useful — "their address is on a domain that bounces" is a diagnosis.
        String head = local.length() >= 2 ? local.substring(0, 2) : local;
        return head + "•••" + domain;
    }
}
