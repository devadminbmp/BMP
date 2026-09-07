-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V006 — a block that actually blocks. Session 65.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── WHAT WAS ACTUALLY HAPPENING ────────────────────────────────────────────────────────────────
-- Session 65 gave support and ops a "Block account" action. It called
-- `UserService.deactivate()`, which sets `deactivated_at`. That does not block anybody:
--
--   1. bmp-auth REACTIVATES any account with a non-null `deactivated_at` the moment its owner
--      completes an OTP login (V004, Instagram-style — AuthService.verifyOtp says so in a comment).
--      So a blocked person logged in again and was silently un-blocked, by design, by a line
--      written for a completely different purpose.
--
--   2. Neither `/auth/refresh` nor `/auth/me` looked at `deactivated_at` at all, so somebody
--      already signed in stayed signed in until their refresh token expired — days later.
--
-- The button worked, the audit entry was written, and nothing happened. That is worse than not
-- having the button, because an agent believes the account is contained and stops watching it.
--
-- ── WHY A SEPARATE COLUMN RATHER THAN REUSING deactivated_at ────────────────────────────────────
-- Because the two states mean opposite things about the person's WISHES:
--
--   deactivated_at  "I want a break."      Reversing it on their next login is the correct,
--                                          intended behaviour and must not change.
--   blocked_at      "We have stopped you." Reversing it on their next login is the bug.
--
-- One column cannot hold both, and overloading it would mean the reactivation path has to guess
-- which kind of deactivation it is looking at. A second column lets each rule stay simple and
-- lets an account be BOTH (a person who deactivated themselves and was later blocked) without the
-- two states destroying each other.
--
-- ── WHY blocked_by AND blocked_reason SIT ON THE ROW ────────────────────────────────────────────
-- The audit log already records who blocked whom and why. It is the durable record and it is not
-- convenient: answering "why can't this person log in?" would mean a text search of the audit
-- table from a different service. These two columns make the answer visible wherever the account
-- is, which is where the question is always asked.
--
-- ── NULLABLE, NO BACKFILL ──────────────────────────────────────────────────────────────────────
-- Every existing account is unblocked, so NULL is exactly right and there is nothing to migrate.
-- Additive and re-runnable, per the project's migration rules.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE user_schema.users
    ADD COLUMN IF NOT EXISTS blocked_at     TIMESTAMPTZ,
    -- The bmp_staff id of whoever did it. NOT a foreign key: bmp_staff lives in admin_schema and
    -- is owned by another service, and a cross-service FK would couple two schemas' deploy order
    -- for the sake of a field that is only ever read for display.
    ADD COLUMN IF NOT EXISTS blocked_by     UUID,
    ADD COLUMN IF NOT EXISTS blocked_reason VARCHAR(500);

COMMENT ON COLUMN user_schema.users.blocked_at IS
    'Set by staff. Unlike deactivated_at, a login does NOT clear this — see V006 and AuthService.';
COMMENT ON COLUMN user_schema.users.blocked_by IS
    'admin_schema.bmp_staff id. Deliberately not an FK — different service, different schema.';
COMMENT ON COLUMN user_schema.users.blocked_reason IS
    'Required by the application when blocking. Shown to staff, never to the blocked person.';

/*
 * Partial index: blocked accounts only.
 *
 * The vast majority of rows have blocked_at IS NULL, so a full index would be almost entirely
 * dead weight. The only query that wants this is "show me everyone currently blocked", which the
 * partial index answers while staying tiny.
 */
CREATE INDEX IF NOT EXISTS ix_users_blocked
    ON user_schema.users (blocked_at)
    WHERE blocked_at IS NOT NULL;
