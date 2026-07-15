package com.bmp.payment.internal.repository;

import com.bmp.payment.internal.entity.CommissionLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CommissionLedgerRepository extends JpaRepository<CommissionLedger, UUID> {
}
