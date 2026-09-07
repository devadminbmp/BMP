package com.bmp.booking.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;

import java.util.UUID;

/**
 * One line on an invoice. V008 (Session 49).
 *
 * <h2>A copy, deliberately</h2>
 * {@code description} is the service name as it read on the day, not a pointer at
 * {@code salon_service}. A salon renaming "Haircut" to "Classic Cut" must not rewrite last
 * month's invoices, and deleting a service must not leave a bill with a hole in it.
 *
 * <p>Entirely immutable. There is no setter on this class and there should never be one: editing
 * a line on an issued invoice is not a thing a system should be able to do quietly. A wrong
 * invoice is cancelled and reissued, which leaves both documents in the record.
 */
@Entity
@Table(name = "invoice_line", schema = "booking_schema")
@Getter
public class InvoiceLine {

    @Id
    private UUID id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    /**
     * Position on the printed document, from 1.
     *
     * <p>Without an explicit order the lines come back however the rows happen to sit, and an
     * invoice whose lines reorder between two viewings looks tampered with.
     */
    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "description", nullable = false, updatable = false, length = 200)
    private String description;

    /** Who did the work. Null for a service booked without a named stylist. */
    @Column(name = "stylist_name", updatable = false, length = 120)
    private String stylistName;

    @Column(name = "duration_minutes", updatable = false)
    private Integer durationMinutes;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    protected InvoiceLine() {} // JPA

    public InvoiceLine(UUID invoiceId, int lineNo, String description, String stylistName,
                        Integer durationMinutes, long amountPaise) {
        this.id = UuidV7.generate();
        this.invoiceId = invoiceId;
        this.lineNo = lineNo;
        this.description = description;
        this.stylistName = stylistName;
        this.durationMinutes = durationMinutes;
        this.amountPaise = amountPaise;
    }
}
