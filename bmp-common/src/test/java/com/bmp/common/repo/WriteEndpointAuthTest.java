package com.bmp.common.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} must carry its own authorization.
 *
 * <h2>The incident this encodes (Session 40)</h2>
 * {@code PUT /api/v1/reviews/{id}} was reachable with <b>no credential at all</b>. It had no
 * {@code @PreAuthorize}, and bmp-review's {@code public-paths} contained
 * {@code /api/v1/reviews/*} — added so a review could be <i>read</i> without logging in. Anyone
 * on the internet could rewrite any review.
 *
 * <p>The trap: <b>public-paths are path-only and method-blind.</b> The pattern doesn't say
 * "GET", it says "this URL". A read that should be public silently publishes the write sitting
 * on the same path. Nothing in the config hints at it, because from the matcher's point of view
 * nothing is wrong.
 *
 * <p>The Session 29 sweep missed it twice over. It looked for endpoints with no credential, and
 * this one <i>looked</i> covered by a deliberate public path. It also missed
 * {@code PUT /salons/{id}}, which had no {@code @PreAuthorize} and accepted {@code status} — so
 * any logged-in user could approve their own salon, or rename anyone else's. <b>"Authenticated"
 * is not "authorised", and a check that only asks the first question keeps finding the second
 * kind of hole acceptable.</b>
 *
 * <h2>Why GETs are exempt</h2>
 * Plenty are legitimately public — browsing is the one thing that must work before signing in.
 * Flagging them all would produce noise, and a check that cries wolf trains people to ignore it,
 * which costs more than it saves.
 */
class WriteEndpointAuthTest {

    /**
     * Credential-ESTABLISHING endpoints. You cannot require a role in order to obtain one.
     *
     * <p>Each is gated by something other than a role, and that something IS the credential:
     * <ul>
     *   <li>{@code login} / {@code oauth2} — email+password, or Google's signed assertion</li>
     *   <li>{@code totp/verify|enrol} — the challenge token issued by step 1 of admin login</li>
     *   <li>{@code activate} — a one-time activation code</li>
     *   <li>{@code otp} — the code sent to the phone (and now single-use, see V005)</li>
     *   <li>{@code refresh} / {@code logout} — the refresh token itself</li>
     *   <li>{@code auth/me} — reads only the caller's own principal</li>
     *   <li>{@code webhook} — a provider signature (Razorpay, when it lands)</li>
     * </ul>
     *
     * <p><b>Adding to this list adds an endpoint anyone on the internet can call.</b> Do it only
     * when the endpoint genuinely cannot yet know who the caller is, and say what gates it.
     */
    private static final Pattern EXEMPT = Pattern.compile(
            "/(login|logout|refresh|activate|signup|register|otp|auth/me|webhook|totp|oauth2)");

    private static final Pattern WRITE_MAPPING = Pattern.compile(
            "@(Post|Put|Patch|Delete)Mapping(?:\\(\"([^\"]*)\"\\))?");

    private static final Pattern BASE_PATH = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)");

    @Test
    @DisplayName("no write endpoint is missing @PreAuthorize")
    void everyWriteEndpointStatesWhoMayCallIt() {
        List<String> unprotected = new ArrayList<>();
        int controllers = 0;

        for (Path service : RepoLayout.serviceDirs()) {
            for (Path file : RepoLayout.filesEndingWith(service.resolve("src/main/java"), "Controller.java")) {
                controllers++;
                String src = RepoLayout.read(file);

                // A class-level @PreAuthorize covers every method in the class.
                int classDecl = src.indexOf("public class");
                if (classDecl >= 0 && src.substring(0, classDecl).contains("@PreAuthorize")) continue;

                Matcher base = BASE_PATH.matcher(src);
                String prefix = base.find() ? base.group(1) : "";

                Matcher m = WRITE_MAPPING.matcher(src);
                while (m.find()) {
                    String path = (prefix + (m.group(2) == null ? "" : m.group(2))).replace("//", "/");
                    if (EXEMPT.matcher(path).find()) continue;

                    // Look between the PREVIOUS mapping and this one (that's this method's own
                    // annotation block), plus the signature that follows. Scanning the whole file
                    // would let one protected method vouch for an unprotected neighbour.
                    String before = src.substring(Math.max(0, m.start() - 900), m.start());
                    int prev = -1;
                    for (String verb : List.of("Get", "Post", "Put", "Patch", "Delete")) {
                        prev = Math.max(prev, before.lastIndexOf("@" + verb + "Mapping"));
                    }
                    if (prev >= 0) before = before.substring(prev);

                    String after = src.substring(m.end(), Math.min(src.length(), m.end() + 400));
                    after = after.split("\\{", 2)[0];

                    if (!before.contains("@PreAuthorize") && !after.contains("@PreAuthorize")) {
                        unprotected.add("  " + m.group(1).toUpperCase(Locale.ROOT) + " " + path
                                + "\n      " + RepoLayout.rel(file));
                    }
                }
            }
        }

        assertThat(controllers)
                .as("found no *Controller.java files — the layout changed and this test would "
                    + "otherwise pass forever without checking anything")
                .isPositive();

        assertThat(unprotected)
                .as("""
                    These write endpoints have no @PreAuthorize:

                    %s

                    A write endpoint must state who may call it. Being inside a public-paths
                    pattern does NOT make it read-only — those patterns are method-blind, which is
                    exactly how PUT /api/v1/reviews/{id} ended up world-writable in Session 40.

                    And "authenticated" is not "authorised": hasRole('SALON_OWNER') alone lets the
                    owner of salon A edit salon B. Where a resource belongs to a salon, check
                    principal.salonId() too.""",
                        String.join("\n", unprotected))
                .isEmpty();
    }
}
