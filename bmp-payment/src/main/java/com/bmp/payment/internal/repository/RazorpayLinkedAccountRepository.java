package com.bmp.payment.internal.repository;

import com.bmp.payment.internal.entity.RazorpayLinkedAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RazorpayLinkedAccountRepository extends JpaRepository<RazorpayLinkedAccount, UUID> {
}
