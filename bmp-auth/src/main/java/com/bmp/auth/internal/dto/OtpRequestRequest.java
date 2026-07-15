package com.bmp.auth.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OtpRequestRequest(
    @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "phone must be E.164, e.g. +919876543210")
    String phone
) {}
