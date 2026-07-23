package com.bmp.payment.repositories;

import com.bmp.payment.entities.PayoutBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PayoutBatchRepository extends JpaRepository<PayoutBatch, UUID> {
}
