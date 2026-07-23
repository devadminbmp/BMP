package com.bmp.payment.repositories;

import com.bmp.payment.entities.CommissionLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CommissionLedgerRepository extends JpaRepository<CommissionLedger, UUID> {
}
