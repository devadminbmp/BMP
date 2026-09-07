package com.bmp.admin.repositories;

import com.bmp.admin.entities.LeaveEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Per-person leave allowances. Overrides the role plan. V016, Session 65. */
public interface LeaveEntitlementRepository extends JpaRepository<LeaveEntitlement, UUID> {

    /** One person, one type, one year. Backed by uq_leave_entitlement. */
    Optional<LeaveEntitlement> findByStaffIdAndLeaveTypeAndFyStartYear(UUID staffId, String leaveType, int fyStartYear);

    /** Everything this person is entitled to this year — their balance card. */
    List<LeaveEntitlement> findByStaffIdAndFyStartYear(UUID staffId, int fyStartYear);

    /**
     * EVERYBODY's entitlements for a year, in one read. The HR overview.
     *
     * <p>Backed by idx_leave_entitlement_year. Deliberately not per-person: the overview shows the
     * whole team, and asking per employee is the N+1 that makes the page slower with every hire.
     */
    List<LeaveEntitlement> findByFyStartYear(int fyStartYear);
}
