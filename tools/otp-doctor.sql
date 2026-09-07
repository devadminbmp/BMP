-- ════════════════════════════════════════════════════════════════════════════════════════════════
--  OTP DOCTOR — where did the code stop? Session 62.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
-- WHY THIS EXISTS
-- ────────────────────────────────────────────────────────────────────────────────────────────────
-- "The OTP isn't arriving" is four different faults wearing the same symptom, and the app reports
-- success for three of them. The code travels:
--
--     bmp-auth  ──writes──>  otp_requests            (did we even issue one?)
--         │
--         └────writes──────>  common_schema.outbox   (was the event recorded?)
--                                   │
--                        OutboxKafkaRelay, every 2s  (was it relayed, or is it stuck?)
--                                   │
--                                 Kafka
--                                   │
--                        NotificationDispatcher      (did anything try to send it?)
--                                   │
--                            notification_log        (sent, or failed, and why?)
--                                   │
--                        LoggingEmailSender or SMTP  (log-only = it went to a console)
--
-- Every hop below is one query. Run it right after asking for a code and read top to bottom: the
-- first section that is EMPTY is where it stopped.
--
-- USAGE
-- ────────────────────────────────────────────────────────────────────────────────────────────────
--   docker cp tools\otp-doctor.sql bmp-postgres-1:/tmp/otp-doctor.sql
--   docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/otp-doctor.sql
--
-- Reads nothing but its own diagnostics. Safe to run any time, against a local database.
--
-- WHAT IT DELIBERATELY DOES NOT SHOW
-- ────────────────────────────────────────────────────────────────────────────────────────────────
-- The code itself. `otp_requests` stores a bcrypt hash, never the plaintext — so this can tell you
-- a code was issued and cannot tell you what it was. That is the design, not a limitation of the
-- script: a query that printed live OTPs would be a credential dump with a helpful banner.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

\echo ''
\echo '=== 1. Did bmp-auth issue a code? (last 5) ================================='
\echo 'EMPTY  -> the request never reached bmp-auth. Check the gateway and the browser network tab.'
\echo 'ROWS   -> a code exists. The email column is where it was ADDRESSED.'
\echo ''

SELECT
    id,
    phone,
    COALESCE(email, '(NULL — UNDELIVERABLE)') AS addressed_to,
    created_at,
    expires_at,
    CASE WHEN expires_at < now() THEN 'expired' ELSE 'live' END AS state,
    attempts,
    consumed_at
FROM user_schema.otp_requests
ORDER BY created_at DESC
LIMIT 5;

\echo ''
\echo '=== 2. Was the event written to the outbox? ================================'
\echo 'EMPTY     -> bmp-auth wrote the OTP row but not the event. That is a bug, not config.'
\echo 'processed = false and rising attempts -> the relay is failing; read last_error.'
\echo 'processed = false and attempts = 0    -> the relay is NOT RUNNING.'
\echo '     Check bmp.outbox.relay.enabled and that bmp-auth has @EnableScheduling.'
\echo ''

SELECT
    id,
    event_type,
    created_at,
    processed,
    processed_at,
    attempts,
    COALESCE(last_error, '—') AS last_error
FROM common_schema.outbox
WHERE event_type = 'otp.requested'
ORDER BY created_at DESC
LIMIT 5;

\echo ''
\echo '=== 3. Did bmp-notification try to send it? ================================'
\echo 'EMPTY  -> the event never arrived. bmp-notification is down, or Kafka is, or the'
\echo '          consumer is on a different topic. Check bmp-notification is running FIRST —'
\echo '          it is the single most common cause.'
\echo 'status = sent   -> it was handed to the email sender. If nothing arrived, the sender was'
\echo '                   in LOG-ONLY mode: the code is in the bmp-notification CONSOLE.'
\echo '                   Look for "EMAIL IS IN LOG-ONLY MODE" in its startup log.'
\echo 'status = failed -> read error_reason. Gmail rejects a From that is not the SMTP user.'
\echo ''

SELECT
    channel,
    template_code,
    status,
    COALESCE(error_reason, '—') AS error_reason,
    created_at,
    sent_at
FROM notification_schema.notification_log
WHERE template_code = 'otp_code'
ORDER BY created_at DESC
LIMIT 10;

\echo ''
\echo '=== 4. Accounts with no email — these can NEVER receive a code ============='
\echo 'Email is the only live channel; SMS and WhatsApp are stubs. A user with no email'
\echo 'address cannot log in at all.'
\echo ''
\echo 'Session 62 made requestOtp refuse these at request time instead of issuing an'
\echo 'undeliverable code and reporting success. If rows appear here AND section 1 shows a'
\echo 'NULL addressed_to, you are running a bmp-auth built before that fix.'
\echo ''

SELECT id, phone, default_role, created_at
FROM user_schema.users
WHERE email IS NULL
  AND deactivated_at IS NULL
ORDER BY created_at DESC
LIMIT 10;

\echo ''
\echo '=== READ IT LIKE THIS ======================================================'
\echo '  1 empty            -> the request never got to bmp-auth'
\echo '  1 ok, 2 empty      -> bmp-auth bug (it should always publish)'
\echo '  2 unprocessed      -> the relay is stuck or not running'
\echo '  2 ok, 3 empty      -> bmp-notification is down, or Kafka is'
\echo '  3 says sent        -> log-only mode; the code is in the notification console'
\echo '  3 says failed      -> read error_reason'
\echo '  1 shows NULL email -> that account has no address; see section 4'
\echo ''
