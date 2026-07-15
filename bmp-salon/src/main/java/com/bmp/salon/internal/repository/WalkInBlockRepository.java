package com.bmp.salon.internal.repository;

import com.bmp.salon.internal.entity.WalkInBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WalkInBlockRepository extends JpaRepository<WalkInBlock, UUID> {
}
