package com.bmp.auth.internal.repository;

import com.bmp.auth.internal.entity.OtpRequests;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OtpRequestsRepository extends JpaRepository<OtpRequests, UUID> {
    Optional<OtpRequests> findTopByPhoneOrderByCreatedAtDesc(String phone);
}
