package com.bmp.salon.services;

import com.bmp.salon.dto.StaffDtos.*;
import com.bmp.salon.entities.SalonStaff;
import com.bmp.salon.entities.StaffInvites;
import com.bmp.salon.repositories.SalonStaffRepository;
import com.bmp.salon.repositories.StaffInvitesRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Session 6: salon_schema.salon_staff / staff_invites. Owner seats are created directly by
 * {@link SalonService#create} (the creating user becomes OWNER, no invite needed); manager
 * seats go through an invite token an owner generates and shares out-of-band (SMS/WhatsApp
 * — actual send wired once bmp-notification's channel is chosen, see bmp-auth's OtpRequested
 * for the pattern this will reuse).
 */
@Service
public class StaffService {

    private final SalonStaffRepository staff;
    private final StaffInvitesRepository invites;
    private final SecureRandom random = new SecureRandom();

    private static final int INVITE_TTL_HOURS = 48;

    public StaffService(SalonStaffRepository staff, StaffInvitesRepository invites) {
        this.staff = staff;
        this.invites = invites;
    }

    @Transactional
    public InviteResponse createInvite(UUID salonId, CreateInviteRequest req) {
        String token = randomToken();
        Instant expiresAt = Instant.now().plus(INVITE_TTL_HOURS, ChronoUnit.HOURS);
        StaffInvites entry = new StaffInvites(salonId, req.phone(), token, "pending", expiresAt);
        entry = invites.save(entry);
        return toInviteResponse(entry);
    }

    @Transactional
    public ConsumeInviteResponse consumeInvite(ConsumeInviteRequest req) {
        StaffInvites invite = invites.findByTokenAndStatus(req.token(), "pending")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVITE_NOT_FOUND_OR_ALREADY_USED"));

        if (invite.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "INVITE_EXPIRED");
        }
        if (!invite.getPhone().equals(req.phone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVITE_PHONE_MISMATCH");
        }

        UUID userId = UUID.fromString(req.userId());
        if (staff.existsBySalonIdAndUserId(invite.getSalonId(), userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_STAFF_AT_SALON");
        }

        staff.save(new SalonStaff(invite.getSalonId(), userId, "MANAGER"));
        invite.setStatus("accepted");

        return new ConsumeInviteResponse(invite.getSalonId());
    }

    @Transactional
    public void addOwner(UUID salonId, UUID ownerUserId) {
        staff.save(new SalonStaff(salonId, ownerUserId, "OWNER"));
    }

    public Optional<StaffLookupResponse> lookupByUserId(UUID userId) {
        return staff.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .map(s -> new StaffLookupResponse(s.getSalonId(), s.getRole()));
    }

    public List<SalonStaff> listForSalon(UUID salonId) {
        return staff.findBySalonId(salonId);
    }

    private String randomToken() {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private InviteResponse toInviteResponse(StaffInvites e) {
        return new InviteResponse(e.getId(), e.getSalonId(), e.getPhone(), e.getToken(), e.getStatus(), e.getExpiresAt());
    }
}
