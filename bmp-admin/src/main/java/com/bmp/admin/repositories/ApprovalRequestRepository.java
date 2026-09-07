package com.bmp.admin.repositories;

import com.bmp.admin.entities.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/** Pending approvals, and the history of them. Session 58. */
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {

    /** THE approver's queue. Backed by idx_approval_queue. */
    List<ApprovalRequest> findByCurrentApproverRoleAndStatusOrderByCreatedAtAsc(String role, String status);

    /** "What did I ask for, and did it land?" */
    List<ApprovalRequest> findByRequestedByStaffIdOrderByCreatedAtDesc(UUID staffId);

    /** Everything raised out of one ticket — shown inline in the conversation. */
    List<ApprovalRequest> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);

    /** A badge on the console nav: how many things are waiting on me. */
    long countByCurrentApproverRoleAndStatus(String role, String status);

    /**
     * Approved but never carried out. The queue nobody thinks to look at.
     *
     * <p>These are the dangerous ones: somebody signed off, the executor threw, and a customer has
     * been told they are getting something. Surfaced on the ops dashboard rather than left to be
     * discovered when the customer chases.
     */
    List<ApprovalRequest> findByStatusOrderByCreatedAtAsc(String status);

    /**
     * APR-000042 from a sequence.
     *
     * <p>NOT a row count. Session 44's ticket references were `count(*) + 1`, which duplicates
     * under concurrency and skips when a row is deleted — the same mistake would be worse here,
     * because two approvals sharing a reference is two people discussing different money.
     */
    @Query(value = "SELECT nextval('admin_schema.approval_ref_seq')", nativeQuery = true)
    long nextRequestNumber();
}
