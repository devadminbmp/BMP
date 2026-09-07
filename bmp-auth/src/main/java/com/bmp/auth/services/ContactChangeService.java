package com.bmp.auth.services;

import com.bmp.auth.client.UserServiceClient;
import com.bmp.auth.dto.UserDto;
import com.bmp.auth.entities.ContactChangeRequest;
import com.bmp.auth.repositories.ContactChangeRequestRepository;
import com.bmp.common.events.ContactChangeCodeRequested;
import com.bmp.common.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Change your own phone or email, confirmed by a code. Session 65.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS EXISTS ALONGSIDE THE SUPPORT TOOL
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * Support can already change somebody's contact details. That path requires a person to call in,
 * convince an agent who they are, and wait — and every one of those calls is an opportunity for
 * social engineering, because the agent is guessing. Somebody who is already signed in has
 * ALREADY proved who they are, far better than any phone conversation can. Making them queue for
 * an agent is worse security and worse service at the same time.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THE CODE ACTUALLY PROVES — AND THE HOLE THAT IS STILL THERE
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * <pre>
 *   EMAIL change → code sent to the NEW address.
 *                  Only somebody who can read that inbox can finish. Real ownership proof.
 *
 *   PHONE change → code sent to the address ALREADY on the account.
 *                  Proves the requester is the account holder. Proves NOTHING about the number.
 * </pre>
 *
 * <p>The second is as strong as it can be today: SMS is undeliverable until DLT registration
 * completes (see LoggingSmsSender), so there is no way to send anything to an unverified number.
 *
 * <p><b>The consequence is real.</b> A typo in the new number produces a login identity whose
 * owner cannot be reached — the phone must match at sign-in, and the code goes to the email. It is
 * a self-inflicted lockout rather than a takeover; nobody else gains access. Two things soften it:
 * the session survives the change, so the mistake is correctable immediately by somebody who is
 * still signed in, and support can fix it afterwards with the account tools from this same
 * session. When SMS goes live, {@code destinationFor} moves to the new number and this becomes a
 * genuine ownership proof — that method is the only thing that needs to change.
 */
@Service
public class ContactChangeService {

    private static final Logger log = LoggerFactory.getLogger(ContactChangeService.class);

    /**
     * Short, like the login OTP. A code that confirms a change to the login identity should not
     * sit in an inbox for an hour waiting for whoever else can read that inbox.
     */
    private static final int TTL_MINUTES = 10;

    /** Same ceiling as the login OTP. Six digits is 1,000,000 guesses; five tries is not a search. */
    private static final int MAX_ATTEMPTS = 5;

    private final ContactChangeRequestRepository repo;
    private final UserServiceClient users;
    private final OutboxPublisher outbox;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public ContactChangeService(ContactChangeRequestRepository repo, UserServiceClient users,
                                 OutboxPublisher outbox) {
        this.repo = repo;
        this.users = users;
        this.outbox = outbox;
    }

    /** What the caller is told after requesting: where to look, and by when. */
    public record RequestResult(String sentToMasked, Instant expiresAt, boolean provesNewNumber) {}

    /**
     * Start a change: validate, issue a code, send it.
     *
     * <p>Nothing on the account is touched here. The user row changes only in {@link #verify}, so
     * an abandoned request leaves no trace beyond an unconsumed row.
     */
    @Transactional
    public RequestResult request(UUID userId, String rawNewPhone, String rawNewEmail) {
        UserDto user = requireUser(userId);

        String newPhone = (rawNewPhone == null || rawNewPhone.isBlank())
                ? null : AuthService.canonicalPhone(rawNewPhone.trim());
        String newEmail = (rawNewEmail == null || rawNewEmail.isBlank()) ? null : rawNewEmail.trim();

        if (newPhone == null && newEmail == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tell us what you want to change — a new phone number, a new email, or both.");
        }

        /*
         * "Changing" a value to what it already is is refused rather than accepted as a no-op.
         *
         * It costs an email and a wait, and ends with the person believing something happened.
         * Saying so immediately is both cheaper and truer.
         */
        if (newPhone != null && newPhone.equals(user.phone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That's already your number.");
        }
        if (newEmail != null && newEmail.equalsIgnoreCase(user.email())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That's already your email address.");
        }

        /*
         * Is the number already somebody's?
         *
         * Checked HERE as well as in bmp-user, for a reason that is about the person rather than
         * about integrity: without it they receive a code, enter it correctly, and are told at the
         * last step that the number is taken. The database constraint would still protect us — it
         * is the wasted round trip and the confusing failure point that this avoids.
         *
         * It does leak that a number has an account, to somebody who is signed in and typing one
         * number at a time. That is the same thing the signup form already tells anybody (Session
         * 65's "this number already has an account"), so it reveals nothing new.
         */
        if (newPhone != null) {
            var existing = users.getUserByPhone(newPhone);
            if (existing.getBody() != null && !existing.getBody().id().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "That number is already used by another BMP account.");
            }
        }

        /*
         * WHERE THE CODE GOES. The single most important line in this class.
         *
         * Email change → the NEW address, so completing it proves ownership.
         * Phone change → the address on file, because SMS cannot deliver. See the class javadoc.
         *
         * When BOTH are changing, the new email wins: it is the stronger proof, and the new
         * address is where the person will be reading BMP's mail from that point on anyway.
         */
        String destination = newEmail != null ? newEmail : user.email();
        if (destination == null || destination.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "There's no email address on your account, so we can't send a confirmation "
                    + "code. Contact support and we'll help.");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        Instant expiresAt = Instant.now().plus(TTL_MINUTES, ChronoUnit.MINUTES);

        ContactChangeRequest row = repo.save(new ContactChangeRequest(
                userId, newPhone, newEmail, destination, passwordEncoder.encode(code), expiresAt));

        outbox.publish(new ContactChangeCodeRequested(
                row.getId(), userId, destination, newPhone, newEmail, code, expiresAt));

        // The code is never logged. The row id is, so a "my code didn't work" report can be traced
        // to the exact row on both sides — the same trick that resolved the Session 48 resend bug.
        log.info("Contact change requested: user={} rowId={} phoneChanging={} emailChanging={}",
                userId, row.getId(), newPhone != null, newEmail != null);

        return new RequestResult(mask(destination), expiresAt, newEmail != null);
    }

    /**
     * Finish the change.
     *
     * <p>Reads the NEWEST request for this user, consumed or not, and then checks its state. The
     * tempting alternative — "the newest UNCONSUMED one" — quietly skips a just-used code and
     * matches an older one still inside its TTL, which is replay wearing a different name.
     */
    @Transactional
    public void verify(UUID userId, String code) {
        ContactChangeRequest row = repo.findTopByUserIdOrderByCreatedAtDesc(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "There's no change waiting to be confirmed. Start again."));

        if (row.isConsumed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That code has already been used. Start again if you need to make another change.");
        }
        if (row.isExpired()) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "That code has expired. Request a new one.");
        }
        if (row.getAttempts() >= MAX_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many wrong codes. Request a new one.");
        }

        if (!passwordEncoder.matches(code, row.getCodeHash())) {
            /*
             * The failed attempt is saved in its OWN right, before anything throws.
             *
             * If the counter only advanced on a successful path, or was rolled back with the
             * exception, five tries would become unlimited tries — which is the whole difference
             * between a six-digit code being a barrier and being a formality.
             */
            row.recordFailedAttempt();
            repo.saveAndFlush(row);
            log.warn("Wrong contact-change code: user={} rowId={} attempt={}",
                    userId, row.getId(), row.getAttempts());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That code isn't right. Check it and try again.");
        }

        /*
         * Consume BEFORE applying.
         *
         * If the change itself fails — bmp-user down, the number taken in the seconds since the
         * check — the code is spent and they request a fresh one. The other order leaves a valid
         * code alive after a partial failure, and a code that survives its own use is not
         * single-use in any meaningful sense.
         */
        row.consume();
        repo.saveAndFlush(row);

        users.changeContact(userId,
                new UserServiceClient.ChangeContactRequest(row.getNewPhone(), row.getNewEmail()));

        log.info("Contact change CONFIRMED: user={} rowId={} phoneChanged={} emailChanged={}",
                userId, row.getId(), row.getNewPhone() != null, row.getNewEmail() != null);
    }

    private UserDto requireUser(UUID userId) {
        var resp = users.getUserById(userId);
        if (resp.getBody() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user no longer exists");
        }
        return resp.getBody();
    }

    /**
     * {@code d•••@gmail.com}. Enough for the person to recognise their own address, not enough to
     * be worth harvesting — the same rule the login screen uses.
     */
    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at < 1) return "•••";
        return email.charAt(0) + "•••" + email.substring(at);
    }
}
