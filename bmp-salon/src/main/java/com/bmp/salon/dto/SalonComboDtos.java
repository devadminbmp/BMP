package com.bmp.salon.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Session 11 — salon_schema.salon_combo / salon_combo_item CRUD. Bundled-service
 * packages (e.g. "Bridal Package" = haircut + makeup + mehendi at one discounted price). */
public final class SalonComboDtos {
    private SalonComboDtos() {}

    public record ComboItemRequest(@NotNull UUID serviceId, boolean requiresSpecialist, @Min(0) int sequence) {}

    public record CreateComboRequest(
        @NotBlank String name,
        @NotNull long pricePaise,
        boolean allowsAddons,
        @NotEmpty List<@Valid ComboItemRequest> items
    ) {}

    /** All fields optional — only non-null ones are applied. Does NOT touch items; use the
     * dedicated item endpoints to add/remove those. */
    public record UpdateComboRequest(String name, Long pricePaise, Boolean allowsAddons) {}

    public record ComboItemResponse(UUID id, UUID serviceId, boolean requiresSpecialist, int sequence) {}

    public record ComboResponse(UUID id, UUID salonId, String name, long pricePaise, boolean allowsAddons,
                                 Instant createdAt, List<ComboItemResponse> items) {}

    public record ErrorResponse(String error, String message) {}
}
