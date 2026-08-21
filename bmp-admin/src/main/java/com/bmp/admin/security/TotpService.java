package com.bmp.admin.security;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * TOTP (RFC 6238) — the second factor for staff console login.
 *
 * <p><b>Why hand-rolled rather than a library.</b> TOTP is ~60 lines of HMAC and modular
 * arithmetic, fully specified by an RFC, with test vectors. Adding a dependency for it means
 * another artefact to keep patched in a service whose whole job is being hard to break into.
 * This implementation follows RFC 6238 §4 and RFC 4226 §5.3 exactly; the algorithm is
 * deliberately boring and the parts people get wrong are called out in comments.
 *
 * <p>Compatible with Google Authenticator, Authy, 1Password and anything else that speaks the
 * standard {@code otpauth://} URI.
 */
@Service
public class TotpService {

    /** 30-second steps — the near-universal default; changing it breaks every authenticator app. */
    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;

    /**
     * How many steps either side of "now" we accept.
     *
     * <p>±1 step means a code stays valid for roughly 90 seconds in total. That's the standard
     * trade-off: it absorbs clock drift between the phone and the server (which is common and
     * not the user's fault) without widening the window enough to matter to an attacker who
     * would need the shared secret anyway.
     */
    private static final int ALLOWED_DRIFT_STEPS = 1;

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final SecureRandom random = new SecureRandom();

    /** A fresh 160-bit secret, Base32-encoded as authenticator apps expect. */
    public String generateSecret() {
        byte[] buf = new byte[20]; // 160 bits — RFC 4226 recommends at least this
        random.nextBytes(buf);
        return base32Encode(buf);
    }

    /**
     * The enrolment URI, rendered as a QR code by the console.
     *
     * <p>The issuer appears twice by convention (as a label prefix and as a parameter) because
     * older apps read one and newer apps read the other.
     */
    public String provisioningUri(String secret, String staffEmail) {
        String issuer = urlEncode("BMP Admin");
        String label = issuer + ":" + urlEncode(staffEmail);
        return "otpauth://totp/%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d"
                .formatted(label, secret, issuer, DIGITS, TIME_STEP_SECONDS);
    }

    /**
     * Verify a submitted code against the secret.
     *
     * <p>Checks the current step and one either side. Comparison is constant-time: a
     * short-circuiting {@code equals} on a 6-digit code leaks, in principle, how many leading
     * digits were right, and this is exactly the kind of place that shortcut gets taken without
     * thinking.
     */
    public boolean verify(String secret, String submittedCode) {
        if (secret == null || submittedCode == null) return false;
        String cleaned = submittedCode.replaceAll("\\s", "");
        if (cleaned.length() != DIGITS) return false;

        long currentStep = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        byte[] key = base32Decode(secret);

        for (int drift = -ALLOWED_DRIFT_STEPS; drift <= ALLOWED_DRIFT_STEPS; drift++) {
            if (constantTimeEquals(generateCode(key, currentStep + drift), cleaned)) {
                return true;
            }
        }
        return false;
    }

    /** RFC 4226 §5.3 — HMAC-SHA1, dynamic truncation, modulo 10^digits. */
    private String generateCode(byte[] key, long step) {
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(step).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);

            // Dynamic truncation: the low 4 bits of the last byte pick where to read from.
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)          // 0x7F masks the sign bit —
                    | ((hash[offset + 1] & 0xFF) << 16)         // forgetting this is THE classic
                    | ((hash[offset + 2] & 0xFF) << 8)          // TOTP bug, and it only shows up
                    | (hash[offset + 3] & 0xFF);                // for ~half of all time steps.

            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            // Both algorithms are guaranteed present on every JVM; this cannot happen in practice.
            throw new IllegalStateException("HMAC-SHA1 unavailable", e);
        }
    }

    /** Length-independent, non-short-circuiting comparison. */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        int diff = a.length() ^ b.length();
        for (int i = 0; i < a.length() && i < b.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // ---- Base32 (RFC 4648, no padding) ----------------------------------------------------

    private String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    private byte[] base32Decode(String encoded) {
        String clean = encoded.trim().replace("=", "").toUpperCase();
        int bits = 0;
        int buffer = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char c : clean.toCharArray()) {
            int index = BASE32_ALPHABET.indexOf(c);
            if (index < 0) continue; // ignore separators users paste in
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
