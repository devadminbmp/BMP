package com.bmp.common.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every service must state, explicitly, which of its endpoints need no token.
 *
 * <h2>Why (Session 29, enforced here since Session 43)</h2>
 * {@code CommonSecurityConfig} reads {@code bmp.security.public-paths}. Before Session 29 the
 * default when the property was absent was <b>permit everything</b> — so a new service that
 * simply forgot the key served its entire API unauthenticated, and looked completely normal
 * doing it. There was no error, no warning, and no screen that behaved differently.
 *
 * <p>The default now fails closed, which turns that silent hole into a service that won't
 * start. Better, but it moves the discovery to runtime, on whoever's machine boots it first.
 * This test moves it to the build.
 *
 * <h2>Two failures, not one</h2>
 * <ul>
 *   <li><b>Missing.</b> No {@code public-paths} at all.</li>
 *   <li><b>{@code /**}.</b> Present, and publishing everything — which satisfies a
 *       "is the property set?" check while restoring the exact hole that check exists to
 *       prevent. A service can legitimately have broad public paths; it cannot legitimately
 *       have <i>all</i> of them public. <b>A guard that only asks whether a value exists will
 *       be satisfied by a value that means nothing.</b></li>
 * </ul>
 *
 * <p>Deliberately parsed with a regex rather than a YAML library: this needs to work on files
 * full of {@code ${ENV:default}} placeholders and folded {@code >-} scalars without dragging a
 * parser's opinions into a test whose only question is "is this key present and not a wildcard".
 */
class PublicPathsTest {

    /**
     * Infrastructure services: no {@code CommonSecurityConfig}, so nothing to declare.
     *
     * <p>{@code api-gateway} is on this list because it routes rather than serves — the services
     * behind it do their own authorization, which is the point of the write-auth test next door.
     * {@code bmp-app} is the retired monolith (see its application.yml).
     *
     * <p>Adding a name here removes a service from this check. Do it only for something that
     * genuinely has no endpoints of its own.
     */
    private static final Set<String> EXEMPT = Set.of(
            "api-gateway", "eureka-server", "bmp-config-server", "bmp-monitoring", "bmp-app");

    @Test
    @DisplayName("every service declares bmp.security.public-paths, and none sets it to /**")
    void everyServiceDeclaresItsPublicPaths() {
        List<String> missing = new ArrayList<>();
        List<String> wideOpen = new ArrayList<>();
        List<String> malformed = new ArrayList<>();
        int checked = 0;

        for (Path service : RepoLayout.serviceDirs()) {
            String name = service.getFileName().toString();
            if (EXEMPT.contains(name)) continue;

            Path yml = service.resolve("src/main/resources/application.yml");
            if (!Files.isRegularFile(yml)) continue;
            checked++;

            List<String> entries = publicPathEntries(RepoLayout.read(yml));
            if (entries.isEmpty()) {
                missing.add(name);
            } else if (entries.contains("/**")) {
                wideOpen.add(name);
            }
            // Session 44 — every entry must LOOK like a path.
            //
            // `public-paths` is a YAML FOLDED SCALAR (`>-`), and inside one a `#` is not a
            // comment — it is literal text. So an explanatory comment written between the
            // entries gets folded into the value, split on the commas, and handed to Spring's
            // path matcher as junk patterns. I did exactly this while adding /photos, and the
            // file looked completely correct; it was only visible by parsing the VALUE.
            //
            // Cheap, total check: a real entry starts with '/'.
            for (String e : entries) {
                if (!e.startsWith("/")) {
                    malformed.add(name + ": \"" + (e.length() > 60 ? e.substring(0, 60) + "…" : e) + "\"");
                }
            }
        }

        assertThat(checked)
                .as("found no service application.yml files at all — the layout changed and this "
                    + "test would otherwise pass forever without checking anything")
                .isPositive();

        assertThat(missing)
                .as("""
                    These services do not declare bmp.security.public-paths:
                      %s

                    Each one will refuse to start (the default fails closed since Session 29), and
                    before that change each would have served EVERY endpoint unauthenticated while
                    looking completely normal. List the paths that genuinely need no token.""",
                        String.join(", ", missing))
                .isEmpty();

        assertThat(wideOpen)
                .as("""
                    These services set public-paths to /**, which makes every endpoint public:
                      %s

                    That satisfies "is the property set?" while restoring the exact hole the
                    property exists to close. List the specific paths instead.""",
                        String.join(", ", wideOpen))
                .isEmpty();

        assertThat(malformed)
                .as("""
                    These public-paths entries are not paths:

                      %s

                    `public-paths` is a YAML folded scalar (`>-`), where `#` is NOT a comment —
                    it is literal text. A comment written between the entries is folded into the
                    value, split on the commas, and handed to Spring's path matcher as junk.

                    Put explanatory comments ABOVE the `public-paths:` key, where `#` really is a
                    comment. The file looks correct either way; only the parsed value shows it.""",
                        String.join("\n      ", malformed))
                .isEmpty();
    }

    /**
     * Every page a logged-out visitor can reach, and the endpoint it calls.
     *
     * <p>Session 48. This list is the guest funnel, written down. Add a row when a public screen
     * starts calling something new; that is the only maintenance this test needs.
     */
    private static final List<String[]> GUEST_REACHABLE = List.of(
            new String[] {"/api/v1/salons",                 "discover: search and nearby"},
            new String[] {"/api/v1/salons/ID",              "salon by id"},
            new String[] {"/api/v1/salons/ID/detail",       "the public salon page"},
            new String[] {"/api/v1/salons/ID/services",     "service list on that page"},
            new String[] {"/api/v1/salons/ID/photos",       "gallery"},
            new String[] {"/api/v1/salons/ID/reviews",      "reviews"},
            new String[] {"/api/v1/salons/ID/stylists",     "stylist picker"},
            new String[] {"/api/v1/availability/slots",     "the time strip"},
            new String[] {"/api/v1/availability/slots/any", "anyone-available slots"});

    /**
     * The guest funnel must actually be reachable without a token.
     *
     * <h2>The bug that caused this test (Session 48)</h2>
     * {@code /api/v1/salons/*} was in bmp-salon's public-paths and looked like it covered the
     * public salon page. It did not: in Ant a single {@code *} matches exactly ONE segment, so it
     * matches {@code /salons/{id}} and stops. {@code /salons/{id}/detail} was never exempted, and
     * every logged-out visitor who tapped a salon card got a 401 — on the single most important
     * page in the funnel.
     *
     * <p>Nothing caught it. The comment directly above that property in application.yml explains
     * the one-segment rule correctly and in detail, and we added an endpoint that breaks it
     * anyway. <b>Knowing a rule is not a control; a test is.</b>
     *
     * <p>It was also invisible from the outside: the frontend rendered "Loading salon…" for a
     * failed request, so the outage looked like slowness. Nobody was going to find this by using
     * the product.
     *
     * <h2>Note what this does NOT check</h2>
     * Public-paths are path-only and method-blind, so exempting a path exempts every verb on it.
     * That is safe only because each write carries its own {@code @PreAuthorize} — which is the
     * write-auth test's job, not this one's. Both are required; neither is sufficient.
     */
    @Test
    @DisplayName("every endpoint a logged-out visitor reaches is in bmp-salon's public-paths")
    void guestReachableEndpointsAreActuallyPublic() {
        Path yml = RepoLayout.serviceDirs().stream()
                .filter(d -> d.getFileName().toString().equals("bmp-salon"))
                .map(d -> d.resolve("src/main/resources/application.yml"))
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);

        assertThat(yml)
                .as("bmp-salon/src/main/resources/application.yml not found — the layout changed "
                    + "and this test would otherwise pass forever without checking anything")
                .isNotNull();

        List<String> patterns = publicPathEntries(RepoLayout.read(yml));
        org.springframework.util.AntPathMatcher matcher =
                new org.springframework.util.AntPathMatcher();

        List<String> unreachable = new ArrayList<>();
        for (String[] row : GUEST_REACHABLE) {
            boolean covered = patterns.stream().anyMatch(p -> matcher.match(p, row[0]));
            if (!covered) unreachable.add(row[0] + "   (" + row[1] + ")");
        }

        assertThat(unreachable)
                .as("""
                    These endpoints are reached by logged-out visitors but are NOT in bmp-salon's
                    bmp.security.public-paths, so the JWT filter rejects them with 401 before the
                    controller runs:

                      %s

                    Guests browsing before login IS the product model on web. Check the pattern
                    carefully before adding it: a single `*` matches exactly ONE path segment, so
                    `/api/v1/salons/*` does NOT cover `/api/v1/salons/{id}/detail`. That precise
                    mistake took down the public salon page in Session 48.""",
                        String.join("\n      ", unreachable))
                .isEmpty();
    }

    /**
     * The comma-separated path patterns under {@code public-paths:}, or empty if the key is absent.
     *
     * <p>Handles both forms in use: an inline value on the same line, and YAML's folded scalar
     * ({@code public-paths: >-} followed by an indented block), which is what every service
     * currently uses so the list can be read across several lines.
     *
     * <p>Line-oriented rather than a YAML parse, on purpose. These files are full of
     * {@code ${ENV:default}} placeholders; the question here is narrow — is the key present, and
     * is it {@code /**} — and answering it doesn't need a parser's view of the whole document.
     */
    private static List<String> publicPathEntries(String yaml) {
        String[] lines = yaml.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher key = Pattern.compile("^(\\s*)public-paths:\\s*(.*)$").matcher(lines[i]);
            if (!key.find()) continue;

            int indent = key.group(1).length();
            StringBuilder value = new StringBuilder(key.group(2).trim());
            // A folded-scalar marker means the value is the indented block that follows.
            if (value.toString().equals(">-") || value.toString().equals(">")
                    || value.toString().equals("|")) {
                value.setLength(0);
            }
            for (int j = i + 1; j < lines.length; j++) {
                String line = lines[j];
                if (line.isBlank()) continue;
                int lineIndent = line.length() - line.stripLeading().length();
                if (lineIndent <= indent) break;   // back out to a sibling key — block is over
                value.append(' ').append(line.trim());
            }
            return Arrays.stream(value.toString().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .toList();
        }
        return List.of();
    }
}
