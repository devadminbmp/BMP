package com.bmp.payment.internal.repository;

import com.bmp.payment.internal.entity.SaasSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SaasSubscriptionRepository extends JpaRepository<SaasSubscription, UUID> {
}
