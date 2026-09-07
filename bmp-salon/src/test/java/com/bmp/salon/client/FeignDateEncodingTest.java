package com.bmp.salon.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against the locale bug that took down every booking path in Session 66.
 *
 * <h2>What happened</h2>
 * {@code BookingServiceClient.getBusyWindows} declared:
 *
 * <pre>
 *   &#64;RequestParam("date") LocalDate date
 * </pre>
 *
 * Feign encodes that through Spring's conversion service, which — with no {@code @DateTimeFormat}
 * to tell it otherwise — falls back to a formatter derived from the <b>JVM default locale</b>. On
 * an Indian or British locale that is {@code 06/09/2026}. The receiving controller declares
 * {@code @DateTimeFormat(iso = ISO.DATE)} and parses only {@code 2026-09-06}, so it 500s on
 * everything the client sends.
 *
 * <h2>Why a test, and why this shape of test</h2>
 * This class of bug has three properties that together make it very expensive:
 *
 * <ul>
 *   <li><b>It compiles.</b> There is nothing for the language to object to.</li>
 *   <li><b>It is locale-dependent.</b> It passes on a machine whose default locale formats dates
 *       as ISO and fails on everyone else's, so it reads as "works on my machine" — an
 *       environment problem rather than a code one.</li>
 *   <li><b>It fails far from its cause.</b> The user sees a 500 from a booking screen; the actual
 *       defect is a missing annotation in a client interface two services away.</li>
 * </ul>
 *
 * <p>So the test asserts the ANNOTATION rather than the behaviour. Asserting behaviour would mean
 * standing up Feign and a server, and — worse — would only catch the bug when run under a locale
 * that exposes it, which is precisely the property that made this hard to find. The annotation is
 * the invariant; check the invariant.
 *
 * <p>Runs in milliseconds with no Spring context, same as {@code SecurityConstantsTest} in
 * bmp-admin, and for the same reason: the cheap check that would have caught the outage.
 */
@DisplayName("Feign date parameters")
class FeignDateEncodingTest {

    /** Temporal types Spring will format via the locale if not told otherwise. */
    private static final Set<Class<?>> DATE_TYPES =
            Set.of(LocalDate.class, LocalTime.class, LocalDateTime.class);

    /**
     * Every Feign client in this service that carries a date.
     *
     * <p>Listed explicitly rather than classpath-scanned: a scan that finds nothing still passes,
     * so a refactor that moved or renamed these interfaces would silently disable the guard. An
     * explicit list fails to compile instead, which is the failure mode you want.
     */
    private static final List<Class<?>> FEIGN_CLIENTS = List.of(BookingServiceClient.class);

    @Test
    @DisplayName("every LocalDate/LocalTime query param declares @DateTimeFormat")
    void everyDateParamIsIsoFormatted() {
        List<String> offenders = new ArrayList<>();

        for (Class<?> client : FEIGN_CLIENTS) {
            for (Method m : client.getMethods()) {
                for (Parameter p : m.getParameters()) {
                    if (!DATE_TYPES.contains(p.getType())) continue;
                    if (p.getAnnotation(RequestParam.class) == null) continue;

                    DateTimeFormat fmt = p.getAnnotation(DateTimeFormat.class);
                    if (fmt == null) {
                        offenders.add(client.getSimpleName() + "." + m.getName()
                                + "(" + p.getType().getSimpleName() + " " + p.getName() + ")"
                                + " — no @DateTimeFormat");
                    } else if (fmt.iso() == DateTimeFormat.ISO.NONE && fmt.pattern().isEmpty()) {
                        // Present but saying nothing: falls back to the locale exactly as if it
                        // were absent, which is the more confusing version of the same bug.
                        offenders.add(client.getSimpleName() + "." + m.getName()
                                + " — @DateTimeFormat with neither iso nor pattern");
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "These Feign date parameters will be encoded using the JVM's default locale "
                + "(e.g. 06/09/2026) and rejected by the receiving controller, which parses ISO "
                + "only. Add @DateTimeFormat(iso = DateTimeFormat.ISO.DATE):\n  "
                + String.join("\n  ", offenders));
    }

    /**
     * The reason parameter NAMES are checkable at all: {@code -parameters} is on.
     *
     * <p>Without it the message above says "arg0", and more importantly {@code @RequestParam} with
     * no explicit value cannot resolve a name at runtime — a separate failure that presents as a
     * missing query parameter. Asserting it here keeps the compiler flag from being dropped
     * silently by a build change.
     */
    @Test
    @DisplayName("compiled with -parameters, so param names survive")
    void parameterNamesAreRetained() throws Exception {
        Method m = BookingServiceClient.class.getMethod(
                "getBusyWindows", java.util.UUID.class, LocalDate.class, java.util.UUID.class);
        assertTrue(m.getParameters()[0].isNamePresent(),
                "Parameter names were not retained — add <parameters>true</parameters> to the "
                + "compiler plugin. Feign and Spring both need them.");
    }
}
