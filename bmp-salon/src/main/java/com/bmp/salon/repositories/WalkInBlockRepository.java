package com.bmp.salon.repositories;

import com.bmp.salon.entities.WalkInBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WalkInBlockRepository extends JpaRepository<WalkInBlock, UUID> {
}
