package com.bmp.rewards.internal.repository;

import com.bmp.rewards.internal.entity.CheckoutDiscount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CheckoutDiscountRepository extends JpaRepository<CheckoutDiscount, UUID> {
}
