package com.bmp.auth.dto;

public record RefreshResponse(String accessToken, long expiresIn) {}
