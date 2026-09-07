package com.bmp.admin.repositories;

import com.bmp.admin.entities.AuthorityLimit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The authority matrix. Session 58.
 *
 * <p>Every method filters on {@code active} — a limit switched off must behave exactly like one
 * that was never configured, which is "no". Leaving a deactivated row visible to the lookup would
 * mean turning a permission off had no effect, quietly.
 */
public interface AuthorityLimitRepository extends JpaRepository<AuthorityLimit, UUID> {

    /** THE lookup. Unique by (action_type, role) — see uq_authority_action_role in V010. */
    Optional<AuthorityLimit> findByActionTypeAndRoleAndActiveTrue(String actionType, String role);

    /** The whole escalation path for one action, in order. */
    List<AuthorityLimit> findByActionTypeAndActiveTrueOrderByStepOrderAsc(String actionType);

    /** Everything one role may do — "what am I allowed to do?" on the console. */
    List<AuthorityLimit> findByRoleAndActiveTrueOrderByActionTypeAsc(String role);

    /** Every configured action, for the settings screen. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT a.actionType FROM AuthorityLimit a WHERE a.active = true ORDER BY a.actionType")
    List<String> findAllActionTypes();
}
