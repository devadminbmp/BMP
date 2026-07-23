package com.bmp.payment.repositories;

import com.bmp.payment.entities.BmpAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BmpAccountRepository extends JpaRepository<BmpAccount, UUID> {
}
