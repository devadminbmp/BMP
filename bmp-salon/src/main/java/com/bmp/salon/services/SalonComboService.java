package com.bmp.salon.services;

import com.bmp.common.money.Money;
import com.bmp.salon.dto.SalonComboDtos.*;
import com.bmp.salon.entities.SalonCombo;
import com.bmp.salon.entities.SalonComboItem;
import com.bmp.salon.repositories.SalonComboItemRepository;
import com.bmp.salon.repositories.SalonComboRepository;
import com.bmp.salon.repositories.SalonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Session 11 — salon_combo / salon_combo_item CRUD. Entities and repositories for these
 * two tables existed since the original schema-generation pass, but nothing had wired a
 * service/controller to them yet (see CONTEXT.md's per-module CRUD status table) — this
 * closes that specific gap, not a broader "combos" product feature beyond straightforward
 * CRUD.
 *
 * <p>Combos are created with their items in one call ({@link #create}) since a combo with
 * zero items isn't a meaningful bundle. After creation, items can be added or removed
 * individually; the combo's own header fields (name/price/allowsAddons) are updated
 * separately via {@link #update} and never touch the item list.
 */
@Service
public class SalonComboService {

    private final SalonRepository salons;
    private final SalonComboRepository combos;
    private final SalonComboItemRepository items;

    public SalonComboService(SalonRepository salons, SalonComboRepository combos, SalonComboItemRepository items) {
        this.salons = salons;
        this.combos = combos;
        this.items = items;
    }

    @Transactional
    public ComboResponse create(UUID salonId, CreateComboRequest req) {
        salons.findById(salonId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        SalonCombo combo = new SalonCombo(salonId, req.name(), Money.ofPaise(req.pricePaise()), req.allowsAddons());
        combo = combos.save(combo);

        List<SalonComboItem> saved = new java.util.ArrayList<>();
        for (ComboItemRequest item : req.items()) {
            saved.add(items.save(new SalonComboItem(combo.getId(), item.serviceId(), item.requiresSpecialist(), item.sequence())));
        }
        return toResponse(combo, saved);
    }

    public ComboResponse getById(UUID salonId, UUID comboId) {
        SalonCombo combo = findOwnedCombo(salonId, comboId);
        return toResponse(combo, items.findByComboId(comboId));
    }

    public List<ComboResponse> list(UUID salonId) {
        return combos.findBySalonId(salonId).stream()
                .map(c -> toResponse(c, items.findByComboId(c.getId())))
                .toList();
    }

    @Transactional
    public ComboResponse update(UUID salonId, UUID comboId, UpdateComboRequest req) {
        SalonCombo combo = findOwnedCombo(salonId, comboId);
        if (req.name() != null) combo.setName(req.name());
        if (req.pricePaise() != null) combo.setPricePaise(Money.ofPaise(req.pricePaise()));
        if (req.allowsAddons() != null) combo.setAllowsAddons(req.allowsAddons());
        return toResponse(combo, items.findByComboId(comboId));
    }

    @Transactional
    public void delete(UUID salonId, UUID comboId) {
        findOwnedCombo(salonId, comboId);
        items.deleteByComboId(comboId);
        combos.deleteById(comboId);
    }

    @Transactional
    public ComboItemResponse addItem(UUID salonId, UUID comboId, ComboItemRequest req) {
        findOwnedCombo(salonId, comboId);
        SalonComboItem item = items.save(new SalonComboItem(comboId, req.serviceId(), req.requiresSpecialist(), req.sequence()));
        return toItemResponse(item);
    }

    @Transactional
    public void removeItem(UUID salonId, UUID comboId, UUID itemId) {
        findOwnedCombo(salonId, comboId);
        SalonComboItem item = items.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "COMBO_ITEM_NOT_FOUND"));
        if (!item.getComboId().equals(comboId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "COMBO_ITEM_NOT_FOUND");
        }
        items.deleteById(itemId);
    }

    private SalonCombo findOwnedCombo(UUID salonId, UUID comboId) {
        SalonCombo combo = combos.findById(comboId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "COMBO_NOT_FOUND"));
        if (!combo.getSalonId().equals(salonId)) {
            // Deliberately the same 404 as "doesn't exist" rather than 403 — avoids leaking
            // whether a given combo id exists under a DIFFERENT salon to an unauthorized caller.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "COMBO_NOT_FOUND");
        }
        return combo;
    }

    private ComboResponse toResponse(SalonCombo c, List<SalonComboItem> comboItems) {
        return new ComboResponse(c.getId(), c.getSalonId(), c.getName(), c.getPricePaise().paise(),
                c.isAllowsAddons(), c.getCreatedAt(), comboItems.stream().map(this::toItemResponse).toList());
    }

    private ComboItemResponse toItemResponse(SalonComboItem i) {
        return new ComboItemResponse(i.getId(), i.getServiceId(), i.isRequiresSpecialist(), i.getSequence());
    }
}
