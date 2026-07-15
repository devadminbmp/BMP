package com.bmp.booking.internal.repository;

import com.bmp.booking.internal.entity.RefundTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefundTicketRepository extends JpaRepository<RefundTicket, UUID> {
}
