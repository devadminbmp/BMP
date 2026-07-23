package com.bmp.admin.repositories;

import com.bmp.admin.entities.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {
    List<SupportTicket> findByStatus(String status);
    List<SupportTicket> findByAssignedStaffId(UUID assignedStaffId);
    List<SupportTicket> findByStatusAndAssignedStaffId(String status, UUID assignedStaffId);
    long countByTicketRefStartingWith(String prefix);
}
