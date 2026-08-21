package com.bmp.admin.repositories;

import com.bmp.admin.entities.StaffSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffSessionRepository extends JpaRepository<StaffSession, UUID> {

    /** Single indexed lookup by selector — never a scan comparing secrets. */
    Optional<StaffSession> findBySelectorAndRevokedFalse(String selector);

    List<StaffSession> findByStaffIdAndRevokedFalse(UUID staffId);

    /**
     * Kill every session for one staff member at once.
     *
     * <p>Used when an account is suspended or offboarded. Without this, revoking access means
     * waiting for their token to expire — which on a Friday afternoon is not good enough.
     */
    @Modifying
    @Query("UPDATE StaffSession s SET s.revoked = true WHERE s.staffId = :staffId AND s.revoked = false")
    int revokeAllForStaff(@Param("staffId") UUID staffId);
}
