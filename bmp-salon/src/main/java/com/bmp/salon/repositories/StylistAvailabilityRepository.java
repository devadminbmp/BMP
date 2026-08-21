package com.bmp.salon.repositories;

import com.bmp.salon.entities.StylistAvailability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface StylistAvailabilityRepository extends JpaRepository<StylistAvailability, UUID> {

    /** Session 8 (availability algorithm): weekly_template rows for a stylist+salon+weekday
     * (their recurring working hours and breaks for that day of the week). */
    List<StylistAvailability> findByStylistIdAndSalonIdAndRuleTypeAndDayOfWeek(
            UUID stylistId, UUID salonId, String ruleType, int dayOfWeek);

    /** exception/leave rows for one specific date — these override the weekly template
     * for that date only. */
    List<StylistAvailability> findByStylistIdAndSalonIdAndRuleTypeAndSpecificDate(
            UUID stylistId, UUID salonId, String ruleType, LocalDate specificDate);

    // ---- Session 18: the WRITE side (editing a stylist's availability) --------------------
    // Until now these rows could only be read. The algorithm consumed a table nothing could
    // populate, which meant every stylist was silently unbookable.

    /** The whole weekly template, all seven days, ordered for direct rendering. */
    List<StylistAvailability> findByStylistIdAndSalonIdAndRuleTypeOrderByDayOfWeekAscStartTimeAsc(
            UUID stylistId, UUID salonId, String ruleType);

    /**
     * Replacing one weekday's rules is a delete-then-insert: a template day is a SET, and
     * diffing rows individually would be more code for no benefit. Scoped to one weekday so a
     * change to Tuesday can never disturb Wednesday.
     */
    void deleteByStylistIdAndSalonIdAndRuleTypeAndDayOfWeek(
            UUID stylistId, UUID salonId, String ruleType, Integer dayOfWeek);

    /** Time off in a date window — the "what's coming up" list. */
    List<StylistAvailability> findByStylistIdAndSalonIdAndSpecificDateBetweenOrderBySpecificDateAsc(
            UUID stylistId, UUID salonId, LocalDate from, LocalDate to);

    /** Salon-scoped so one salon can never delete another salon's rule by guessing an id. */
    java.util.Optional<StylistAvailability> findByIdAndSalonId(UUID id, UUID salonId);
}
