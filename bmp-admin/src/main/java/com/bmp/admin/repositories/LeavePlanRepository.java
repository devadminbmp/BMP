package com.bmp.admin.repositories;

import com.bmp.admin.entities.LeavePlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Org-wide leave allowances per role. V016, Session 65. */
public interface LeavePlanRepository extends JpaRepository<LeavePlan, UUID> {

    /** The default for one role and type in one year. Backed by uq_leave_plan. */
    Optional<LeavePlan> findByRoleAndLeaveTypeAndFyStartYear(String role, String leaveType, int fyStartYear);

    /**
     * A whole year's plan in one read.
     *
     * <p>The HR screen and every balance calculation want the same handful of rows; fetching them
     * once and indexing in memory avoids a query per (role, type) pair, which for seven roles and
     * four types is 28 round trips to answer one screen.
     */
    List<LeavePlan> findByFyStartYear(int fyStartYear);
}
