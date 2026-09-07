package com.bmp.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response to {@code POST /api/v1/auth/otp/request}.
 *
 * <p>Session 14: {@code resendAvailableAt} added so the frontend can show an accurate
 * "resend code in Ns" countdown instead of hardcoding the cooldown. Requesting another OTP
 * for the same phone before this instant returns HTTP 429 (see AuthService — cooldown is
 * {@code AuthService.OTP_RESEND_COOLDOWN_SECONDS}).
 *
 * @param otpRequestId      id of the created otp_requests row (opaque; the client doesn't need it to verify, verification is by phone)
 * @param expiresAt         when the code stops working (~5 min out) — show a "code expires in" timer off this
 * @param resendAvailableAt earliest instant a new /otp/request will be accepted for this phone — drive the resend button off this
 */
/**
 * @param accountExists    true when this phone already has an account. Session 65.
 *
 *   Added because a signup that silently behaves as a login is indistinguishable from a broken
 *   system. Someone entering their number with a NEW email address gets a code mailed to their OLD
 *   one — which is correct (see below) and completely invisible, so it reads as the app sending
 *   codes to random addresses.
 *
 * @param codeSentToMasked where the code actually went, masked: {@code n••••••••01@gmail.com}.
 *
 *   THE MASK IS THE WHOLE POINT. Showing the full address would let anyone type a stranger's phone
 *   number and read back their email — a free enumeration oracle. Showing NOTHING leaves the person
 *   who genuinely owns the account unable to work out which of their inboxes to check. Masked shows
 *   enough for the owner to recognise it and not enough for anyone else to learn it.
 *
 *   Null on first-time signup, where the code goes to the address just typed and there is nothing
 *   to disclose.
 *
 * <h2>Why the typed email is ignored for an existing account</h2>
 * {@code AuthService.requestOtp} resolves the destination as the STORED email, never the submitted
 * one. If the request's email won, anybody could type your phone number with their own address and
 * be sent your login code — account takeover in a single step. That behaviour is correct and is not
 * changing; these two fields exist so it stops being a secret.
 */
public record OtpRequestResponse(UUID otpRequestId, Instant expiresAt, Instant resendAvailableAt,
                                  boolean accountExists, String codeSentToMasked) {}
