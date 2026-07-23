package com.bmp.auth.dto;

/** Sent to bmp-user-service to create a brand-new user on first successful OTP verify.
 * Session 6: added email (dual-channel OTP + role-based signup carries it now). */
public record CreateUserRequest(String phone, String email, String defaultRole) {}
