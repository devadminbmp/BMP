package com.bmp.salon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** BMP-24 DTOs — salon_schema.stylist / stylist_salon / stylist_service. */
public final class StylistDtos {
    private StylistDtos() {}

    public record CreateStylistRequest(@NotBlank String name, UUID userId) {}

    /**
     * Session 44 — correct a stylist's name or speciality.
     *
     * <p>Until now {@code StylistController} had a single {@code PUT}, for
     * {@code available-today}, so <b>a stylist's name was write-once</b>. Salons quick-add staff
     * mid-shift ("Ravi" for Ravikumar, a name misheard across a busy floor), which makes a typo
     * the normal case — and it then appears on the public salon page and every desk row forever.
     *
     * <p>Null means unchanged, the same contract as {@code UpdateSalonRequest} and
     * {@code UpdateServiceRequest}. Speciality accepts an empty string to CLEAR it, because
     * "actually she doesn't specialise in anything particular" is a real correction; name does
     * not, because a stylist with no name is not a usable row.
     */
    public record UpdateStylistRequest(String name, String speciality) {}

    public record StylistResponse(UUID id, String name, UUID userId, BigDecimal overallRating,
                                   int totalReviews, boolean isTopStylist, Instant createdAt) {}

    public record LinkStylistRequest(@NotNull UUID stylistId) {}

    /**
     * Session 16: {@code stylistName} added. The link row only ever carried ids, which meant
     * every consumer (the manager desk, the booking screen) had to fan out one call per
     * stylist just to render a list — or show raw UUIDs to a human. The name lives one table
     * away in the same service, so joining it here is strictly cheaper than making every
     * caller do it. Nullable only if the stylist row is missing, which shouldn't happen.
     *
     * <p>Session 26: {@code stylistUserId} added, and it is NOT redundant with {@code stylistId}.
     * A stylist is a salon-owned record that can exist with no login at all — six of the seven
     * seeded stylists have {@code user_id = NULL}, because a salon lists its chair staff long
     * before any of them installs the app. So {@code stylist.id} and {@code users.id} are
     * different identifiers for different things, and only some stylists have both.
     *
     * <p>Without this field a signed-in stylist could not be matched to their own row, which is
     * what a stylist dashboard needs before it can show "my schedule". The frontend previously
     * had no way to do it and there was no stylist dashboard at all — those two facts are
     * related. Nullable: a stylist who has never signed up simply has no user.
     *
     * <p>Costs nothing: {@code toStylistSalonResponse} already loads the Stylist row for the
     * name, so this reads a field off an object that is already in hand.
     */
    public record StylistSalonResponse(UUID id, UUID stylistId, String stylistName, UUID stylistUserId,
                                        UUID salonId, String status, boolean isAvailableToday,
                                        BigDecimal salonRating, Integer salonReviewCount,
                                        Instant joinedAt, Instant leftAt) {}

    public record AvailableTodayRequest(boolean isAvailableToday) {}

    public record AvailableTodayResponse(UUID stylistId, UUID salonId, boolean isAvailableToday) {}

    /**
     * The whole set of services this stylist performs, replacing whatever was there. Session 67.
     *
     * <h2>Why a SET rather than add-one / remove-one</h2>
     * Darshan: "stylist can choose service which is salon itself". The thing being edited is
     * "which of our services does Ravi do" — a checkbox list against the salon's menu. A list of
     * ticks is naturally expressed as the resulting set, and sending the set means the client
     * cannot get out of step with the server by dropping one call in a sequence of five.
     *
     * <p>It also removes an entire class of bug: with add/remove there is a duplicate check, an
     * ordering question, and a partial-failure state where three of five ticks saved. Replace-set
     * has none of those — it either applied or it didn't.
     *
     * @param serviceIds may be EMPTY, which means "this stylist does none of our services". That
     *                   is a real thing to say (a trainee, or somebody being wound down) and must
     *                   not be confused with "no change" — which is why this is never optional.
     */
    public record StylistServiceRequest(@NotNull List<UUID> serviceIds) {}

    /**
     * One service a stylist performs at a salon.
     *
     * <h2>Session 67 — THE PER-STYLIST DURATION AND PRICE ARE GONE, and Darshan was right</h2>
     * Session 66 carried {@code actualDurationMinutes} and {@code overridePricePaise}: how long
     * THIS stylist takes, and what THIS stylist charges. Darshan's answer:
     *
     * <blockquote>"Why we required services plus timings ... timing is standard for service and
     * applicable all stylish"</blockquote>
     *
     * He is right, and the reason is worth writing down rather than just deleting the fields. How
     * long a haircut takes is a property of THE HAIRCUT. A salon that has decided a colour is 90
     * minutes has made that decision once, for the service, and expects it to hold. Modelling it
     * per stylist doesn't capture a real distinction — it creates one, and then demands the owner
     * maintain it for every stylist times every service. With 100 stylists and 30 services that
     * is 3,000 numbers nobody will ever revisit, and the moment they go stale the booking
     * algorithm is sizing appointments off figures no one believes.
     *
     * <p>The same argument kills the price override, and he confirmed it: one price per service.
     *
     * <p>What is left is the only thing that was ever a real per-stylist fact: <b>does this person
     * do this service or not.</b> The duration and price below are the SALON'S, echoed from the
     * service row so a client can render a full line without a second fetch — not per-stylist
     * values wearing a different name.
     *
     * @param serviceName     resolved from the salon's menu. Null only when the service row has
     *                        vanished — shown as a broken assignment rather than hidden, because
     *                        it is the owner's to notice and remove
     * @param durationMinutes THE SERVICE'S duration. Same for every stylist who performs it
     * @param pricePaise      THE SERVICE'S price. Same for every stylist who performs it
     * @param serviceArchived retired by the salon: the link survives but nobody can book it
     */
    public record StylistServiceResponse(UUID id, UUID stylistId, UUID serviceId,
                                          String serviceName, String serviceCategory,
                                          int durationMinutes, Long pricePaise,
                                          boolean serviceArchived) {}

    public record ErrorResponse(String error, String message) {}
}
