package com.bmp.booking.repositories;

import com.bmp.booking.entities.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {
    Page<Booking> findByCustomerId(UUID customerId, Pageable pageable);
    Page<Booking> findByCustomerIdAndStatus(UUID customerId, com.bmp.booking.api.BookingStatus status, Pageable pageable);
    long countByBookingRefStartingWith(String prefix);

    // ---- Session 16: the salon side of bookings (manager desk) ----------------------------
    // Until now bookings could only be read by customer. A salon could not see its own
    // day, which made every operational screen impossible to build.

    Page<Booking> findBySalonIdOrderByCreatedAtDesc(UUID salonId, Pageable pageable);

    Page<Booking> findBySalonIdAndStatusOrderByCreatedAtDesc(
            UUID salonId, com.bmp.booking.api.BookingStatus status, Pageable pageable);

    /**
     * Session 21: the staff console's booking lookup.
     *
     * <p>Matches on the human reference only — people read out "seven seven four one" and drop
     * the prefix. Deliberately NOT a free-text search over customers: browsing bookings is what
     * an internal tool shouldn't make easy, for the same reason the console has no customer list.
     */
    java.util.List<Booking> findByBookingRefContainingIgnoreCase(String bookingRef);

    /**
     * Session 22: drives the {@code new_users} coupon audience.
     *
     * <p>bmp-booking owns the fact "has this customer ever booked", so it answers rather than
     * bmp-rewards duplicating the query — two sources of truth for "is this their first
     * booking" is two answers that eventually differ.
     */
    long countByCustomerId(UUID customerId);

    // ---- Session 36: one customer, at ONE salon -------------------------------------------

    /**
     * A single customer's bookings <b>at one salon</b>.
     *
     * <h2>Both parameters, always. This is the privacy boundary.</h2>
     * The salon-scoped queries above answer "everything at my salon" and the customer-scoped
     * ones answer "everything for me". Neither answers "this customer, at my salon" — so until
     * now the returning-customer view could not be built, and the obvious shortcut was for a
     * screen to call {@code findByCustomerId} and filter in Java.
     *
     * <p>That shortcut would have handed a salon <b>every booking that customer has made
     * anywhere on BMP</b> before the filter ran. Which salon they went to last month is another
     * salon's commercial data and the customer's private business, and "we filtered it in the
     * UI" is not a defence when the JSON already crossed the wire.
     *
     * <p>Encoding the pair in the method name makes the boundary structural rather than a rule
     * somebody has to remember. There is deliberately no single-argument variant for the salon
     * side.
     */
    Page<Booking> findBySalonIdAndCustomerIdOrderByCreatedAtDesc(
            UUID salonId, UUID customerId, Pageable pageable);

    /**
     * The header numbers for that view: how many visits, what they've spent, when they first and
     * last came.
     *
     * <p>A projection rather than loading every booking and summing in Java. A loyal customer of
     * three years is a few hundred rows, which is fine to page through but wasteful to load in
     * full just to produce four numbers on a card — and the count has to cover ALL their visits,
     * not just the page being shown.
     *
     * <p>{@code completedVisits} counts only COMPLETED, while {@code totalBookings} counts
     * everything. The gap between the two is the interesting part: a customer with nine bookings
     * and three completed is a customer who keeps cancelling, and averaging that away would hide
     * the one thing the salon most needs to see.
     *
     * <p>Money is summed in integer paise. {@code COALESCE} because {@code SUM} over no rows is
     * null, and a null landing in a {@code long} projection is an exception on a screen that
     * just wanted to show zero.
     */
    /*
     * NATIVE, not JPQL, and the reason is specific rather than a preference.
     *
     * `final_amount_paise` maps to a Money value object through MoneyAttributeConverter. JPQL
     * `sum()` over an attribute with an AttributeConverter is not reliably supported — Hibernate
     * has to decide whether it is summing Money or Long, and the answer has changed between
     * versions. The column itself is a plain BIGINT, so dropping to SQL removes the ambiguity
     * entirely.
     *
     * The cost of going native is real and worth naming: this query now knows the schema name
     * and the column names, so a rename in a future migration breaks it at RUNTIME rather than
     * at compile time. `status` is compared as text, which is safe because the entity is
     * @Enumerated(EnumType.STRING) — if anyone ever switches that to ORDINAL, this silently
     * returns zeros. That is the one thing to check if these numbers ever look wrong.
     */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
            select
                count(*)                                                            as totalBookings,
                coalesce(sum(case when status = 'COMPLETED' then 1 else 0 end), 0)   as completedVisits,
                coalesce(sum(case when status = 'CANCELLED' then 1 else 0 end), 0)   as cancelledCount,
                coalesce(sum(case when status = 'NO_SHOW'   then 1 else 0 end), 0)   as noShowCount,
                coalesce(sum(case when status = 'COMPLETED' then final_amount_paise else 0 end), 0) as totalSpentPaise,
                min(created_at)                                                     as firstVisit,
                max(created_at)                                                     as lastVisit
            from booking_schema.booking
            where salon_id = :salonId and customer_id = :customerId
            """)
    CustomerAtSalonStats customerStats(
            @org.springframework.data.repository.query.Param("salonId") UUID salonId,
            @org.springframework.data.repository.query.Param("customerId") UUID customerId);

    /**
     * Spring Data projects the query above onto this interface by getter name.
     *
     * <p>An interface rather than a record because the {@code select} aliases map to getters by
     * name. A record would bind <b>positionally</b>, so reordering two columns of the same type
     * would compile, run, and quietly swap the numbers — which for "cancelled" and "no-show" is
     * a distinction the salon cares about.
     *
     * <p>The aliases in the SQL above must match these getter names exactly, minus {@code get}.
     * PostgreSQL folds unquoted identifiers to lower case, and Spring Data matches
     * case-insensitively, so {@code totalBookings} → {@code totalbookings} → {@code
     * getTotalBookings()} resolves. Do not "tidy" the aliases to snake_case; that breaks it.
     *
     * <p>Boxed types, not primitives: a projection over zero rows can hand back nulls despite
     * the {@code COALESCE}, and a null unboxing into a {@code long} is an NPE on a screen that
     * only wanted to show a zero. The service defaults them.
     */
    interface CustomerAtSalonStats {
        Long getTotalBookings();
        Long getCompletedVisits();
        Long getCancelledCount();
        Long getNoShowCount();
        Long getTotalSpentPaise();
        java.time.Instant getFirstVisit();
        java.time.Instant getLastVisit();
    }
}
