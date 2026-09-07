package com.bmp.rewards.controllers;

import com.bmp.rewards.entities.ReferralProgram;
import com.bmp.rewards.services.ReferralProgramService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The referral offer, set from the admin console. Session 64.
 *
 * <h2>Internal, like the coupon admin endpoints beside it</h2>
 * Called by bmp-admin as ROLE_SERVICE, never by a browser. bmp-admin is where the staff token is
 * verified, the role is checked and the action is written to the audit log — routing this through
 * it keeps "which staff member changed what the platform pays" in one place rather than two.
 *
 * <h2>Changing the offer never touches a referral that already exists</h2>
 * Worth restating at the API boundary, because it is the property somebody will assume the opposite
 * of: amounts are frozen onto the referral row at attribution, so an admin lowering the rate — or
 * switching a side off — applies to referrals made AFTERWARDS only. See V006 and
 * ReferralProgramService.
 */
@Tag(name = "Referral programme (internal)")
@RestController
@RequestMapping("/api/v1/internal/referral-program")
public class ReferralProgramInternalController {

    private final ReferralProgramService programs;

    public ReferralProgramInternalController(ReferralProgramService programs) {
        this.programs = programs;
    }

    /**
     * @param effectivelyOff true when neither side pays anything. Sent rather than left for the
     *                       client to derive, so the console cannot compute it differently from the
     *                       payout does.
     */
    public record ProgramResponse(
            UUID id,
            long referrerRewardPaise, long refereeRewardPaise,
            boolean referrerEnabled, boolean refereeEnabled,
            long referrerPayoutPaise, long refereePayoutPaise,
            boolean effectivelyOff,
            Instant effectiveFrom, String changedByEmail, String note, Instant createdAt) {}

    /**
     * @param effectiveFrom null means now. A future value SCHEDULES the change — the version is
     *                      saved but does not apply until then.
     * @param note          required, at least 5 characters, enforced in the service. An unexplained
     *                      change to what the platform pays is the one somebody has to reconstruct
     *                      from bank statements six months later.
     */
    public record PublishRequest(
            long referrerRewardPaise, long refereeRewardPaise,
            boolean referrerEnabled, boolean refereeEnabled,
            Instant effectiveFrom,
            UUID staffId, String staffEmail,
            @NotBlank String note) {}

    @Operation(summary = "The offer in force right now")
    @GetMapping("/current")
    @PreAuthorize("hasRole('SERVICE')")
    public ProgramResponse current() {
        return toResponse(programs.current());
    }

    @Operation(summary = "Every version, newest first",
               description = "Append-only. This is the record of what was promised and when, which a single mutable settings row could not provide.")
    @GetMapping("/history")
    @PreAuthorize("hasRole('SERVICE')")
    public List<ProgramResponse> history() {
        return programs.history().stream().map(this::toResponse).toList();
    }

    @Operation(summary = "Publish a new version",
               description = "Applies to referrals made AFTER it. Referrals already recorded keep the amounts frozen onto them.")
    @PostMapping
    @PreAuthorize("hasRole('SERVICE')")
    public ProgramResponse publish(@Valid @RequestBody PublishRequest req) {
        return toResponse(programs.publish(
                req.referrerRewardPaise(), req.refereeRewardPaise(),
                req.referrerEnabled(), req.refereeEnabled(),
                req.effectiveFrom(), req.staffId(), req.staffEmail(), req.note()));
    }

    private ProgramResponse toResponse(ReferralProgram p) {
        return new ProgramResponse(
                p.getId(),
                p.getReferrerRewardPaise(), p.getRefereeRewardPaise(),
                p.isReferrerEnabled(), p.isRefereeEnabled(),
                p.referrerPayoutPaise(), p.refereePayoutPaise(),
                p.isEffectivelyOff(),
                p.getEffectiveFrom(), p.getChangedByEmail(), p.getNote(), p.getCreatedAt());
    }
}
