package com.bmp.common.repo;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does every Zod schema in BMP-FE ask for fields the backend record actually sends?
 *
 * <h2>Why (Session 40)</h2>
 * The frontend validates every response at the API boundary. That's good — until the schema and
 * the record drift, at which point the client rejects a response the server considers correct,
 * and the screen fails with a Zod error that names a field rather than a cause.
 *
 * <p>It drifts because <b>a mock that never has to agree with the server guarantees the two will
 * diverge</b>. {@code USE_MOCKS} defaults ON, so a schema can be written against imagined data,
 * pass every local check, and break the first time it meets the real API. The first sweep of
 * this found four live mismatches.
 *
 * <h2>The hand-maintained bit</h2>
 * {@link #PAIRS} maps schema to record. That mapping cannot be inferred — names differ on
 * purpose ({@code SalonSchema} parses {@code NearbySalonResponse}), and one record often serves
 * several schemas. <b>An unmapped schema fails the test rather than being skipped</b>: silence
 * on an unknown schema is how a checker slowly stops checking anything.
 *
 * <h2>Skips rather than fails without BMP-FE</h2>
 * This reads a SIBLING repository. A backend-only checkout is a legitimate state — CI clones one
 * repo — so this is an {@code Assumption}, not an assertion. Failing the backend build because
 * the frontend isn't on disk would teach people to ignore the failure, which costs more than the
 * check is worth.
 */
class ApiContractTest {

    /**
     * Zod schema → the Java record it parses. {@code null} = built client-side, no server shape.
     *
     * <p>Add a row when you add a schema. An unmapped schema is reported, not ignored.
     */
    private static final Map<String, String> PAIRS = new LinkedHashMap<>();
    static {
        PAIRS.put("BookingSchema", "BookingResponse");
        PAIRS.put("BookingItemSchema", "ItemResponse");
        PAIRS.put("PagedBookingsSchema", "PagedBookings");
        PAIRS.put("ScheduleEntrySchema", "ScheduleEntryResponse");
        PAIRS.put("DaySummarySchema", "SalonDaySummaryResponse");
        PAIRS.put("SalonDaySchema", "SalonDayResponse");
        PAIRS.put("DeskStylistSchema", "StylistSalonResponse");
        PAIRS.put("DeskServiceSchema", "ServiceResponse");
        PAIRS.put("SalonBookingSchema", "BookingResponse");
        PAIRS.put("SalonHistoryPageSchema", "PagedBookings");
        PAIRS.put("CustomerAtSalonSchema", "CustomerAtSalonResponse");
        PAIRS.put("CancelPreviewSchema", "CancelPreviewResponse");
        PAIRS.put("RescheduleEligibilitySchema", "RescheduleEligibility");
        PAIRS.put("BookingEventSchema", "EventResponse");
        PAIRS.put("ContactRevealSchema", "ContactRevealResponse");
        PAIRS.put("SalonPolicySchema", "PolicyResponse");
        PAIRS.put("RawSlotSchema", "SlotResponse");
        PAIRS.put("WeeklyTemplateSchema", "WeeklyTemplateResponse");
        PAIRS.put("DayTemplateSchema", "DayTemplate");
        PAIRS.put("AvailabilityRuleSchema", "AvailabilityRuleResponse");
        PAIRS.put("TimeWindowSchema", "TimeWindow");
        PAIRS.put("StaffMemberSchema", "StaffMemberResponse");
        PAIRS.put("InviteSchema", "InviteResponse");
        PAIRS.put("MeResponseSchema", "MeResponse");
        PAIRS.put("OtpRequestResponseSchema", "OtpRequestResponse");
        PAIRS.put("OtpVerifyResponseSchema", "OtpVerifyResponse");
        PAIRS.put("RefreshResponseSchema", "RefreshResponse");
        PAIRS.put("GoogleAuthResponseSchema", "GoogleAuthResponse");
        PAIRS.put("ApiErrorSchema", "ErrorResponse");
        PAIRS.put("SalonSchema", "NearbySalonResponse");
        PAIRS.put("SalonDetailSchema", "SalonDetailResponse");
        // The ADMINISTRATIVE salon shape — status + the booking-alert contact, which no customer
        // should read off the public page. Session 40.
        PAIRS.put("SalonAdminSchema", "SalonResponse");
        PAIRS.put("ServiceSchema", "ServiceResponse");
        PAIRS.put("StylistSchema", "PublicStylistResponse");
        PAIRS.put("SlotGroupSchema", null);   // built client-side by groupSlots()
        PAIRS.put("OfferSchema", "CouponRequestDto");
        PAIRS.put("CouponQuoteSchema", "CouponQuoteResponse");
        PAIRS.put("CustomerAtSalonPageSchema", null);
        PAIRS.put("ComboSchema", "ComboResponse");
        PAIRS.put("ComboItemSchema", "ComboItemResponse");
        PAIRS.put("SalonPhotoSchema", "SalonPhotoResponse");
    }

    private static final Pattern RECORD = Pattern.compile("record (\\w+)\\(");
    private static final Pattern SCHEMA = Pattern.compile(
            "export const (\\w+Schema)\\s*=\\s*(?:z\\.object\\(\\{|(\\w+)\\.extend\\(\\{)");
    private static final Pattern SCHEMA_KEY = Pattern.compile("^ {2}(\\w+):", Pattern.MULTILINE);
    private static final Pattern ANNOTATION = Pattern.compile("@\\w+(\\([^)]*\\))?");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//.*");

    private record Schema(List<String> keys, String base, String source, String file) {}

    @Test
    @DisplayName("every Zod schema matches the backend record it parses")
    void schemasMatchTheirRecords() {
        Path fe = RepoLayout.root().resolveSibling("BMP-FE");
        Assumptions.assumeTrue(Files.isDirectory(fe.resolve("src/api")),
                "BMP-FE not found next to this repo — skipping the cross-repo contract check. "
                + "This is expected in a backend-only checkout.");

        Map<String, Set<String>> records = backendRecords();
        Map<String, Schema> schemas = frontendSchemas(fe);

        List<String> unchecked = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        int verified = 0;

        for (String name : new TreeSet<>(schemas.keySet())) {
            Schema info = schemas.get(name);

            if (!PAIRS.containsKey(name)) {
                unchecked.add("  · " + name + "  (" + info.file() + ")");
                continue;
            }
            String record = PAIRS.get(name);
            if (record == null) continue;   // deliberately client-side

            if (!records.containsKey(record)) {
                mismatches.add("  x " + name + " -> " + record + "\n        record not found in the backend");
                continue;
            }

            Set<String> keys = new HashSet<>(info.keys());
            String source = info.source();
            // An `X.extend({...})` schema inherits X's keys AND X's optionality markers. The base
            // source has to be unioned in too, or an inherited `.nullish()` is invisible here and
            // the check reports a mismatch that isn't one. (It did, on SalonDetailSchema.distanceKm.)
            Schema base = info.base() == null ? null : schemas.get(info.base());
            if (base != null) {
                keys.addAll(base.keys());
                source = source + "\n" + base.source();
            }

            List<String> missing = new ArrayList<>(new TreeSet<>(keys));
            missing.removeAll(records.get(record));

            // A key the client marks optional/nullish/default is fine — absence is by design.
            List<String> hard = new ArrayList<>();
            for (String k : missing) {
                if (!Pattern.compile("\\b" + Pattern.quote(k) + ":.*(optional|nullish|default)\\(")
                            .matcher(source).find()) {
                    hard.add(k);
                }
            }
            if (hard.isEmpty()) {
                verified++;
            } else {
                mismatches.add("  x " + name + " -> " + record
                        + "\n        server does not send: " + String.join(", ", hard));
            }
        }

        assertThat(verified)
                .as("no schema/record pairs were verified — the parsers or the layout changed, and "
                    + "a silent pass here is worse than no check")
                .isPositive();

        assertThat(mismatches)
                .as("""
                    The client asks for fields the server does not send:

                    %s

                    Fix one of three ways, in this order of preference:
                      1. The server should send it -> add it to the record (+ a migration if new)
                      2. The client shouldn't ask  -> remove it, or derive it client-side
                      3. It's genuinely optional   -> .nullish() on the schema AND handle null in the UI

                    If you pick 3, check the RENDER path too: a schema that permits null and a
                    component that calls .toFixed() on it trades a parse error for a crash.""",
                        String.join("\n", mismatches))
                .isEmpty();

        assertThat(unchecked)
                .as("""
                    These Zod schemas are not mapped to a backend record:

                    %s

                    Add each to PAIRS in this test — with the record it parses, or null if it is
                    built client-side. Skipping unknown schemas quietly is how a checker stops
                    checking anything without anyone noticing.""",
                        String.join("\n", unchecked))
                .isEmpty();
    }

    /** Every {@code record Name(...)} in the backend → its component names. */
    private static Map<String, Set<String>> backendRecords() {
        Map<String, Set<String>> out = new HashMap<>();
        for (Path service : RepoLayout.serviceDirs()) {
            for (Path file : RepoLayout.filesEndingWith(service.resolve("src/main/java"), ".java")) {
                String src = RepoLayout.read(file);
                Matcher m = RECORD.matcher(src);
                while (m.find()) {
                    int i = m.end(), depth = 1, start = i;
                    while (depth > 0 && i < src.length()) {
                        char c = src.charAt(i);
                        if (c == '(') depth++;
                        else if (c == ')') depth--;
                        i++;
                    }
                    String body = src.substring(start, Math.max(start, i - 1));
                    body = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(body).replaceAll("")).replaceAll("");
                    Set<String> fields = out.computeIfAbsent(m.group(1), k -> new HashSet<>());
                    for (String part : splitTopLevel(body)) {
                        String f = ANNOTATION.matcher(part).replaceAll("").trim();
                        if (f.isEmpty()) continue;
                        String[] words = f.split("\\s+");
                        fields.add(words[words.length - 1]);
                    }
                }
            }
        }
        return out;
    }

    /** Every exported Zod object schema in BMP-FE's api layer. */
    private static Map<String, Schema> frontendSchemas(Path feRoot) {
        Map<String, Schema> out = new LinkedHashMap<>();
        for (Path file : RepoLayout.filesEndingWith(feRoot.resolve("src/api"), ".ts")) {
            // Only the api layer's own modules, not subdirectories like mocks/.
            if (!file.getParent().equals(feRoot.resolve("src/api"))) continue;
            String src = RepoLayout.read(file);
            Matcher m = SCHEMA.matcher(src);
            while (m.find()) {
                int i = m.end(), depth = 1, start = i;
                while (depth > 0 && i < src.length()) {
                    char c = src.charAt(i);
                    if (c == '{') depth++;
                    else if (c == '}') depth--;
                    i++;
                }
                String body = src.substring(start, Math.max(start, i - 1));
                body = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(body).replaceAll("")).replaceAll("");
                List<String> keys = new ArrayList<>();
                Matcher k = SCHEMA_KEY.matcher(body);
                while (k.find()) keys.add(k.group(1));
                out.put(m.group(1), new Schema(keys, m.group(2), body, file.getFileName().toString()));
            }
        }
        return out;
    }

    /** Split on commas that aren't inside brackets — record components, generics and all. */
    private static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (char c : text.toCharArray()) {
            if (c == '(' || c == '<' || c == '[') depth++;
            else if (c == ')' || c == '>' || c == ']') depth--;
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString());
        return out;
    }
}
