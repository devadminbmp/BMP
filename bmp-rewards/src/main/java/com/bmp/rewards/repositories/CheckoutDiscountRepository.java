package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.CheckoutDiscount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CheckoutDiscountRepository extends JpaRepository<CheckoutDiscount, UUID> {
}
