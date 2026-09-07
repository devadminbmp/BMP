package com.bmp.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors bmp-user-service's user response shape. Kept minimal to what auth needs.
 *
 * <p>Session 6: added email (dual-channel OTP needs to know where to send).
 * <p>Session 13: added deactivatedAt — a non-null value on login triggers auto-reactivation
 * (the fresh OTP login IS the "I want my account back" signal; see AuthService).
 * <p>Session 65: added blockedAt.
 *
 * <h2>deactivatedAt and blockedAt are opposites, not variations</h2>
 * <pre>
 *   deactivatedAt != null  →  they paused themselves. LOG THEM IN and reactivate. Intended.
 *   blockedAt     != null  →  staff stopped them. REFUSE. A login must never clear it.
 * </pre>
 * Reading only the first field is how "Block account" shipped as a button that did nothing: the
 * console set {@code deactivated_at}, and the login path treated it as "welcome back".
 *
 * <h2>This record is a hand-maintained subset — a second copy of someone else's contract</h2>
 * bmp-user's {@code UserResponse} has sixteen components; this has eight. That is deliberate (auth
 * has no business knowing a person's hair type) and it is also a standing hazard: nothing in the
 * build checks the two agree, so a field added there is simply invisible here until somebody
 * notices. It binds by NAME, so a mismatch is silently null rather than a compile error.
 *
 * <p>If you add a field here, make sure bmp-user actually sends it under that exact name.
 */
public record UserDto(UUID id, String phone, String email, String name, String defaultRole,
                       boolean isVerified, Instant deactivatedAt, Instant blockedAt) {}
