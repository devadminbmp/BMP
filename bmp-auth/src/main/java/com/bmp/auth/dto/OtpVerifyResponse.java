package com.bmp.auth.dto;

import java.util.UUID;

public record OtpVerifyResponse(UUID userId, String refreshToken, String accessToken, long expiresIn) {}
