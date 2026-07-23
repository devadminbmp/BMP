package com.bmp.auth.dto;

import java.util.UUID;

/** Trimmed local mirror of bmp-salon's StylistDtos.StylistResponse — only what auth needs. */
public record StylistDto(UUID id, String name, UUID userId) {}
