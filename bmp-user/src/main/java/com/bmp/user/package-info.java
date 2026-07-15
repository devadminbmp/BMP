/**
 * User MODULE — identity, OTP auth, profiles, roles.
 *
 * <p>Public surface: com.bmp.user.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: user_schema — users, user_roles, otp_requests, refresh_tokens, onboarding_state.
 *
 * <p><b>Session 5:</b> this is now an independently-deployable Spring Boot
 * service (see its own pom.xml/Application.java/application.yml), not a Modulith
 * module of one shared deployable. Spring Modulith annotation removed accordingly.
 */
package com.bmp.user;
