package com.bmp.rewards.internal.repository;

import com.bmp.rewards.internal.entity.WinBackJobLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WinBackJobLogRepository extends JpaRepository<WinBackJobLog, UUID> {
}
