package com.bmp.auth.internal.dto;

import jakarta.validation.constraints.NotBlank;

public record OtpVerifyRequest(
    @NotBlank String phone,
    @NotBlank String otp,
    String deviceFingerprint
) {}
