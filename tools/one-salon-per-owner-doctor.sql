-- ════════════════════════════════════════════════════════════════════════════════════════════════
--  ONE PHONE = ONE SALON — will V027 apply cleanly? Session 65.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
--  RUN THIS BEFORE RESTARTING bmp-salon.
--
--  V027 adds a unique index guaranteeing that one person holds at most one OWNER seat. If your
--  database already contains an owner with two salons, that migration FAILS — deliberately and
--  loudly, because the alternative is a migration quietly deciding which of somebody's two
--  businesses to disown.
--
--  A failed Flyway migration stops the service from starting. Better to find out here, with the
--  service still running, than at boot.
--
--  USAGE
--      docker cp tools\one-salon-per-owner-doctor.sql bmp-postgres-1:/tmp/salon-doctor.sql
--      docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/salon-doctor.sql
--
--  Read-only. Changes nothing.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

\echo ''
\echo '=== 1. THE ANSWER: can V027 apply? ==========================================='
\echo 'EMPTY means yes — restart whenever you like.'
\echo 'Rows here mean the migration will FAIL until you resolve each one by hand.'
\echo ''

SELECT
    ss.user_id,
    count(*)                                        AS owner_seats,
    string_agg(s.name || ' (' || COALESCE(s.reference, '?') || ', ' || s.status || ')', E'\n')
                                                    AS salons_owned
FROM salon_schema.salon_staff ss
LEFT JOIN salon_schema.salon s ON s.id = ss.salon_id
WHERE lower(ss.role) = 'owner'
GROUP BY ss.user_id
HAVING count(*) > 1
ORDER BY 2 DESC;

\echo ''
\echo '=== 2. WHO those owners are, if section 1 had rows ==========================='
\echo 'Joined to users so you can recognise the person rather than a UUID.'
\echo ''

SELECT u.phone, COALESCE(u.name, '(no name)') AS name, COALESCE(u.email, '(no email)') AS email,
       count(*) AS owner_seats
FROM salon_schema.salon_staff ss
JOIN user_schema.users u ON u.id = ss.user_id
WHERE lower(ss.role) = 'owner'
GROUP BY u.phone, u.name, u.email
HAVING count(*) > 1
ORDER BY 4 DESC;

\echo ''
\echo '=== 3. Every salon and who owns it — the ground truth ========================'
\echo 'One row per salon. A salon with NO owner row is its own problem: it usually means a'
\echo 'salon_staff row was deleted directly (tools/reset-test-account.sql does that) while the'
\echo 'salon itself survived. Such a salon is invisible to its owner and cannot be managed.'
\echo ''

SELECT
    COALESCE(s.reference, '(none)') AS ref,
    s.name,
    s.status,
    COALESCE(u.phone, '— NO OWNER SEAT —') AS owner_phone,
    COALESCE(u.name, '') AS owner_name,
    s.created_at::date
FROM salon_schema.salon s
LEFT JOIN salon_schema.salon_staff ss ON ss.salon_id = s.id AND lower(ss.role) = 'owner'
LEFT JOIN user_schema.users u ON u.id = ss.user_id
ORDER BY s.created_at DESC
LIMIT 30;

\echo ''
\echo '=== 4. Orphaned salons — no owner seat at all ================================'
\echo 'These do NOT block the migration, but nobody can manage them. Either restore the seat'
\echo '(INSERT into salon_staff with role OWNER) or delete the salon.'
\echo ''

SELECT COALESCE(s.reference, '(none)') AS ref, s.name, s.status, s.created_at::date
FROM salon_schema.salon s
WHERE NOT EXISTS (
    SELECT 1 FROM salon_schema.salon_staff ss
     WHERE ss.salon_id = s.id AND lower(ss.role) = 'owner'
)
ORDER BY s.created_at DESC;

\echo ''
\echo '=== HOW TO FIX A DUPLICATE, IF SECTION 1 HAD ROWS ============================'
\echo ''
\echo '  Decide which salon that person keeps, then drop the OTHER owner seat:'
\echo ''
\echo '    DELETE FROM salon_schema.salon_staff'
\echo '     WHERE user_id = ''<the user id>'' AND salon_id = ''<the salon they do NOT keep>'''
\echo '       AND lower(role) = ''owner'';'
\echo ''
\echo '  That leaves the salon itself intact but unowned — it will appear in section 4. Decide'
\echo '  separately whether to delete it or hand it to a different owner. The migration does NOT'
\echo '  make that choice for you, on purpose: it is somebody business.'
\echo ''
