package com.bmp.admin.repositories;

import com.bmp.admin.entities.StaffActivation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffActivationRepository extends JpaRepository<StaffActivation, UUID> {

    /**
     * Redemption looks up by the HASH of the submitted code, not by staff id — the employee
     * only has the code, and asking them for anything else would make the link they were sent
     * useless on its own.
     */
    Optional<StaffActivation> findByCodeHashAndConsumedAtIsNull(String codeHash);

    /** Outstanding codes for one person — a re-issue invalidates these. */
    List<StaffActivation> findByStaffIdAndConsumedAtIsNull(UUID staffId);
}
