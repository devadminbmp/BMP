package com.bmp.payment.internal.repository;

import com.bmp.payment.internal.entity.PayoutBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PayoutBatchRepository extends JpaRepository<PayoutBatch, UUID> {
}
