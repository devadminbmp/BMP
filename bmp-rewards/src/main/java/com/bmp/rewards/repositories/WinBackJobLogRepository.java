package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.WinBackJobLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WinBackJobLogRepository extends JpaRepository<WinBackJobLog, UUID> {
}
