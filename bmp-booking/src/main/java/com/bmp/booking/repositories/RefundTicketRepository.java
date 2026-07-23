package com.bmp.booking.repositories;

import com.bmp.booking.entities.RefundTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefundTicketRepository extends JpaRepository<RefundTicket, UUID> {
}
