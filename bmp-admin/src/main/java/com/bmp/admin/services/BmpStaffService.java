package com.bmp.admin.services;

import com.bmp.admin.dto.AdminDtos.*;
import com.bmp.admin.entities.BmpStaff;
import com.bmp.admin.repositories.BmpStaffRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** BMP-29: bmp_staff CRUD. password_hash is NEVER accepted from a client, only a plaintext "password" is. */
@Service
public class BmpStaffService {

    private final BmpStaffRepository repo;
    private final AuditLogService auditLogService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public BmpStaffService(BmpStaffRepository repo, AuditLogService auditLogService) {
        this.repo = repo;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public StaffResponse create(CreateStaffRequest req) {
        if (repo.existsByPhone(req.phone())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_ALREADY_EXISTS");
        }
        String hash = encoder.encode(req.password());
        BmpStaff staff = new BmpStaff(req.name(), req.phone(), req.email(), hash, req.role(), "active", null);
        staff = repo.save(staff);
        return toResponse(staff);
    }

    public StaffResponse getById(UUID id) {
        return repo.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));
    }

    public List<StaffResponse> list(String role) {
        List<BmpStaff> all = role == null ? repo.findAll() : repo.findByRole(role);
        return all.stream().map(this::toResponse).toList();
    }

    @Transactional
    public StaffResponse updateStatus(UUID id, StaffStatusRequest req) {
        BmpStaff staff = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));
        String before = staff.getStatus();
        staff.setStatus(req.status());
        staff.touch();
        auditLogService.record("bmp_staff", id, "staff.status_changed", "bmp_staff", id,
                Map.of("before", before, "after", req.status()), null);
        return toResponse(staff);
    }

    // never expose passwordHash
    private StaffResponse toResponse(BmpStaff s) {
        return new StaffResponse(s.getId(), s.getName(), s.getPhone(), s.getEmail(), s.getRole(),
                s.getStatus(), s.getCreatedAt());
    }
}
