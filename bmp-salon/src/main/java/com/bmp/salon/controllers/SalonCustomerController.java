package com.bmp.salon.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.salon.entities.SalonCustomer;
import com.bmp.salon.services.SalonCustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The salon's own customer book. Session 52, V026.
 *
 * <h2>Every route here is scoped to the caller's own salon</h2>
 * The salon id comes from the JWT, never from the request. A contact list is the most
 * straightforwardly saleable thing a salon owns, and a {@code salonId} request parameter on these
 * routes would let any owner page through a competitor's regulars — with names and phone numbers.
 *
 * <p>So there is no salonId parameter on any customer-facing method below. {@code principal
 * .salonId()} is the only source, and a caller whose token carries none is refused outright.
 *
 * <h2>Not visible to stylists</h2>
 * Deliberately consistent with Session 48/49: a stylist sees their schedule, never customer
 * contact details. Owners and managers only.
 */
@Tag(name = "Salon customers",
     description = "The SALON's own contact book for walk-in and phone customers — not BMP user accounts. Owner/manager only, always scoped to their own salon.")
@RestController
@RequestMapping("/api/v1/salon-customers")
public class SalonCustomerController {

    private final SalonCustomerService customers;

    public SalonCustomerController(SalonCustomerService customers) {
        this.customers = customers;
    }

    /**
     * @param visitCount how many counter bookings this person has had here. The counter's
     *                   "is this a regular?" answer, shown on the card.
     */
    public record CustomerResponse(UUID id, String name, String phone, String email,
                                    int visitCount, Instant lastVisitAt, String notes,
                                    boolean hasBmpAccount) {
        static CustomerResponse of(SalonCustomer c) {
            return new CustomerResponse(c.getId(), c.getName(), c.getPhone(), c.getEmail(),
                    c.getVisitCount(), c.getLastVisitAt(), c.getNotes(),
                    c.getLinkedUserId() != null);
        }
    }

    public record UpsertCustomerRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 20) String phone,
            @Size(max = 200) String email,
            @Size(max = 1000) String notes) {}

    /**
     * Type-ahead at the counter. Blank {@code q} returns the regulars, most recently seen first.
     *
     * <p>Matches a partial phone OR a partial name, because the receptionist sometimes has the
     * number in front of them and sometimes only remembers "Priya".
     */
    @Operation(summary = "Search this salon's own customers",
               description = "Owner/manager of THIS salon. Partial phone or name; blank returns recent regulars. Scoped to the caller's salon from the token — there is deliberately no salonId parameter.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null")
    @GetMapping
    public List<CustomerResponse> search(@AuthenticationPrincipal AuthenticatedUser caller,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return customers.search(caller.salonId(), q, page, size)
                .map(CustomerResponse::of).getContent();
    }

    /**
     * Create or update a customer record without booking anything.
     *
     * <p>Exists for the case where somebody phones to ask about prices and the desk wants to note
     * them down, and for correcting a mistyped name afterwards. The booking path calls the same
     * service method, so both doors converge on one record per phone number.
     */
    @Operation(summary = "Add or update a customer in this salon's book")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null")
    @PostMapping
    public ResponseEntity<CustomerResponse> upsert(@AuthenticationPrincipal AuthenticatedUser caller,
                                                     @Valid @RequestBody UpsertCustomerRequest req) {
        SalonCustomer c = customers.upsert(caller.salonId(), req.name(), req.phone(),
                req.email(), req.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(CustomerResponse.of(c));
    }

    public record EditCustomerRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 200) String email,
            @Size(max = 1000) String notes) {}

    /**
     * Correct a record. The PHONE is not editable — it is the row's identity and what every past
     * booking was matched by; see {@code SalonCustomerService.edit}.
     */
    @Operation(summary = "Edit a customer's name, email or note",
               description = "Owner/manager of THIS salon. The phone number is deliberately not editable: it is the unique key the customer's whole visit history was matched by. A wrong number becomes a new record.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null")
    @PutMapping("/{customerId}")
    public CustomerResponse edit(@AuthenticationPrincipal AuthenticatedUser caller,
                                  @PathVariable UUID customerId,
                                  @Valid @RequestBody EditCustomerRequest req) {
        return CustomerResponse.of(
                customers.edit(caller.salonId(), customerId, req.name(), req.email(), req.notes()));
    }

    @Operation(summary = "One customer's record")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null")
    @GetMapping("/{customerId}")
    public CustomerResponse one(@AuthenticationPrincipal AuthenticatedUser caller,
                                 @PathVariable UUID customerId) {
        return CustomerResponse.of(customers.requireAtSalon(caller.salonId(), customerId));
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // SERVICE-TO-SERVICE
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Called by bmp-booking while taking a counter booking. {@code ROLE_SERVICE} only.
     *
     * <h2>Why bmp-booking calls this rather than the app calling both</h2>
     * If the app upserted the customer and then created the booking, a failure between the two
     * leaves a contact record for a booking that never happened — and, worse, the customer's
     * details could be supplied to the booking call by the client, which is the same class of
     * mistake as letting the client supply the price (Session 30).
     *
     * <p>Doing it here means the customer id on a counter booking always corresponds to a real
     * row in this salon's book, and the manager cannot type one.
     *
     * <p>{@code salonId} IS a parameter on this route, unlike every method above — a service
     * caller has no salon of its own. It is safe because {@code ROLE_SERVICE} is only granted by
     * the internal key, and bmp-booking passes the salon it took from the manager's own token.
     */
    @Operation(summary = "Upsert a customer for a counter booking (service-to-service)")
    @PreAuthorize("hasRole('SERVICE')")
    @PostMapping("/internal/{salonId}/upsert")
    public CustomerResponse internalUpsert(@PathVariable UUID salonId,
                                            @Valid @RequestBody UpsertCustomerRequest req) {
        return CustomerResponse.of(
                customers.upsert(salonId, req.name(), req.phone(), req.email(), req.notes()));
    }

    /** Count a visit once the booking is actually written. Service-to-service. */
    @Operation(summary = "Record that a counter booking was taken for this customer")
    @PreAuthorize("hasRole('SERVICE')")
    @PostMapping("/internal/{salonId}/{customerId}/visit")
    public ResponseEntity<Void> internalRecordVisit(@PathVariable UUID salonId,
                                                     @PathVariable UUID customerId) {
        customers.recordVisit(salonId, customerId);
        return ResponseEntity.noContent().build();
    }
}
