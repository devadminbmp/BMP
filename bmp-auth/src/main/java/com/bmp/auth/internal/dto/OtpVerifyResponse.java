package com.bmp.auth.internal.dto;

import java.util.UUID;

public record OtpVerifyResponse(UUID userId, String refreshToken, String accessToken, long expiresIn) {}
