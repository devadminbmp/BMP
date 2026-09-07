package com.bmp.rewards.services;

import com.bmp.rewards.entities.ReferralProgram;
import com.bmp.rewards.repositories.ReferralProgramRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reading and changing the referral offer. Session 64.
 *
 * <h2>Why this is admin-editable at all</h2>
 * The amounts lived in Spring properties, so changing what the platform pays for a referral needed
 * an env var, a redeploy and a developer. A commercial lever only engineering can pull is a lever
 * nobody pulls — the programme either runs at whatever number was guessed on day one, or it gets
 * switched off by deleting code.
 *
 * <h2>A change never touches a referral that already exists</h2>
 * This is the rule the whole design turns on. Somebody told their friend about us under a ₹150
 * offer; when that friend books three weeks later they are owed ₹150, whatever the rate is by then
 * and even if the programme has been switched off entirely.
 *
 * <p>That works because the amounts are FROZEN onto the referral row at attribution (V002). This
 * service only decides what gets frozen next. Nothing here can reach a referral that has already
 * been recorded, which means "admin lowered the rate" can never become "customer was promised
 * something we did not pay".
 */
@Service
public class ReferralProgramService {

    private static final Logger log = LoggerFactory.getLogger(ReferralProgramService.class);

    /** Mirrors the CHECK in V006. Duplicated on purpose — see {@link #validate}. */
    private static final long MAX_REWARD_PAISE = 1_000_000L;

    private final ReferralProgramRepository programs;
    private final long fallbackReferrerPaise;
    private final long fallbackRefereePaise;

    public ReferralProgramService(
            ReferralProgramRepository programs,
            @Value("${bmp.rewards.referrer-reward-paise:15000}") long fallbackReferrerPaise,
            @Value("${bmp.rewards.referee-reward-paise:10000}") long fallbackRefereePaise) {
        this.programs = programs;
        this.fallbackReferrerPaise = fallbackReferrerPaise;
        this.fallbackRefereePaise = fallbackRefereePaise;
    }

    /**
     * The offer in force right now.
     *
     * <p>V006 seeds a first version, so the fallback below should never be reached in a migrated
     * database. It exists because the alternative — throwing — would mean an empty table takes
     * down SIGNUP, and a referral code is not worth failing a registration over. Falling back to
     * the old property values keeps behaviour identical to before this table existed.
     */
    public ReferralProgram current() {
        return programs.currentAt(Instant.now()).orElseGet(() -> {
            log.warn("No referral_program version is in force — falling back to the configured "
                    + "defaults. V006 should have seeded one; check it ran.");
            return new ReferralProgram(fallbackReferrerPaise, fallbackRefereePaise, true, true,
                    Instant.now(), null, "system", "Fallback — no version found in the database.");
        });
    }

    /** Every version, newest first. The console's history table. */
    public List<ReferralProgram> history() {
        return programs.findAllByOrderByEffectiveFromDesc();
    }

    /**
     * Publish a new version. The previous one is untouched and stays in the history.
     *
     * @param note REQUIRED by this method though nullable in the schema. An unexplained change to
     *             what the platform pays is exactly the one somebody has to reconstruct from bank
     *             statements six months later; the database allows null because a migration seeded
     *             a row, the application does not because a human is making a commercial decision.
     */
    @Transactional
    public ReferralProgram publish(long referrerRewardPaise, long refereeRewardPaise,
                                    boolean referrerEnabled, boolean refereeEnabled,
                                    Instant effectiveFrom, UUID staffId, String staffEmail,
                                    String note) {
        validate(referrerRewardPaise, refereeRewardPaise, note);

        Instant from = effectiveFrom == null ? Instant.now() : effectiveFrom;
        ReferralProgram previous = current();

        ReferralProgram next = programs.save(new ReferralProgram(
                referrerRewardPaise, refereeRewardPaise, referrerEnabled, refereeEnabled,
                from, staffId, staffEmail, note.trim()));

        /*
         * Logged at INFO with both the old and new values, because this is a money decision and the
         * question asked afterwards is always "when did it change, and from what".
         *
         * The audit trail proper is the table itself — this line is for whoever is reading logs
         * during an incident and has not thought to query it.
         */
        log.info("Referral programme changed by {}: referrer {}→{} paise ({}), referee {}→{} paise "
                        + "({}), effective {}. Note: {}",
                staffEmail,
                previous.getReferrerRewardPaise(), referrerRewardPaise,
                referrerEnabled ? "on" : "OFF",
                previous.getRefereeRewardPaise(), refereeRewardPaise,
                refereeEnabled ? "on" : "OFF",
                from, note.trim());

        if (next.isEffectivelyOff()) {
            log.warn("The referral programme is now effectively OFF — new referrals will be recorded "
                    + "but will pay nothing to either side. Referrals made BEFORE now keep their "
                    + "frozen amounts and will still pay out.");
        }
        return next;
    }

    /**
     * Guard rails, duplicated from the V006 CHECK constraints.
     *
     * <p>Deliberate duplication. The constraint is the guarantee — it holds against any writer,
     * including a migration or somebody at a psql prompt. This is the MESSAGE: a constraint
     * violation surfaces as a 500 and a stack trace, and an admin who typed one zero too many
     * deserves a sentence telling them so.
     */
    private void validate(long referrerRewardPaise, long refereeRewardPaise, String note) {
        if (note == null || note.trim().length() < 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say why you're changing this — whoever reconciles the payouts later will need it.");
        }
        if (referrerRewardPaise < 0 || refereeRewardPaise < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A reward cannot be negative. To stop paying a side, switch it off instead.");
        }
        if (referrerRewardPaise > MAX_REWARD_PAISE || refereeRewardPaise > MAX_REWARD_PAISE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is over ₹10,000 per referral. Amounts are in PAISE — ₹150 is 15000. "
                    + "If you really mean it, raise the ceiling in code with a second pair of eyes.");
        }
    }
}
