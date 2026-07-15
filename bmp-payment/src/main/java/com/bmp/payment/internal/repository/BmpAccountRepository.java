package com.bmp.payment.internal.repository;

import com.bmp.payment.internal.entity.BmpAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BmpAccountRepository extends JpaRepository<BmpAccount, UUID> {
}
