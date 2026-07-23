package com.bmp.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record OtpRequestResponse(UUID otpRequestId, Instant expiresAt) {}
