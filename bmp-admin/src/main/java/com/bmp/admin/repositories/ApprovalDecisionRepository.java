package com.bmp.admin.repositories;

import com.bmp.admin.entities.ApprovalDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** The append-only decision trail. Session 58. Backed by idx_decision_request. */
public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, UUID> {

    /** Oldest first, so it reads as a story: finance declined, ops approved. */
    List<ApprovalDecision> findByRequestIdOrderByCreatedAtAsc(UUID requestId);
}
