-- ════════════════════════════════════════════════════════════════════════════════════════════════
--  V004 — device push tokens. Session 64.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
--  Email has been the only channel that reaches a human since Session 6. SMS and WhatsApp are
--  interfaces with logging implementations and no provider account. That is defensible for OTP,
--  which people are actively waiting for with the app open — and useless for everything that
--  matters LATER: your appointment is tomorrow, the salon just cancelled, support replied.
--
--  Push is the right channel for those because it interrupts, and because it costs nothing per
--  message once the plumbing exists.
--
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  WHY THE TOKEN LIVES HERE AND NOT ON THE USER
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  A push token is a DELIVERY ADDRESS, exactly like an email address is for SMTP — and bmp-user
--  already owns the person, while bmp-notification owns getting things to them. Putting it in
--  user_schema would mean bmp-notification calling bmp-user on every send, on the hot path, for a
--  value that only bmp-notification ever uses.
--
--  It is also a per-DEVICE fact, not a per-person one: the same account on a phone and a tablet has
--  two tokens and should get both notifications. A column on `users` could not express that without
--  becoming a list, at which point it is this table with extra steps.
--
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  TOKENS ROT, AND THAT IS NORMAL
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  Expo (and FCM/APNs beneath it) invalidate tokens when an app is reinstalled, restored to a new
--  device, or simply left unopened long enough. Sending to a dead token returns `DeviceNotRegistered`
--  and is not an error worth alerting on — it is the expected end of a token's life.
--
--  So `disabled_at` is a soft state, not a delete: keeping the row means a reinstall that returns
--  the SAME token can be revived, and means "we stopped sending" is distinguishable from "we never
--  had one" when somebody asks why they got no notification.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS notification_schema.push_token (
    id                 UUID PRIMARY KEY NOT NULL,          -- UUIDv7
    user_id            UUID NOT NULL,                      -- logical ref -> user_schema.users

    /*
     * The Expo push token, e.g. ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx].
     *
     * 200 chars: an Expo token is ~41, an FCM token can exceed 160, and this column should survive
     * a migration to raw FCM without a schema change. Cheap insurance against a truncation bug that
     * would silently produce undeliverable tokens.
     */
    token              VARCHAR(200) NOT NULL,

    -- 'ios' | 'android' | 'web'. Kept because delivery behaviour genuinely differs — iOS needs a
    -- badge count, Android does not — and because "no Android user got this" is a real question.
    platform           VARCHAR(10) NOT NULL,

    -- Which install this is. Lets one device REPLACE its own token on reinstall rather than
    -- accumulating a new row every time, which is how a person ends up getting four copies.
    device_id          VARCHAR(120),

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    /*
     * Set when the provider tells us the token is dead, or when the user signs out.
     * Soft, deliberately — see the header. A revived token clears this rather than inserting anew.
     */
    disabled_at        TIMESTAMPTZ,
    disabled_reason    VARCHAR(120)
);

/*
 * One row per token, globally.
 *
 * NOT (user_id, token): the same physical device handed from one person to another must not end up
 * delivering the first person's notifications to the second. The token identifies the device, so
 * re-registering it under a new user must MOVE it, and a unique constraint on the token alone is
 * what forces that upsert rather than allowing a duplicate.
 */
CREATE UNIQUE INDEX IF NOT EXISTS uk_push_token_token
    ON notification_schema.push_token (token);

-- The send path: every live token for one person.
CREATE INDEX IF NOT EXISTS idx_push_token_user
    ON notification_schema.push_token (user_id)
    WHERE disabled_at IS NULL;

COMMENT ON TABLE notification_schema.push_token IS
    'Per-device push delivery addresses. Owned by bmp-notification because only it sends. See V004 header.';
COMMENT ON COLUMN notification_schema.push_token.disabled_at IS
    'Soft: a dead token is kept so a reinstall can revive it, and so "stopped sending" differs from "never had one".';
