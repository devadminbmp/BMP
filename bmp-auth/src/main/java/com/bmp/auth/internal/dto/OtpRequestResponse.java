package com.bmp.auth.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record OtpRequestResponse(UUID otpRequestId, Instant expiresAt) {}
