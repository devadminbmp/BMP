package com.bmp.payment.internal.repository;

import com.bmp.payment.internal.entity.PayoutQueueItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PayoutQueueItemRepository extends JpaRepository<PayoutQueueItem, UUID> {
}
