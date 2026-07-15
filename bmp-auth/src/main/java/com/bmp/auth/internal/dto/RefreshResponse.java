package com.bmp.auth.internal.dto;

public record RefreshResponse(String accessToken, long expiresIn) {}
