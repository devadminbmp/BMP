package com.bmp.common.repo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Two invariants about the boundary between the gateway and the services behind it.
 *
 * <h2>1. Every controller prefix must be routed — the same bug, three times</h2>
 * A service gains a new controller with a new path prefix. The controller is correct. The
 * frontend client is correct. The Zod schema is correct. And every request 404s, because the one
 * file that also had to change lives in a different module and nothing connected the two.
 *
 * <ol>
 *   <li><b>{@code /api/v1/admin/**}</b> — the entire staff console. BMP-ADMIN's axios baseURL is
 *       {@code /api/v1/admin}, so <em>every request the console made</em> died at the gateway.
 *       Nothing in bmp-admin was wrong; the request never arrived.</li>
 *   <li><b>{@code /api/v1/coupon-requests/**}</b> — the salon owner's Offers tab, which had
 *       therefore never once worked against a real backend. It rendered "That didn't load · Not
 *       Found", which reads like a bug in the panel.</li>
 *   <li>and the trap underneath both: {@code /coupons/**} <em>looks</em> like it covers
 *       {@code /coupon-requests/**}. It does not. Spring's Ant matcher treats {@code -} as an
 *       ordinary character, so they are unrelated strings that share seven letters. A reviewer
 *       scanning the route list will not catch this, because to a human it looks covered.</li>
 * </ol>
 *
 * <p>Worth a test rather than a checklist because the failure is invisible at build time, at
 * startup, and in the target service's own logs — bmp-rewards never saw the request, so it had
 * nothing to report. It surfaces only as a 404 in a UI, and a gateway 404 is indistinguishable
 * from an application 404. The cost isn't the fix (one line of YAML); it's the hour spent looking
 * in the wrong service first. <b>A bug whose symptom points away from its cause has to be caught
 * mechanically</b>, because the debugging instinct it triggers is the wrong one.
 *
 * <h2>2. Every {@code /internal} endpoint must require ROLE_SERVICE</h2>
 * This test originally asserted the opposite of the truth — that internal paths are not
 * gateway-routed. They are. {@code InternalSalonController} maps {@code /api/v1/salons/internal},
 * which the existing {@code /api/v1/salons/**} predicate matches, so <b>every internal endpoint in
 * this system is reachable from the public internet.</b>
 *
 * <p>That is not a hole, because it was never the gateway's job to close it: authorization belongs
 * to the service that owns the data, and each internal controller carries
 * {@code @PreAuthorize("hasRole('SERVICE')")} — a role granted only by a valid
 * {@code X-Internal-Service-Key}, never by a user JWT. Path-based secrecy would be a second,
 * weaker mechanism that could silently disagree with the first.
 *
 * <p>But it does mean the {@code @PreAuthorize} is the ONLY thing standing between the internet
 * and endpoints that take a service's word for who the caller is. So it is asserted here rather
 * than assumed: add an internal controller without that annotation and this test fails.
 */
@DisplayName("Gateway ↔ service boundary")
class GatewayRouteTest {

    /**
     * The top-level internal namespace, exempt from the routing check.
     *
     * <p>bmp-rewards puts its service-to-service endpoints at {@code /api/v1/internal/coupons} and
     * {@code /api/v1/internal/coupon-requests} — a namespace with no gateway route at all, so
     * those endpoints are genuinely unreachable from outside.
     *
     * <p>Every other service nests them under the public parent instead
     * ({@code /api/v1/salons/internal}, {@code /api/v1/bookings/internal}), which the parent's
     * {@code /**} predicate matches — so those ARE publicly reachable and rely entirely on
     * {@code hasRole('SERVICE')}.
     *
     * <p>Both are safe today; test 2 asserts the guard on all six. But bmp-rewards' arrangement is
     * strictly better — it adds a network-level layer under the authorization one, so a future
     * mistake in a {@code @PreAuthorize} isn't immediately internet-facing. Worth converging on,
     * logged in docs/PENDING_WORK.md rather than changed here: moving a path prefix breaks every
     * Feign client that calls it, which is a change that deserves its own commit.
     */
    private static final String TOP_LEVEL_INTERNAL = "/api/v1/internal";

    /**
     * Any mapping annotation carrying a path that starts {@code /api}.
     *
     * <p>Deliberately NOT just {@code @RequestMapping}. Controllers in this codebase use two
     * different conventions: most declare a class-level {@code @RequestMapping} prefix and
     * relative method paths, but {@code SalonController} declares no class-level prefix at all
     * and puts the full path on every method ({@code @GetMapping("/api/v1/salons/{id}/photos")}).
     *
     * <p>The first version of this test matched only {@code @RequestMapping}, which meant it
     * silently skipped that entire controller — <b>a guard that quietly checks nothing is worse
     * than no guard</b>, because it also reports success. Matching every mapping annotation
     * covers both conventions and doesn't care which a future controller picks.
     */
    private static final Pattern REQUEST_MAPPING = Pattern.compile(
            "@(?:Request|Get|Post|Put|Patch|Delete)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"(/api/[^\"]*)\"");

    /** A {@code - Path=a,b,c} predicate line in the gateway's application.yml. */
    private static final Pattern PATH_PREDICATE = Pattern.compile("-\\s*Path=([^\\n#]+)");

    /** The class-level guard every internal controller must carry. */
    private static final Pattern SERVICE_ROLE_GUARD = Pattern.compile(
            "@PreAuthorize\\s*\\(\\s*\"hasRole\\('SERVICE'\\)\"\\s*\\)");

    // ── 1. routing ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every controller's @RequestMapping prefix is covered by a gateway route")
    void everyPrefixIsRouted() {
        Set<String> routed = gatewayRoutedPrefixes();
        assertTrue(routed.size() > 5,
                "Parsed only " + routed.size() + " gateway route prefixes, which means the YAML "
                + "parse in this test has broken rather than the routes having vanished. Fix the "
                + "test before trusting it. Found: " + routed);

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : controllerPrefixes().entrySet()) {
            if (e.getKey().startsWith(TOP_LEVEL_INTERNAL)) continue;   // deliberate — see below
            if (!isCovered(e.getKey(), routed)) {
                missing.add(e.getKey() + "   served by " + e.getValue());
            }
        }

        if (!missing.isEmpty()) {
            fail("""
                 These controller path prefixes are NOT routed by the API gateway, so requests to \
                 them 404 AT THE GATEWAY and never reach the service. The service's own logs show \
                 nothing, because the request never arrived.

                 Fix: add the prefix to a route predicate in
                   api-gateway/src/main/resources/application.yml

                 Careful — a similar-looking existing prefix does NOT cover a new one. \
                 `/api/v1/coupons/**` does not match `/api/v1/coupon-requests/**`; Spring's Ant \
                 matcher treats `-` as an ordinary character.

                 Unrouted:
                   %s
                 """.formatted(String.join("\n  ", missing)));
        }
    }

    /**
     * Is {@code prefix} covered by some routed prefix?
     *
     * <p>Segment-aware on purpose. {@code /api/v1/admin/**} genuinely covers
     * {@code /api/v1/admin/coupons}, so a plain equality check produced five false failures for
     * bmp-admin's console controllers. But a raw {@code startsWith} would report
     * {@code /api/v1/coupons} as covering {@code /api/v1/coupon-requests} — <em>reintroducing the
     * exact bug this test exists to catch</em>. So a match must land on a segment boundary.
     */
    private static boolean isCovered(String prefix, Set<String> routed) {
        for (String r : routed) {
            if (prefix.equals(r)) return true;
            if (prefix.startsWith(r + "/")) return true;
        }
        return false;
    }

    // ── 2. internal endpoints ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every /internal controller requires ROLE_SERVICE")
    void internalControllersRequireServiceRole() {
        List<String> unguarded = new ArrayList<>();
        int checked = 0;

        for (Path module : RepoLayout.serviceDirs()) {
            for (Path java : RepoLayout.filesEndingWith(module.resolve("src/main"), "Controller.java")) {
                String src = RepoLayout.read(java);
                Matcher m = REQUEST_MAPPING.matcher(src);
                boolean internal = false;
                while (m.find()) {
                    // "/internal" as a whole segment — not a substring, so a hypothetical
                    // /api/v1/internal-notes controller isn't swept up by accident.
                    String p = m.group(1);
                    if (p.equals("/api/v1/internal")
                            || p.contains("/internal/") || p.endsWith("/internal")) {
                        internal = true;
                    }
                }
                if (!internal) continue;
                checked++;
                if (!SERVICE_ROLE_GUARD.matcher(src).find()) {
                    unguarded.add(RepoLayout.rel(java));
                }
            }
        }

        assertTrue(checked > 0,
                "Found no /internal controllers at all. They exist (bmp-auth, bmp-booking, "
                + "bmp-salon), so the detection in this test has broken — a test that silently "
                + "checks nothing is worse than no test.");

        assertTrue(unguarded.isEmpty(),
                "These controllers serve an /internal path but do NOT carry "
                + "@PreAuthorize(\"hasRole('SERVICE')\"):\n  "
                + String.join("\n  ", unguarded)
                + "\n\nInternal paths ARE reachable through the gateway — the parent route "
                + "(/api/v1/salons/**, /api/v1/bookings/**) matches them. That annotation is the "
                + "ONLY thing between the public internet and an endpoint that takes the caller's "
                + "word for who they are. ROLE_SERVICE is granted solely by a valid "
                + "X-Internal-Service-Key and never by a user JWT.");
    }

    // ── parsing ────────────────────────────────────────────────────────────────────────────

    private static Set<String> gatewayRoutedPrefixes() {
        Path yml = RepoLayout.root().resolve("api-gateway/src/main/resources/application.yml");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PATH_PREDICATE.matcher(RepoLayout.read(yml));
        while (m.find()) {
            for (String raw : m.group(1).split(",")) {
                String p = normalise(raw);
                if (!p.isEmpty()) out.add(p);
            }
        }
        return out;
    }

    /** Every class-level {@code @RequestMapping} prefix across all modules → the modules serving it. */
    private static Map<String, Set<String>> controllerPrefixes() {
        Map<String, Set<String>> out = new TreeMap<>();
        for (Path module : RepoLayout.serviceDirs()) {
            String moduleName = module.getFileName().toString();
            // The gateway has no controllers; skip so it can't self-satisfy the check.
            if (moduleName.equals("api-gateway")) continue;

            for (Path java : RepoLayout.filesEndingWith(module.resolve("src/main"), "Controller.java")) {
                Matcher m = REQUEST_MAPPING.matcher(RepoLayout.read(java));
                while (m.find()) {
                    String prefix = normalise(m.group(1));
                    if (!prefix.isEmpty()) {
                        out.computeIfAbsent(prefix, k -> new LinkedHashSet<>()).add(moduleName);
                    }
                }
            }
        }
        return out;
    }

    // ── 3. routed to the RIGHT service, in the right ORDER ─────────────────────────────────

    /**
     * Session 49. {@link #everyPrefixIsRouted} asks "is this path routed <em>somewhere</em>?" and
     * cannot fail when a path is routed to the <b>wrong</b> service — which is a live bug class
     * in this file, not a hypothetical.
     *
     * <h2>The bug this pins</h2>
     * Three paths look like salon paths and are served by other services:
     *
     * <pre>
     *   /api/v1/salons/{id}/reviews             bmp-review
     *   /api/v1/salons/{id}/bookings/../invoice bmp-booking
     *   /api/v1/salons/{id}/invoices/..         bmp-booking
     * </pre>
     *
     * {@code salon-service} claims the whole of {@code /api/v1/salons/**}, and Spring Cloud
     * Gateway takes the FIRST matching route in declaration order. So these three only work while
     * their routes sit above it. Someone tidying the file alphabetically, or appending a new
     * route at the end "where the others are", silently breaks all three — and the symptom is a
     * 404 at the gateway with nothing in any service's log, which is exactly the kind of thing
     * that gets debugged for an afternoon.
     *
     * <p>The reviews route was broken this way from the day the endpoint was written and went
     * unnoticed because nothing called it. This test is the thing that would have caught it.
     */
    @Test
    @DisplayName("salon-shaped paths owned by other services are routed there, and declared first")
    void salonShapedPathsGoToTheRightService() {
        List<Route> routes = gatewayRoutesInOrder();
        assertTrue(routes.size() > 5,
                "Parsed only " + routes.size() + " routes — the YAML parse in this test has "
                + "broken rather than the routes having vanished. Fix the test before trusting it.");

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/api/v1/salons/abc123/reviews", "bmp-review-service");
        expected.put("/api/v1/salons/abc123/bookings/def456/invoice", "bmp-booking-service");
        expected.put("/api/v1/salons/abc123/invoices/def456/payment", "bmp-booking-service");
        // Control cases: ordinary salon paths must still reach bmp-salon. Without these, someone
        // could "fix" a failure by routing all of /api/v1/salons/** to bmp-booking and pass.
        expected.put("/api/v1/salons/abc123", "bmp-salon-service");
        expected.put("/api/v1/salons/abc123/services", "bmp-salon-service");
        expected.put("/api/v1/salons/abc123/leave-requests", "bmp-salon-service");
        /*
         * Session 48/49's stylist self-service. This route was MISSING entirely — every endpoint
         * under it 404'd at the gateway, so the whole feature was unreachable in a real
         * deployment while working perfectly in local testing against the service directly.
         *
         * It is NOT covered by /api/v1/stylists/**: Ant treats '-' as an ordinary character, so
         * `stylists` and `stylist-profile` share a prefix and nothing else. Same trap as
         * /api/v1/coupons/** vs /api/v1/coupon-requests/** in Session 44.
         */
        expected.put("/api/v1/stylist-profile", "bmp-salon-service");
        expected.put("/api/v1/stylist-profile/leave", "bmp-salon-service");
        expected.put("/api/v1/stylist-profile/join-requests", "bmp-salon-service");
        expected.put("/api/v1/stylist-profile/available-today", "bmp-salon-service");
        /*
         * Session 52 — the salon's own customer book. Same '-' trap again: `/api/v1/salons/**`
         * does NOT match `/api/v1/salon-customers/...`, so without its own entry in the
         * salon-service predicate the counter's customer search 404s at the gateway while working
         * perfectly against the service directly. Sixth instance of this bug class.
         */
        /*
         * Session 54 — the SEVENTH instance. `POST /api/v1/bookings/{id}/review` is declared in
         * bmp-REVIEW, not bmp-booking, and had been routed to the wrong service since it was
         * written. It survived because the endpoint had no frontend either: unreachable from both
         * ends looks like "not shipped yet", and nothing unshipped gets its routing tested.
         *
         * The control case below it matters as much as the case itself — the rest of
         * /api/v1/bookings/** must still reach bmp-booking.
         */
        expected.put("/api/v1/bookings/abc123/review", "bmp-review-service");
        expected.put("/api/v1/bookings/abc123", "bmp-booking-service");
        expected.put("/api/v1/bookings/abc123/cancel", "bmp-booking-service");
        expected.put("/api/v1/bookings/salon/day", "bmp-booking-service");

        expected.put("/api/v1/salon-customers", "bmp-salon-service");
        expected.put("/api/v1/salon-customers/abc123", "bmp-salon-service");
        // Session 52 — the counter booking door lives in bmp-booking, not bmp-salon.
        expected.put("/api/v1/bookings/counter", "bmp-booking-service");
        // Session 52 — the batched availability the picker uses.
        expected.put("/api/v1/availability/salon-day", "bmp-salon-service");
        // Session 48's stylist-scoped booking endpoints, and the stylist's own reviews.
        expected.put("/api/v1/bookings/stylist/day", "bmp-booking-service");
        expected.put("/api/v1/reviews/stylist/abc123", "bmp-review-service");

        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String actual = routes.stream()
                    .filter(r -> r.matches(e.getKey()))
                    .map(Route::uri)
                    .findFirst()             // FIRST match wins — that IS the gateway's rule
                    .orElse("<no route matches>");
            if (!actual.contains(e.getValue())) {
                wrong.add(e.getKey() + "\n      expected -> " + e.getValue()
                        + "\n      actual   -> " + actual);
            }
        }

        if (!wrong.isEmpty()) {
            fail("""
                 These paths reach the WRONG service, or none at all. Requests 404 at the gateway \
                 and never arrive, so the owning service's logs show nothing.

                 Spring Cloud Gateway uses the FIRST route whose predicate matches, in the order \
                 routes are declared in api-gateway/src/main/resources/application.yml. A narrower \
                 salon path must be declared ABOVE the `salon-service` route, which claims all of \
                 /api/v1/salons/**.

                 A SALON-SHAPED PATH DOES NOT IMPLY bmp-salon.

                 Mis-routed:
                   %s
                 """.formatted(String.join("\n   ", wrong)));
        }
    }

    /** One gateway route: its target uri and its Path patterns, in declaration order. */
    private record Route(String uri, List<String> patterns) {
        boolean matches(String path) {
            return patterns.stream().anyMatch(p -> antMatches(p, path));
        }
    }

    /**
     * Ant matching, reimplemented rather than pulled from Spring.
     *
     * <p>This test lives in bmp-common, which has no Spring Web dependency, and adding one so a
     * test can borrow {@code AntPathMatcher} would be a heavier change than the twelve lines
     * below. The two rules that matter here — and the two this codebase has repeatedly got wrong
     * — are that a single {@code *} matches exactly ONE segment and {@code **} matches any number.
     */
    private static boolean antMatches(String pattern, String path) {
        String[] pp = pattern.split("/");
        String[] sp = path.split("/");
        int i = 0;
        for (; i < pp.length; i++) {
            if ("**".equals(pp[i])) return true;          // swallows the rest, however long
            if (i >= sp.length) return false;
            if ("*".equals(pp[i])) continue;              // exactly one segment
            if (!pp[i].equals(sp[i])) return false;
        }
        return i == sp.length;
    }

    /**
     * Parse the gateway YAML into routes, preserving declaration order.
     *
     * <p>Order is the whole point of {@link #salonShapedPathsGoToTheRightService}, so this cannot
     * use the unordered prefix set the other tests share.
     */
    private static List<Route> gatewayRoutesInOrder() {
        Path yml = RepoLayout.root().resolve("api-gateway/src/main/resources/application.yml");
        String text = RepoLayout.read(yml);
        List<Route> out = new ArrayList<>();
        String pendingUri = null;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#")) continue;                       // comments mention paths too
            if (t.startsWith("uri:")) {
                pendingUri = t.substring(4).trim();
                continue;
            }
            Matcher m = PATH_PREDICATE.matcher(t);
            if (m.find() && pendingUri != null) {
                List<String> pats = new ArrayList<>();
                for (String raw : m.group(1).split(",")) {
                    String p = raw.trim();
                    if (!p.isEmpty()) pats.add(p);
                }
                out.add(new Route(pendingUri, pats));
                pendingUri = null;
            }
        }
        return out;
    }

    /** Trim, drop any {@code {pathVariable}} tail, drop a trailing {@code /**} or {@code /}. */
    private static String normalise(String raw) {
        String p = raw.trim();
        int var = p.indexOf('{');
        if (var >= 0) p = p.substring(0, var);
        if (p.endsWith("/**")) p = p.substring(0, p.length() - 3);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
