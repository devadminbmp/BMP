package com.bmp.notification.repositories;

import com.bmp.notification.entities.PushToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Device push addresses. Session 64 (V004). */
public interface PushTokenRepository extends JpaRepository<PushToken, UUID> {

    /**
     * By token, NOT by (user, token).
     *
     * <p>The token identifies the DEVICE. Looking it up per-user would let the same physical phone
     * hold rows for two people and deliver the first person's notifications to the second.
     */
    Optional<PushToken> findByToken(String token);

    /** Every live address for one person — the send path. A tablet and a phone both count. */
    List<PushToken> findByUserIdAndDisabledAtIsNull(UUID userId);
}
