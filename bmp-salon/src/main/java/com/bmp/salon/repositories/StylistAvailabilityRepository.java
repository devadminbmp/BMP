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
}
