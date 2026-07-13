/**
 * User MODULE — identity, OTP auth, profiles, roles.
 *
 * <p>Public surface: com.bmp.user.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: user_schema — users, user_roles, otp_requests, refresh_tokens, onboarding_state.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "User",
    allowedDependencies = { "common" }
)
package com.bmp.user;
