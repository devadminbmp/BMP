package com.bmp.salon.services;

import com.bmp.common.money.Money;
import com.bmp.salon.dto.SalonDtos.*;
import com.bmp.salon.entities.Salon;
import com.bmp.salon.entities.SalonHours;
import com.bmp.salon.entities.SalonPolicy;
import com.bmp.salon.repositories.SalonHoursRepository;
import com.bmp.salon.repositories.SalonPolicyRepository;
import com.bmp.salon.repositories.SalonRepository;
import com.bmp.salon.repositories.SalonServiceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * BMP-23: salon / salon_policy / salon_hours / salon_service CRUD.
 * NOTE on `location`: the entity stores it as a plain "lat,lng" String (no hibernate-spatial
 * dependency wired in this pass), so proximity search here is an in-memory Haversine
 * calculation over all salons, not a real PostGIS ST_DWithin query. TODO(later ticket):
 * add hibernate-spatial + a native ST_DWithin query once salon count makes this matter.
 */
@Service
public class SalonService {

    private final SalonRepository salons;
    private final SalonPolicyRepository policies;
    private final SalonHoursRepository hours;
    private final SalonServiceRepository services;
    private final StaffService staffService;

    public SalonService(SalonRepository salons, SalonPolicyRepository policies,
                         SalonHoursRepository hours, SalonServiceRepository services,
                         StaffService staffService) {
        this.salons = salons;
        this.policies = policies;
        this.hours = hours;
        this.services = services;
        this.staffService = staffService;
    }

    /**
     * Session 6: {@code ownerUserId} comes from the caller's JWT ({@code AuthenticatedUser}),
     * never from the request body — the creating user always becomes this salon's OWNER via
     * a new salon_staff row, no invite needed for your own salon.
     */
    @Transactional
    public SalonResponse create(CreateSalonRequest req, UUID ownerUserId) {
        String location = req.location().lat() + "," + req.location().lng();
        String strategy = req.stylistAssignmentStrategy() == null ? "least_loaded" : req.stylistAssignmentStrategy();
        Salon s = new Salon(req.name(), location, "pending", strategy);
        s = salons.save(s);
        staffService.addOwner(s.getId(), ownerUserId);
        return toResponse(s);
    }

    public SalonResponse getById(UUID id) {
        return salons.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));
    }

    @Transactional
    public SalonResponse update(UUID id, UpdateSalonRequest req) {
        Salon s = salons.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));
        if (req.name() != null) s.setName(req.name());
        if (req.location() != null) s.setLocation(req.location().lat() + "," + req.location().lng());
        if (req.status() != null) s.setStatus(req.status());
        if (req.stylistAssignmentStrategy() != null) s.setStylistAssignmentStrategy(req.stylistAssignmentStrategy());
        s.touch();
        return toResponse(s);
    }

    public List<NearbySalonResponse> near(double lat, double lng, double radiusKm) {
        return salons.findAll().stream()
                .map(s -> {
                    String[] parts = s.getLocation().split(",");
                    double sLat = Double.parseDouble(parts[0]);
                    double sLng = Double.parseDouble(parts[1]);
                    double dist = haversineKm(lat, lng, sLat, sLng);
                    return new NearbySalonResponse(s.getId(), s.getName(), dist);
                })
                .filter(r -> r.distanceKm() <= radiusKm)
                .sorted((a, b) -> Double.compare(a.distanceKm(), b.distanceKm()))
                .toList();
    }

    @Transactional
    public PolicyResponse upsertPolicy(UUID salonId, PolicyRequest req) {
        salons.findById(salonId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));
        SalonPolicy p = policies.findBySalonId(salonId).orElse(null);
        if (p == null) {
            p = new SalonPolicy(salonId, req.template(), req.freeCancelHours(), req.lateGraceMinutes(),
                    req.requirePrepayment(), req.slotGranularityMinutes());
            p = policies.save(p);
        } else {
            p.setTemplate(req.template());
            p.setFreeCancelHours(req.freeCancelHours());
            p.setLateGraceMinutes(req.lateGraceMinutes());
            p.setRequirePrepayment(req.requirePrepayment());
            p.setSlotGranularityMinutes(req.slotGranularityMinutes());
            p.touch();
        }
        return toPolicyResponse(p);
    }

    public PolicyResponse getPolicy(UUID salonId) {
        return policies.findBySalonId(salonId).map(this::toPolicyResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND"));
    }

    /** Bulk upsert of all 7 days at once — idempotent (re-running with the same body doesn't duplicate rows). */
    @Transactional
    public HoursResponse upsertHours(UUID salonId, HoursRequest req) {
        salons.findById(salonId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));
        for (HourEntry entry : req.hours()) {
            if (entry.dayOfWeek() < 0 || entry.dayOfWeek() > 6) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DAY_OF_WEEK");
            }
            if (entry.closeTime().compareTo(entry.openTime()) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CLOSE_TIME_MUST_BE_AFTER_OPEN_TIME");
            }
            SalonHours existing = hours.findBySalonIdAndDayOfWeek(salonId, entry.dayOfWeek()).orElse(null);
            if (existing == null) {
                hours.save(new SalonHours(salonId, entry.dayOfWeek(), entry.openTime(), entry.closeTime()));
            } else {
                existing.setOpenTime(entry.openTime());
                existing.setCloseTime(entry.closeTime());
            }
        }
        List<HourEntry> echoed = hours.findBySalonId(salonId).stream()
                .map(h -> new HourEntry(h.getDayOfWeek(), h.getOpenTime(), h.getCloseTime()))
                .toList();
        return new HoursResponse(salonId, echoed);
    }

    @Transactional
    public ServiceResponse addService(UUID salonId, ServiceRequest req) {
        salons.findById(salonId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));
        com.bmp.salon.entities.SalonService svc = new com.bmp.salon.entities.SalonService(
                salonId, req.name(), Money.ofPaise(req.pricePaise()), req.durationMinutes(), req.requiresStylistAssignment());
        svc = services.save(svc);
        return toServiceResponse(svc);
    }

    public List<ServiceResponse> listServices(UUID salonId) {
        return services.findBySalonId(salonId).stream().map(this::toServiceResponse).toList();
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return r * c;
    }

    private SalonResponse toResponse(Salon s) {
        String[] parts = s.getLocation().split(",");
        LatLng loc = new LatLng(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        return new SalonResponse(s.getId(), s.getName(), loc, s.getStatus(),
                s.getStylistAssignmentStrategy(), s.getCreatedAt(), s.getUpdatedAt());
    }

    private PolicyResponse toPolicyResponse(SalonPolicy p) {
        return new PolicyResponse(p.getId(), p.getSalonId(), p.getTemplate(), p.getFreeCancelHours(),
                p.getLateGraceMinutes(), p.isRequirePrepayment(), p.getSlotGranularityMinutes());
    }

    private ServiceResponse toServiceResponse(com.bmp.salon.entities.SalonService s) {
        return new ServiceResponse(s.getId(), s.getSalonId(), s.getName(), s.getPricePaise().paise(),
                s.getDurationMinutes(), s.isRequiresStylistAssignment());
    }
}
