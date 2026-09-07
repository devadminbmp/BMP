package com.bmp.auth.dto;

/** Sent to bmp-user-service to create a brand-new user on first successful OTP verify.
 * Session 6: added email (dual-channel OTP + role-based signup carries it now). */
/**
 * bmp-auth's mirror of bmp-user's CreateUserRequest. Session 65.
 *
 * <h2>The bug this had</h2>
 * bmp-user's record is {@code (phone, name, gender, age, email, defaultRole)} — six fields. This
 * one carried three. Jackson serialises only what is here, so {@code name} never left bmp-auth, and
 * EVERY account created through signup was nameless: the salon desk header fell back to a phone
 * number, the support console showed "(no name)", and the person who had typed "Nidhi" into the
 * form two screens earlier had no way to tell that it had been dropped.
 *
 * <p>Nothing failed. A record with fewer fields is not a compile error, not a runtime error, and
 * not a validation error — {@code name} is nullable on the far side. The only symptom was a column
 * that stayed null, which reads as "the user did not enter a name".
 *
 * <h2>Keep this in step with bmp-user, or delete it</h2>
 * Two hand-maintained copies of one contract, with nothing checking they match. The right long-term
 * fix is a shared DTO in bmp-common, or a generated client; until then this comment is the only
 * thing standing between the next added field and the same silent loss.
 *
 * <p>{@code gender} and {@code age} are deliberately still omitted: signup does not collect them,
 * so sending null adds a field that can drift for no benefit. They are set later through the
 * profile editor, which calls bmp-user directly.
 */
public record CreateUserRequest(String phone, String name, String email, String defaultRole) {}
