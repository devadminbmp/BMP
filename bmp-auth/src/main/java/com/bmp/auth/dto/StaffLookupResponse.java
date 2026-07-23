package com.bmp.auth.dto;

import java.util.UUID;

/** Local mirror of bmp-salon's StaffDtos.StaffLookupResponse. */
public record StaffLookupResponse(UUID salonId, String role) {}
