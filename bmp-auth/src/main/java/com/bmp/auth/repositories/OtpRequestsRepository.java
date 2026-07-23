package com.bmp.auth.repositories;

import com.bmp.auth.entities.OtpRequests;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OtpRequestsRepository extends JpaRepository<OtpRequests, UUID> {
    Optional<OtpRequests> findTopByPhoneOrderByCreatedAtDesc(String phone);
}
