package com.bmp.payment.repositories;

import com.bmp.payment.entities.SaasSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SaasSubscriptionRepository extends JpaRepository<SaasSubscription, UUID> {
}
