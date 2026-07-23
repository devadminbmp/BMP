package com.bmp.salon.services;

import com.bmp.common.money.Money;
import com.bmp.salon.dto.StylistDtos.*;
import com.bmp.salon.entities.Stylist;
import com.bmp.salon.entities.StylistSalon;
import com.bmp.salon.repositories.StylistRepository;
import com.bmp.salon.repositories.StylistSalonRepository;
import com.bmp.salon.repositories.StylistServiceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** BMP-24: stylist / stylist_salon / stylist_service — the portable-identity tables. */
@Service
public class StylistCrudService {

    private final StylistRepository stylists;
    private final StylistSalonRepository stylistSalons;
    private final StylistServiceRepository stylistServices;

    public StylistCrudService(StylistRepository stylists, StylistSalonRepository stylistSalons,
                               StylistServiceRepository stylistServices) {
        this.stylists = stylists;
        this.stylistSalons = stylistSalons;
        this.stylistServices = stylistServices;
    }

    @Transactional
    public StylistResponse create(CreateStylistRequest req) {
        Stylist s = new Stylist(req.userId(), req.name(), BigDecimal.ZERO, 0, false);
        s = stylists.save(s);
        return toResponse(s);
    }

    public StylistResponse getById(UUID id) {
        return stylists.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_NOT_FOUND"));
    }

    @Transactional
    public StylistSalonResponse link(UUID salonId, LinkStylistRequest req) {
        stylists.findById(req.stylistId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_NOT_FOUND"));
        StylistSalon link = new StylistSalon(req.stylistId(), salonId, "active", null, 0, true, Instant.now(), null);
        link = stylistSalons.save(link);
        return toStylistSalonResponse(link);
    }

    public List<StylistSalonResponse> listForSalon(UUID salonId, String status) {
        List<StylistSalon> found = status != null
                ? stylistSalons.findBySalonIdAndStatus(salonId, status)
                : stylistSalons.findBySalonId(salonId);
        return found.stream().map(this::toStylistSalonResponse).toList();
    }

    /** The cheapest, fastest check in the availability algorithm (BMP-13/14) — keep this endpoint fast and correct. */
    @Transactional
    public AvailableTodayResponse setAvailableToday(UUID salonId, UUID stylistId, AvailableTodayRequest req) {
        StylistSalon link = stylistSalons.findBySalonIdAndStylistId(salonId, stylistId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_SALON_LINK_NOT_FOUND"));
        link.setIsAvailableToday(req.isAvailableToday());
        return new AvailableTodayResponse(stylistId, salonId, link.isAvailableToday());
    }

    /** NEVER a DELETE — status moves to 'alumni' and salon_rating/salon_review_count freeze here, permanently. */
    @Transactional
    public StylistSalonResponse markAlumni(UUID salonId, UUID stylistId) {
        StylistSalon link = stylistSalons.findBySalonIdAndStylistId(salonId, stylistId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_SALON_LINK_NOT_FOUND"));
        Stylist stylist = stylists.findById(stylistId).orElseThrow();
        link.setStatus("alumni");
        link.setLeftAt(Instant.now());
        link.setSalonRating(stylist.getOverallRating()); // freeze snapshot at this moment
        return toStylistSalonResponse(link);
    }

    @Transactional
    public StylistServiceResponse addService(UUID salonId, UUID stylistId, StylistServiceRequest req) {
        com.bmp.salon.entities.StylistService entity = new com.bmp.salon.entities.StylistService(
                stylistId, salonId, req.serviceId(), req.actualDurationMinutes(),
                req.overridePricePaise() == null ? null : Money.ofPaise(req.overridePricePaise()));
        entity = stylistServices.save(entity);
        return toStylistServiceResponse(entity);
    }

    public List<StylistServiceResponse> listServices(UUID salonId, UUID stylistId) {
        return stylistServices.findByStylistIdAndSalonId(stylistId, salonId).stream()
                .map(this::toStylistServiceResponse).toList();
    }

    private StylistResponse toResponse(Stylist s) {
        return new StylistResponse(s.getId(), s.getName(), s.getUserId(), s.getOverallRating(),
                s.getTotalReviews(), s.isTopStylist(), s.getCreatedAt());
    }

    private StylistSalonResponse toStylistSalonResponse(StylistSalon l) {
        return new StylistSalonResponse(l.getId(), l.getStylistId(), l.getSalonId(), l.getStatus(),
                l.isAvailableToday(), l.getSalonRating(), l.getSalonReviewCount(), l.getJoinedAt(), l.getLeftAt());
    }

    private StylistServiceResponse toStylistServiceResponse(com.bmp.salon.entities.StylistService s) {
        return new StylistServiceResponse(s.getId(), s.getStylistId(), s.getServiceId(),
                s.getActualDurationMinutes(), s.getOverridePricePaise() == null ? null : s.getOverridePricePaise().paise());
    }
}
