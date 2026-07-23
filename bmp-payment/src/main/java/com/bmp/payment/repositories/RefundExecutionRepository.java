package com.bmp.payment.repositories;

import com.bmp.payment.entities.RefundExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefundExecutionRepository extends JpaRepository<RefundExecution, UUID> {
}
