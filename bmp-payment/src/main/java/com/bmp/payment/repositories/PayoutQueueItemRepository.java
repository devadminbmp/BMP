package com.bmp.payment.repositories;

import com.bmp.payment.entities.PayoutQueueItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PayoutQueueItemRepository extends JpaRepository<PayoutQueueItem, UUID> {
}
