package com.bmp.auth.dto;

import java.util.UUID;

/** Local mirror of bmp-salon's StylistDtos.CreateStylistRequest — sent at STYLIST signup. */
public record CreateStylistRequest(String name, UUID userId) {}
