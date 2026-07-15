package com.bmp.auth.internal.repository;

import com.bmp.auth.internal.entity.RefreshTokens;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokensRepository extends JpaRepository<RefreshTokens, UUID> {
    List<RefreshTokens> findByUserIdAndRevokedFalse(UUID userId);
    Optional<RefreshTokens> findBySelectorAndRevokedFalse(String selector);
}
