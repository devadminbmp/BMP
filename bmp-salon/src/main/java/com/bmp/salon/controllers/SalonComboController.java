package com.bmp.salon.controllers;

import com.bmp.salon.dto.SalonComboDtos.*;
import com.bmp.salon.services.SalonComboService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Session 11 — salon_schema.salon_combo / salon_combo_item CRUD (bundled-service
 * packages, e.g. a "Bridal Package" combining several services at one discounted price).
 * Not restricted to the owning salon's OWNER/MANAGER yet — same "open pending a follow-up
 * authorization ticket" status as most of SalonController's own endpoints (see CONTEXT.md).
 */
@Tag(name = "Salon Combos", description = "salon_combo / salon_combo_item CRUD. A combo is created with its items in one call; items can be added/removed individually afterward, independent of the combo's own name/price/allowsAddons fields.")
@RestController
@RequestMapping("/api/v1/salons/{salonId}/combos")
public class SalonComboController {

    private final SalonComboService service;

    public SalonComboController(SalonComboService service) {
        this.service = service;
    }

    @Operation(summary = "Create a combo with its items", description = "A combo with zero items isn't a meaningful bundle, so items are required at creation.")
    @PostMapping
    public ResponseEntity<ComboResponse> create(@PathVariable UUID salonId, @Valid @RequestBody CreateComboRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(salonId, req));
    }

    @Operation(summary = "List this salon's combos")
    @GetMapping
    public List<ComboResponse> list(@PathVariable UUID salonId) {
        return service.list(salonId);
    }

    @Operation(summary = "Get a combo by id")
    @GetMapping("/{comboId}")
    public ComboResponse getById(@PathVariable UUID salonId, @PathVariable UUID comboId) {
        return service.getById(salonId, comboId);
    }

    @Operation(summary = "Update a combo's name/price/allowsAddons", description = "Only non-null fields are applied. Does NOT touch the item list — use the item endpoints below for that.")
    @PutMapping("/{comboId}")
    public ComboResponse update(@PathVariable UUID salonId, @PathVariable UUID comboId, @RequestBody UpdateComboRequest req) {
        return service.update(salonId, comboId, req);
    }

    @Operation(summary = "Delete a combo and all its items")
    @DeleteMapping("/{comboId}")
    public ResponseEntity<Void> delete(@PathVariable UUID salonId, @PathVariable UUID comboId) {
        service.delete(salonId, comboId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Add a service to an existing combo")
    @PostMapping("/{comboId}/items")
    public ResponseEntity<ComboItemResponse> addItem(@PathVariable UUID salonId, @PathVariable UUID comboId,
                                                        @Valid @RequestBody ComboItemRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addItem(salonId, comboId, req));
    }

    @Operation(summary = "Remove a service from a combo")
    @DeleteMapping("/{comboId}/items/{itemId}")
    public ResponseEntity<Void> removeItem(@PathVariable UUID salonId, @PathVariable UUID comboId, @PathVariable UUID itemId) {
        service.removeItem(salonId, comboId, itemId);
        return ResponseEntity.noContent().build();
    }
}
