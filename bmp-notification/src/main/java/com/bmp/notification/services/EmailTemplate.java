package com.bmp.notification.services;

import java.util.List;

/**
 * The BMP email card — one branded shell every customer-facing email is poured into. Session 48.
 *
 * <h2>Why a hand-built shell and not a templating engine</h2>
 * Thymeleaf or Freemarker would be the obvious reach, and both are the wrong tool here. Email
 * HTML is not web HTML: it is a 1999 subset, and the hard part is not substituting values (which
 * is trivial) but knowing which tags survive. Adding a template engine buys string interpolation
 * we already have and costs a dependency, a resource-loading path, and a second place where a
 * broken email can hide. Four emails do not need a rendering pipeline.
 *
 * <h2>The rules this file obeys, and why each one exists</h2>
 * <ul>
 *   <li><b>Tables for layout.</b> Outlook (2016–2021, and the desktop client most Indian salon
 *       owners' accountants use) renders through Microsoft Word's HTML engine. It supports no
 *       flexbox, no grid, and no {@code float} it can be trusted with. Nested tables are ugly and
 *       they are what actually works.</li>
 *   <li><b>Inline styles only.</b> Gmail strips {@code <style>} blocks in several contexts —
 *       notably the Gmail app on Android when the mail is forwarded. A class-based design looks
 *       perfect in every preview tool and arrives unstyled on the device most of our owners read
 *       mail on.</li>
 *   <li><b>No background images, no web fonts.</b> Both are blocked by default in most clients.
 *       Anything load-bearing must be text or a background colour.</li>
 *   <li><b>A max width of 600px.</b> The oldest constant in email design; wider gets clipped in
 *       Outlook's reading pane.</li>
 *   <li><b>Every value escaped.</b> A salon named "Curl & Co" must not corrupt the document, and
 *       a name is attacker-controlled text arriving from a signup form. See {@link #escape}.</li>
 * </ul>
 *
 * <h2>Dark mode</h2>
 * We set explicit colours on every text element rather than relying on inheritance. Gmail and
 * Apple Mail invert unstyled text in dark mode; a card that declares only its background ends up
 * white-on-white for a real slice of readers.
 */
public final class EmailTemplate {

    private EmailTemplate() {}

    // ── Brand palette. Mirrors BMP-FE/src/theme/tokens.js exactly. ─────────────────────────────
    // Copied rather than imported because there is no shared source of truth between a TypeScript
    // theme and a Java service. If the app's blue changes, change it here too — that duplication
    // is the honest cost of the two living in different languages, and it is written down here so
    // the next person finds it rather than wondering why the email looks off-brand.
    private static final String BLUE_800   = "#1E3A5F";  // header band
    private static final String BLUE_700   = "#2B4C7E";  // primary
    private static final String BLUE_100   = "#E8EFF7";  // soft tint panels
    private static final String BLUE_50    = "#F4F7FB";  // page wash
    private static final String SURFACE    = "#FFFFFF";
    private static final String BORDER     = "#E2E8F1";
    private static final String TEXT       = "#1B2733";
    private static final String TEXT_MUTED = "#5A6B82";
    private static final String SUCCESS    = "#2E7D5B";
    private static final String WARNING    = "#B9822B";

    /** Shown in the footer of every email. One place to change them. */
    public static final String SUPPORT_EMAIL = "support@bemyprofessional.in";
    public static final String SUPPORT_PHONE = "+91 80 4718 2200";

    /**
     * A labelled row inside a details panel — "Salon ID", "a1b2c3…".
     *
     * @param label  the caption; short, sentence case
     * @param value  the value; escaped for you
     * @param mono   render the value in a monospace face. For identifiers people copy or read
     *               aloud: proportional fonts make 0/O and 1/l genuinely ambiguous, and a salon
     *               ID quoted wrongly to support wastes everybody's time.
     */
    public record Row(String label, String value, boolean mono) {
        public static Row of(String label, String value) { return new Row(label, value, false); }
        public static Row id(String label, String value) { return new Row(label, value, true); }
    }

    /** Accent colour for the header band and any callout — one per email, by tone. */
    public enum Tone {
        /** Neutral / informational. */      INFO(BLUE_800, BLUE_100),
        /** Something good happened. */      GOOD(SUCCESS, "#E6F2EC"),
        /** Action needed from them. */      ATTENTION(WARNING, "#FBF3E4");

        final String accent;
        final String tint;
        Tone(String accent, String tint) { this.accent = accent; this.tint = tint; }
    }

    /**
     * Render the full document.
     *
     * @param preheader the line clients show next to the subject in the inbox list. Easy to skip
     *                  and worth more than most of the body — it is the difference between
     *                  "Be My Professional" and "Your salon is live. Here is your salon ID." in
     *                  the one place a person decides whether to open the mail. Hidden in the
     *                  body itself by the usual zero-size trick.
     * @param heading   the big line inside the card
     * @param intro     one or two sentences under the heading, plain text
     * @param rows      optional details panel; pass an empty list for none
     * @param callout   optional highlighted box (an OTP code, a rejection reason); null for none
     * @param outro     optional closing paragraph; null for none
     */
    public static String card(String preheader,
                              String heading,
                              String intro,
                              List<Row> rows,
                              String callout,
                              String outro,
                              Tone tone) {
        StringBuilder b = new StringBuilder(4096);

        b.append("<!DOCTYPE html><html lang=\"en\"><head>")
         .append("<meta charset=\"UTF-8\">")
         // Stops iOS Mail auto-shrinking the card to fit, which makes 14px text unreadable.
         .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         // Tells clients we designed for both schemes, so they stop force-inverting our colours.
         .append("<meta name=\"color-scheme\" content=\"light dark\">")
         .append("<title>").append(escape(heading)).append("</title>")
         .append("</head>")
         .append("<body style=\"margin:0;padding:0;background-color:").append(BLUE_50)
         .append(";-webkit-font-smoothing:antialiased;\">");

        // The preheader. Zero-height + zero-opacity + off-screen: the belt-and-braces combination
        // that hides it in Gmail, Outlook AND Apple Mail. Any one alone leaks in at least one.
        b.append("<div style=\"display:none;font-size:1px;color:").append(BLUE_50)
         .append(";line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden;\">")
         .append(escape(preheader))
         // Padding characters, so the client doesn't pull real body copy in after the preheader.
         .append("&#847;&zwnj;&nbsp;".repeat(30))
         .append("</div>");

        b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
         .append("style=\"background-color:").append(BLUE_50).append(";padding:24px 12px;\"><tr><td align=\"center\">");

        b.append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
         .append("style=\"width:100%;max-width:600px;background-color:").append(SURFACE)
         .append(";border:1px solid ").append(BORDER).append(";border-radius:14px;overflow:hidden;")
         .append("font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\">");

        // ── Header band ───────────────────────────────────────────────────────────────────────
        // The wordmark is TEXT, not an image. Images are blocked by default in Outlook and in
        // Gmail for unknown senders, and a brand header that is invisible on first contact is
        // worse than no header — first contact is exactly when this mail is sent.
        b.append("<tr><td style=\"background-color:").append(tone.accent).append(";padding:22px 32px;\">")
         .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
         .append("<td style=\"background-color:").append(SURFACE)
         .append(";width:34px;height:34px;border-radius:9px;text-align:center;vertical-align:middle;")
         .append("font-size:18px;font-weight:700;color:").append(tone.accent).append(";\">B</td>")
         .append("<td style=\"padding-left:12px;font-size:17px;font-weight:600;color:#FFFFFF;")
         .append("letter-spacing:0.2px;\">Be My Professional</td>")
         .append("</tr></table></td></tr>");

        // ── Body ──────────────────────────────────────────────────────────────────────────────
        b.append("<tr><td style=\"padding:32px;\">")
         .append("<h1 style=\"margin:0 0 12px;font-size:23px;line-height:1.3;font-weight:700;color:")
         .append(TEXT).append(";\">").append(escape(heading)).append("</h1>")
         .append("<p style=\"margin:0;font-size:15px;line-height:1.65;color:").append(TEXT_MUTED)
         .append(";\">").append(escape(intro)).append("</p>");

        if (callout != null && !callout.isBlank()) {
            b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
             .append("style=\"margin:24px 0 0;\"><tr><td style=\"background-color:").append(tone.tint)
             .append(";border-radius:10px;padding:18px 20px;font-size:15px;line-height:1.6;color:")
             .append(TEXT).append(";\">").append(escape(callout)).append("</td></tr></table>");
        }

        if (rows != null && !rows.isEmpty()) {
            b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
             .append("style=\"margin:24px 0 0;border:1px solid ").append(BORDER)
             .append(";border-radius:10px;\">");
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                String sep = i == 0 ? "" : "border-top:1px solid " + BORDER + ";";
                b.append("<tr><td style=\"").append(sep).append("padding:13px 18px;font-size:13px;color:")
                 .append(TEXT_MUTED).append(";width:38%;\">").append(escape(r.label())).append("</td>")
                 .append("<td style=\"").append(sep).append("padding:13px 18px;font-size:14px;font-weight:600;color:")
                 .append(TEXT).append(";")
                 .append(r.mono() ? "font-family:'SF Mono',Consolas,Menlo,monospace;font-size:13px;" : "")
                 // Long ids and addresses must wrap rather than stretch the table past 600px.
                 .append("word-break:break-word;\">")
                 .append(escape(r.value())).append("</td></tr>");
            }
            b.append("</table>");
        }

        if (outro != null && !outro.isBlank()) {
            b.append("<p style=\"margin:24px 0 0;font-size:15px;line-height:1.65;color:")
             .append(TEXT_MUTED).append(";\">").append(escape(outro)).append("</p>");
        }

        b.append("</td></tr>");

        // ── Footer ────────────────────────────────────────────────────────────────────────────
        b.append("<tr><td style=\"background-color:").append(BLUE_50).append(";border-top:1px solid ")
         .append(BORDER).append(";padding:22px 32px;font-size:12px;line-height:1.7;color:")
         .append(TEXT_MUTED).append(";\">")
         .append("<strong style=\"color:").append(TEXT).append(";\">Need a hand?</strong><br>")
         .append("Email <a href=\"mailto:").append(SUPPORT_EMAIL).append("\" style=\"color:")
         .append(BLUE_700).append(";text-decoration:none;\">").append(SUPPORT_EMAIL).append("</a>")
         .append(" or call ").append(SUPPORT_PHONE).append("<br><br>")
         .append("Be My Professional &middot; Bengaluru, India<br>")
         .append("<span style=\"color:#8A99AD;\">You're getting this because you have a BMP account.</span>")
         .append("</td></tr>");

        b.append("</table></td></tr></table></body></html>");
        return b.toString();
    }

    /**
     * HTML-escape. Applied to EVERY interpolated value without exception.
     *
     * <p>Not a nicety. Salon names, owner names and admin rejection reasons all arrive as free
     * text from a form, and all three end up in this document. {@code &} alone breaks rendering
     * for an ordinary business name like "Curl &amp; Co"; the angle brackets are the injection
     * case. Escaping at the single point of interpolation is the only version of this that stays
     * correct as emails are added — a rule that each new caller must remember is a rule that
     * will be forgotten.
     */
    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
