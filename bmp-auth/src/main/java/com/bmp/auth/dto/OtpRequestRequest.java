package com.bmp.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Session 6: email is optional here — required only when the phone belongs to a brand-new
 * user (enforced in AuthService, not the DTO, since that depends on a DB lookup). For an
 * existing user's email on file is reused automatically so dual-channel delivery still works
 * without asking for it again on every login. */
public record OtpRequestRequest(
    @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "phone must be E.164, e.g. +919876543210")
    String phone,
    @Email String email
) {}
