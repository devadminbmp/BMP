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

import java.time.Instant;
import java.util.Optional;

/**
 * Claims the seeded superadmin account, once.
 *
 * <h2>The problem this solves</h2>
 * A console needs a first account, but every obvious way of creating one is a hole:
 * <ul>
 *   <li>A password in the migration lives in git forever and gets copied into staging.</li>
 *   <li>A default password ("admin/admin") is guessed within minutes of being reachable.</li>
 *   <li>A self-service "create the first admin" page hands the console to whoever finds it.</li>
 * </ul>
 *
 * <h2>How this works instead</h2>
 * V003 seeds the account with {@code password_hash = 'LOCKED-NO-PASSWORD-SET'} — deliberately
 * not a bcrypt hash, so verification can only fail. This runner sets a real password from the
 * environment, <b>only if the account still has no usable one</b>.
 *
 * <p>That single condition is what makes it safe:
 * <ul>
 *   <li>The variables are inert on every boot after the first, so a stale value in a deploy
 *       config can't silently reset a live account.</li>
 *   <li>Someone who gains access to environment variables still can't take over an account
 *       that has already been claimed — they'd have to write to the database, at which point
 *       they own everything anyway.</li>
 *   <li>Nothing is logged that reveals the password. The log says a bootstrap happened, which
 *       is exactly what you want to see in the audit trail.</li>
 * </ul>
 *
 * <p>Set these once, start the service, then remove them:
 * <pre>
 *   BMP_ADMIN_BOOTSTRAP_EMAIL=you@bemyprofessional.in
 *   BMP_ADMIN_BOOTSTRAP_PASSWORD=&lt;something long, from a password manager&gt;
 * </pre>
 */
@Component
public class StaffBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);

    /** The sentinel written by V003. Anything that isn't a bcrypt hash would do; this is explicit. */
    public static final String LOCKED = "LOCKED-NO-PASSWORD-SET";

    /** Long enough to be worth having. Staff use a password manager; there's no excuse for 8. */
    private static final int MIN_PASSWORD_LENGTH = 16;

    private final BmpStaffRepository staffRepo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${bmp.admin.bootstrap-email:}")
    private String bootstrapEmail;

    @Value("${bmp.admin.bootstrap-password:}")
    private String bootstrapPassword;

    public StaffBootstrap(BmpStaffRepository staffRepo) {
        this.staffRepo = staffRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrapEmail == null || bootstrapEmail.isBlank()
                || bootstrapPassword == null || bootstrapPassword.isBlank()) {
            warnIfNobodyCanLogIn();
            return;
        }

        if (bootstrapPassword.length() < MIN_PASSWORD_LENGTH) {
            // Refuse rather than quietly accept a weak one — this account can read every
            // customer's personal data.
            log.error("BMP_ADMIN_BOOTSTRAP_PASSWORD is shorter than {} characters. Refusing to "
                    + "set it. No account was changed.", MIN_PASSWORD_LENGTH);
            return;
        }

        Optional<BmpStaff> match = staffRepo.findAll().stream()
                .filter(s -> bootstrapEmail.equalsIgnoreCase(s.getEmail()))
                .findFirst();

        if (match.isEmpty()) {
            log.error("Bootstrap email '{}' does not match any staff account. Nothing was changed. "
                    + "Check the seeded account's email in V003.", bootstrapEmail);
            return;
        }

        BmpStaff staff = match.get();

        // THE CONDITION THAT MAKES THIS SAFE. An account that already has a password is never
        // touched, so these environment variables cannot be used to hijack a live account.
        if (!LOCKED.equals(staff.getPasswordHash())) {
            log.info("Staff account '{}' is already claimed — bootstrap variables ignored. "
                    + "Remove BMP_ADMIN_BOOTSTRAP_* from the environment.", bootstrapEmail);
            return;
        }

        staff.setPasswordHash(encoder.encode(bootstrapPassword));
        staff.touch();
        staffRepo.save(staff);

        // Deliberately says WHAT happened and nothing about the credential itself.
        log.warn("BOOTSTRAP: password set for staff account '{}'. Two-factor is NOT yet enrolled "
                + "— the first login will require it. Remove BMP_ADMIN_BOOTSTRAP_* from the "
                + "environment now.", bootstrapEmail);
    }

    /**
     * If every account is still locked, the console is unusable — and silence would leave
     * someone debugging a login screen that could never work.
     */
    private void warnIfNobodyCanLogIn() {
        boolean anyClaimed = staffRepo.findAll().stream()
                .anyMatch(s -> !LOCKED.equals(s.getPasswordHash()));
        if (!anyClaimed) {
            log.warn("No staff account has a usable password. The console cannot be signed into. "
                    + "Set BMP_ADMIN_BOOTSTRAP_EMAIL and BMP_ADMIN_BOOTSTRAP_PASSWORD once, "
                    + "restart, then remove them.");
        }
    }
}
