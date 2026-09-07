package com.bmp.admin.security;

import com.bmp.admin.entities.BmpStaff;
import com.bmp.admin.repositories.BmpStaffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

/**
 * Creates one signed-in-able staff account per role, for LOCAL DEVELOPMENT ONLY.
 *
 * <h2>Why this exists</h2>
 * The production onboarding path is deliberately manual and deliberately slow: a superadmin is
 * claimed once from environment variables, enrols 2FA, creates each employee, and hands over a
 * single-use activation code that the employee redeems and then enrols their own 2FA. That is
 * correct, and it should stay correct.
 *
 * <p>It is also five minutes of clicking <b>per role</b>, repeated after every
 * {@code docker compose down -v} — which is every time a migration changes. The predictable
 * result is that nobody ever tests as a support agent or a read-only analyst, and the
 * least-privilege rules that this console's whole design rests on go unverified until a real
 * employee hits them.
 *
 * <p>So: same flow, pre-executed, on a throwaway database.
 *
 * <h2>THE THREE GUARDS — read before changing any of them</h2>
 *
 * <b>1. Opt-in property, default OFF.</b> {@code bmp.admin.dev-staff.enabled} must be explicitly
 * true. Nothing happens otherwise, so merely deploying this class changes nothing.
 *
 * <b>2. The database must be on localhost.</b> This is the guard that actually matters, and it
 * is here rather than a {@code @Profile} annotation for a specific reason: <b>in this repo the
 * {@code dev} profile points at a SHARED Neon Postgres branch</b> (see
 * {@code application-dev.yml}), not at anything local. A seeder gated on {@code @Profile("dev")}
 * would have written known-password admin accounts into a database three founders share. Profile
 * names lie; a JDBC URL does not.
 *
 * <b>3. Existing accounts are never touched.</b> Only missing ones are created. A local database
 * where someone has been testing a lockout or a suspension keeps that state.
 *
 * <h2>Credentials</h2>
 * One password, shared by all five accounts, from {@code BMP_ADMIN_DEV_STAFF_PASSWORD}. If unset,
 * a random one is generated and printed <b>once</b> at startup. Deliberately not a constant like
 * "devpassword": a hardcoded default in source is the thing that eventually gets typed into a
 * real environment, and this file has already been the site of that mistake once (see
 * {@code StaffBootstrap}'s javadoc and the V003 history).
 *
 * <h2>2FA</h2>
 * All five share ONE randomly-generated TOTP secret, printed once as an {@code otpauth://} URI.
 * One entry in your authenticator app therefore covers every role — the point is to test
 * <i>permissions</i>, and five separate QR codes would just push people back to using the
 * superadmin for everything, which is exactly the behaviour this is trying to prevent.
 *
 * <p>The secret is generated per run, not fixed in source, so nothing reusable leaks into git.
 *
 * @see StaffBootstrap the production path, which this does not replace
 */
@Component
public class DevStaffSeeder implements ApplicationRunner {

    // No @Order: this and StaffBootstrap touch disjoint accounts — bootstrap claims the
    // V003-seeded devadmin.bmp@gmail.com, this creates dev.*@bemyprofessional.in — so the
    // order they run in doesn't matter. (Worth stating: an unordered ApplicationRunner gets
    // LOWEST_PRECEDENCE, so "add @Order to run after the unordered one" doesn't work anyway.)

    private static final Logger log = LoggerFactory.getLogger(DevStaffSeeder.class);

    /** Password length when we generate one. Long because it's printed, not typed from memory. */
    private static final int GENERATED_PASSWORD_LENGTH = 20;

    /** Matches StaffBootstrap's floor — the console reads customer PII either way. */
    private static final int MIN_PASSWORD_LENGTH = 16;

    /**
     * The five accounts. Phones sit in +9100000000xx to stay clear of the V003 superadmin
     * (+910000000001) and of anything a person might plausibly own.
     */
    private static final List<DevStaff> ACCOUNTS = List.of(
            new DevStaff("Dev Superadmin", "dev.super@bemyprofessional.in",   "+910000000011", StaffPermission.SUPER_ADMIN),
            // Session 65 — the ADMIN rung, and the SUPPORT MANAGER rung. Both were missing here, so
            // neither could be exercised locally without hand-writing SQL. Every rung of the
            // hierarchy Darshan specified now has a dev login, which is the only way to test that
            // "ops cannot manage ops" and "an admin can" are actually true rather than assumed.
            new DevStaff("Dev Admin",      "dev.admin@bemyprofessional.in",   "+910000000016", RoleHierarchy.ADMIN),
            new DevStaff("Dev Ops",        "dev.ops@bemyprofessional.in",     "+910000000012", StaffPermission.OPS_ADMIN),
            new DevStaff("Dev Support Lead","dev.lead@bemyprofessional.in",   "+910000000017", StaffPermission.SUPPORT_LEAD),
            new DevStaff("Dev Support",    "dev.support@bemyprofessional.in", "+910000000013", StaffPermission.SUPPORT_AGENT),
            new DevStaff("Dev Finance",    "dev.finance@bemyprofessional.in", "+910000000014", StaffPermission.FINANCE_ADMIN),
            new DevStaff("Dev Read Only",  "dev.readonly@bemyprofessional.in","+910000000015", StaffPermission.READ_ONLY)
    );

    private record DevStaff(String name, String email, String phone, String role) {}

    private final BmpStaffRepository staffRepo;
    private final TotpService totp;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    @Value("${bmp.admin.dev-staff.enabled:false}")
    private boolean enabled;

    @Value("${bmp.admin.dev-staff.password:}")
    private String configuredPassword;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    public DevStaffSeeder(BmpStaffRepository staffRepo, TotpService totp) {
        this.staffRepo = staffRepo;
        this.totp = totp;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return; // the normal case — say nothing
        }

        if (!isLocalDatabase()) {
            // Loud, and refuses. Someone has set the flag against a shared or cloud database,
            // which is how known-password admin accounts end up somewhere they can be reached.
            log.error("""
                    ============================================================================
                    REFUSING to seed dev staff accounts: the database is not on localhost.
                      spring.datasource.url = {}
                    This guard exists because the `dev` profile in this repo points at a SHARED
                    Neon branch. Seeding known-password admin accounts there would hand the
                    console to anyone who can read this source file.
                    Unset bmp.admin.dev-staff.enabled, or point at local Docker Postgres.
                    ============================================================================""",
                    datasourceUrl);
            return;
        }

        String password = resolvePassword();
        if (password == null) return; // resolvePassword logged why

        // One secret for all five — see the class javadoc.
        String sharedSecret = totp.generateSecret();
        Instant now = Instant.now();

        int created = 0;
        for (DevStaff account : ACCOUNTS) {
            if (staffRepo.existsByEmailIgnoreCase(account.email())
                    || staffRepo.existsByPhone(account.phone())) {
                // Guard 3: never touch an existing account. A local database mid-test keeps
                // whatever lockout or suspension state someone put it in.
                continue;
            }

            BmpStaff staff = new BmpStaff(
                    account.name(), account.phone(), account.email(),
                    encoder.encode(password), account.role(), "active", null);

            // Pre-enrolled, so login is password + code with no enrolment detour.
            staff.setTotpSecret(sharedSecret);
            staff.setTotpEnrolledAt(now);

            staffRepo.save(staff);
            created++;
        }

        if (created == 0) {
            log.info("Dev staff accounts already present — nothing created. Password unchanged; "
                    + "if you've lost it, wipe the rows or reset the database.");
            return;
        }

        // Printed once, at startup, on a local-only database. This is the only place the
        // credential appears; it is never written to a file or a migration.
        log.warn("""

                ============================================================================
                DEV STAFF ACCOUNTS SEEDED ({} created) — LOCAL DATABASE ONLY
                ============================================================================
                Password for ALL of the accounts below:
                    {}

{}

                Two-factor: ONE entry covers every account above.

                  Base32 secret (paste into tools\\totp.mjs if you have no authenticator app):
                    {}

                  Or scan / paste this into an authenticator:
                    {}

                  No app?  node tools\\totp.mjs <the-secret-above>

                Console: http://localhost:5180  ·  admin door /admin/login  ·  support /support/login
                ============================================================================""",
                created, password,
                /*
                 * Built from ACCOUNTS rather than five positional indexes.
                 *
                 * The previous form named entries 0..4 by hand, so adding a sixth role printed a
                 * login block that silently omitted it — the account would exist, work, and be
                 * invisible in the one place a developer looks for it. Session 61 added
                 * support_lead and hit exactly that.
                 */
                ACCOUNTS.stream()
                        .map(a -> "  %-36s %s".formatted(a.email(), a.role()))
                        .collect(java.util.stream.Collectors.joining("\n")),
                /*
                 * The raw secret is printed as well as the otpauth:// URI. The URI is what an
                 * authenticator wants; the bare Base32 is what `tools/totp.mjs` wants, and digging
                 * it out of a query string by eye is exactly the sort of step people get wrong at
                 * the point they are already blocked on logging in.
                 */
                sharedSecret,
                totp.provisioningUri(sharedSecret, "dev@bemyprofessional.in"));
    }

    /**
     * The configured password, or a generated one.
     *
     * <p>Returns null (having logged) if a password was supplied but is too short — refusing is
     * better than quietly seeding five admin accounts behind something weak, even locally, since
     * "locally" has a habit of becoming "on a laptop on hotel wifi".
     */
    private String resolvePassword() {
        if (configuredPassword != null && !configuredPassword.isBlank()) {
            if (configuredPassword.length() < MIN_PASSWORD_LENGTH) {
                log.error("BMP_ADMIN_DEV_STAFF_PASSWORD is shorter than {} characters. "
                        + "No accounts were created.", MIN_PASSWORD_LENGTH);
                return null;
            }
            return configuredPassword;
        }
        return generatePassword();
    }

    /** Ambiguity-free alphabet: no O/0, no I/l/1 — this gets read off a console and retyped. */
    private String generatePassword() {
        final String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder out = new StringBuilder(GENERATED_PASSWORD_LENGTH);
        for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    /**
     * Is the configured datasource pointing at this machine?
     *
     * <p>Conservative by construction: anything unrecognised, malformed or empty counts as NOT
     * local. The failure mode of being too strict is "the seeder didn't run"; the failure mode
     * of being too lenient is admin accounts with a printed password on a shared database.
     */
    private boolean isLocalDatabase() {
        if (datasourceUrl == null || datasourceUrl.isBlank()) return false;
        String url = datasourceUrl.toLowerCase();
        // Match the host segment specifically. A bare `contains("localhost")` would also accept
        // jdbc:postgresql://prod.example.com/db?opt=localhost — unlikely, but this check is the
        // only thing standing between a shared database and known credentials.
        return url.startsWith("jdbc:postgresql://localhost:")
                || url.startsWith("jdbc:postgresql://localhost/")
                || url.startsWith("jdbc:postgresql://127.0.0.1:")
                || url.startsWith("jdbc:postgresql://127.0.0.1/");
    }
}
