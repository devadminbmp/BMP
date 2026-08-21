package com.bmp.rewards.services;

import com.bmp.common.money.Money;
import com.bmp.rewards.entities.Referral;
import com.bmp.rewards.entities.ReferralCode;
import com.bmp.rewards.repositories.ReferralCodeRepository;
import com.bmp.rewards.repositories.ReferralRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Referrals — one customer inviting another.
 *
 * <p>Two jobs: give every customer a shareable code, and record who joined through whose.
 *
 * <h2>The rewards are frozen at referral time, deliberately</h2>
 * {@code referrer_reward_paise} and {@code referee_reward_paise} are copied onto the row when
 * the referral is created, not read from config when it pays out. If the programme changes from
 * ₹150 to ₹50 next month, everyone who already referred someone still gets what they were
 * promised. Reading current config at payout would quietly renege on that, and nobody would
 * notice until a customer did.
 *
 * <h2>Fraud is recorded, not blocked silently</h2>
 * Self-referral and already-referred are caught here and written to {@code fraud_reason} rather
 * than throwing. The referral still exists as a record — you want to be able to see that
 * someone tried, and a hard refusal teaches them exactly which check to work around next time.
 * The reward simply never completes.
 *
 * <h2>What isn't built</h2>
 * Payout. {@code completed_at} is meant to be set on the referee's FIRST COMPLETED VISIT, which
 * needs a booking-completed event this service doesn't consume yet, and a wallet credit that
 * needs payments. So referrals accumulate correctly and pay nothing — flagged in
 * {@link #completeOnFirstVisit}, not hidden.
 */
@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    /** Alphabet with no 0/O or 1/I/l — referral codes get read aloud and typed by hand. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int VALIDITY_DAYS = 90;

    private final ReferralCodeRepository codes;
    private final ReferralRepository referrals;
    private final SecureRandom random = new SecureRandom();
    private final long referrerRewardPaise;
    private final long refereeRewardPaise;

    public ReferralService(ReferralCodeRepository codes, ReferralRepository referrals,
                           @Value("${bmp.rewards.referrer-reward-paise:15000}") long referrerRewardPaise,
                           @Value("${bmp.rewards.referee-reward-paise:10000}") long refereeRewardPaise) {
        this.codes = codes;
        this.referrals = referrals;
        this.referrerRewardPaise = referrerRewardPaise;
        this.refereeRewardPaise = refereeRewardPaise;
    }

    /**
     * This customer's code, created on first request.
     *
     * <p>Lazy rather than at signup: most customers never share one, and generating millions of
     * unused codes to guarantee uniqueness across a table nobody reads is work for its own sake.
     */
    @Transactional
    public String codeFor(UUID userId) {
        return codes.findByUserId(userId)
                .map(ReferralCode::getCode)
                .orElseGet(() -> {
                    String code = generateUniqueCode();
                    codes.save(new ReferralCode(userId, code));
                    log.info("Referral code issued for user {}", userId);
                    return code;
                });
    }

    /**
     * Record that {@code refereeUserId} joined using {@code code}.
     *
     * <p>Called at signup. Fraud checks write a reason rather than refusing — see the class
     * comment for why.
     */
    @Transactional
    public Referral attribute(String code, UUID refereeUserId) {
        ReferralCode referralCode = codes.findByCode(code.trim().toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "We don't recognise that referral code."));

        UUID referrerUserId = referralCode.getUserId();
        String fraudReason = null;

        if (referrerUserId.equals(refereeUserId)) {
            fraudReason = "self_referral";
        } else if (referrals.existsByRefereeUserId(refereeUserId)) {
            // A person can only ever be referred once — otherwise the same account could be
            // "introduced" repeatedly for repeated rewards.
            fraudReason = "already_referred";
        }

        Referral referral = new Referral(
                referrerUserId, refereeUserId,
                // Frozen here. See the class comment.
                Money.ofPaise(referrerRewardPaise), Money.ofPaise(refereeRewardPaise),
                Instant.now(), Instant.now().plus(VALIDITY_DAYS, ChronoUnit.DAYS),
                fraudReason, null);

        referral = referrals.save(referral);

        if (fraudReason != null) {
            log.warn("Referral flagged: referrer={} referee={} reason={}",
                    referrerUserId, refereeUserId, fraudReason);
        } else {
            log.info("Referral recorded: referrer={} referee={}", referrerUserId, refereeUserId);
        }
        return referral;
    }

    /**
     * Mark a referral complete when the referee actually shows up.
     *
     * <p>⚠️ NOTHING CALLS THIS YET. It should fire on the referee's first COMPLETED booking —
     * not their first booking, because a booking that's made and cancelled is exactly how you'd
     * farm referral rewards. That needs bmp-rewards to consume a {@code booking.completed}
     * event, which it doesn't.
     *
     * <p>And even once it does, there is nowhere to pay the reward: the wallet exists in the
     * schema but crediting it means payments, which are Phase 3.
     *
     * <p>So referrals accumulate correctly and pay nothing. That's a deliberate half — the
     * attribution is the part that's hard to reconstruct later, so it's worth recording now.
     * <b>Do not advertise a referral programme until this is wired.</b>
     */
    @Transactional
    public void completeOnFirstVisit(UUID refereeUserId) {
        referrals.findByRefereeUserId(refereeUserId).ifPresent(r -> {
            if (r.getFraudReason() != null) {
                log.info("Referral for referee {} not completed — flagged {}", refereeUserId, r.getFraudReason());
                return;
            }
            if (r.getExpiresAt().isBefore(Instant.now())) {
                log.info("Referral for referee {} expired before their first visit", refereeUserId);
                return;
            }
            // TODO(payout): credit both wallets. Needs bmp-payment.
            log.info("Referral for referee {} would complete here — payout not implemented", refereeUserId);
        });
    }

    private String generateUniqueCode() {
        // Six characters from a 31-character alphabet is ~887 million combinations. Retrying on
        // collision is simpler and safer than assuming it can't happen.
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder("BMP");
            for (int i = 0; i < 6; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String candidate = sb.toString();
            if (codes.findByCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique referral code after 10 attempts");
    }
}
