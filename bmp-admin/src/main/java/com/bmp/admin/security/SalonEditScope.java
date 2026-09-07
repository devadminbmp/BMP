package com.bmp.admin.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which FIELDS of a salon each console tier may change. Session 65.
 *
 * <h2>Why this is per-field, unlike every other scope class</h2>
 * {@link AccountScope} and {@link StaffAccountScope} answer "may you touch this record at all",
 * because for an account that is the whole question — you can either administer somebody or you
 * cannot. A salon is not like that. A support agent fixing a bounced booking-alert email is a
 * five-second job that should not need an admin; the same agent renaming the business or moving
 * its address is a change customers see and the owner did not ask for.
 *
 * <p>So the tier does not decide WHETHER, it decides WHICH:
 * <pre>
 *   support agent / lead  →  where booking alerts go. Contact plumbing, nothing public.
 *   ops admin             →  the listing: name, area, PIN, address, description, categories.
 *   super admin           →  the same as ops. There is deliberately nothing extra here.
 * </pre>
 *
 * <h2>What NOBODY may change through this path</h2>
 * <ul>
 *   <li><b>status</b> — approval is moderation, with its own endpoint, its own checks and its own
 *       audit trail. bmp-salon's {@code SalonService.update} rejects it outright, so this is
 *       belt and braces; the point of naming it here is that a reader looking for "who can
 *       approve a salon" finds the answer where they are looking.</li>
 *   <li><b>location</b> — the map pin. Changing it silently sends customers to the wrong place,
 *       and neither support nor ops is standing outside the shop. The owner sets it from their
 *       own device, where the phone's GPS is the evidence.</li>
 *   <li><b>the cover image and gallery</b> — moderation removes photos; it does not swap them.</li>
 * </ul>
 *
 * <p>The owner keeps their own full editor. This is a repair tool for when they cannot, or when
 * something is wrong and support is on the phone with them — not a second way to run the salon.
 */
public final class SalonEditScope {

    private SalonEditScope() {}

    /** Booking-alert plumbing. Not shown to customers, and wrong values silently lose bookings. */
    private static final Set<String> CONTACT_FIELDS =
            Set.of("bookingNotifyEmail", "bookingNotifyPhone");

    /** The public listing. Customers read every one of these. */
    private static final Set<String> LISTING_FIELDS =
            Set.of("name", "area", "pincode", "address", "about", "categories");

    /**
     * Everything this caller is allowed to change, or an empty set if nothing.
     *
     * <p>Returned as a SET rather than a boolean so the caller can both authorise and explain —
     * the console asks the same question to decide which inputs to render, and a rejection can
     * name the fields that were refused instead of failing the whole request anonymously.
     */
    public static Set<String> editableBy(StaffPrincipal caller) {
        if (caller == null) return Set.of();

        Set<String> allowed = new LinkedHashSet<>();

        // Ops and the owner get the listing.
        if (caller.can(StaffPermission.SALON_REVIEW)) {
            allowed.addAll(LISTING_FIELDS);
            allowed.addAll(CONTACT_FIELDS);
            return allowed;
        }

        /*
         * Support gets the contact fields, and only because it can already SEE the salon
         * (SALON_VIEW). Somebody with no visibility of salons has no business editing one.
         */
        if (caller.can(StaffPermission.SALON_VIEW)) {
            allowed.addAll(CONTACT_FIELDS);
        }
        return allowed;
    }

    /**
     * Throws unless every field the caller is trying to change is one they may change.
     *
     * <p>Rejects the WHOLE request rather than silently dropping the fields it dislikes. A partial
     * save that reports success is the worst outcome available: the agent believes the address is
     * fixed, the customer still cannot find the shop, and nothing anywhere says the two disagree.
     *
     * @param changing the field names actually present in the request — the ones with a non-null
     *                 value. Null fields mean "unchanged" and are not a request to change anything.
     */
    public static void requireCanEdit(StaffPrincipal caller, Set<String> changing) {
        Set<String> allowed = editableBy(caller);

        if (allowed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You don't have permission to edit salons.");
        }

        Set<String> refused = new LinkedHashSet<>(changing);
        refused.removeAll(allowed);
        if (refused.isEmpty()) return;

        /*
         * Name the fields. "Forbidden" makes an agent guess which of six inputs was the problem,
         * and the usual guess is "all of it", so they escalate the whole task.
         */
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You can change where booking alerts go, but not " + String.join(", ", refused)
                + ". An ops admin can edit the salon's listing.");
    }
}
