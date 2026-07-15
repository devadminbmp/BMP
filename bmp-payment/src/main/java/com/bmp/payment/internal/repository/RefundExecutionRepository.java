package com.bmp.payment.internal.repository;

import com.bmp.payment.internal.entity.RefundExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefundExecutionRepository extends JpaRepository<RefundExecution, UUID> {
}
