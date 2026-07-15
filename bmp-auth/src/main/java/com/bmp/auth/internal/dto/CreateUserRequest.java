package com.bmp.auth.internal.dto;

/** Sent to bmp-user-service to create a brand-new user on first successful OTP verify. */
public record CreateUserRequest(String phone, String defaultRole) {}
