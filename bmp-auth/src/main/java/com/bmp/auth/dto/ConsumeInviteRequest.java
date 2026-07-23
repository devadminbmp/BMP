package com.bmp.auth.dto;

/** Local mirror of bmp-salon's StaffDtos.ConsumeInviteRequest — sent over Feign at MANAGER signup. */
public record ConsumeInviteRequest(String token, String phone, String userId) {}
