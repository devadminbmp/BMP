package com.bmp.payment.repositories;

import com.bmp.payment.entities.RazorpayLinkedAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RazorpayLinkedAccountRepository extends JpaRepository<RazorpayLinkedAccount, UUID> {
}
