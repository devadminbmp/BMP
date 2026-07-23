package com.bmp.salon.repositories;

import com.bmp.salon.entities.WalkInBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WalkInBlockRepository extends JpaRepository<WalkInBlock, UUID> {

    /** Session 8 (availability algorithm): a stylist's walk-in blocks for one date — each
     * one is a busy window the same as a real booking. */
    List<WalkInBlock> findByStylistIdAndBlockDate(UUID stylistId, LocalDate blockDate);
}
