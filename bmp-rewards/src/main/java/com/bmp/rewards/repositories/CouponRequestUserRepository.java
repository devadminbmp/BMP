package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.CouponRequestUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CouponRequestUserRepository extends JpaRepository<CouponRequestUser, UUID> {
    List<CouponRequestUser> findByRequestId(UUID requestId);
}
