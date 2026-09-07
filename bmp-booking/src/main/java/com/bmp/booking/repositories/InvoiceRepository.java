package com.bmp.booking.repositories;

import com.bmp.booking.entities.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/** Invoices. V008 (Session 49). */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByBookingId(UUID bookingId);
    Optional<Invoice> findByInvoiceNo(String invoiceNo);
    boolean existsByBookingId(UUID bookingId);

    /** The customer's own bills, newest first. */
    Page<Invoice> findByCustomerIdOrderByIssuedAtDesc(UUID customerId, Pageable pageable);

    /** The salon's book, newest first. */
    Page<Invoice> findBySalonIdOrderByIssuedAtDesc(UUID salonId, Pageable pageable);

    /** What this salon is still owed — the "who hasn't paid" list. */
    Page<Invoice> findBySalonIdAndStatusOrderByIssuedAtDesc(UUID salonId, String status, Pageable pageable);

    /**
     * The next invoice number, from a Postgres SEQUENCE.
     *
     * <h2>Why not count(*) + 1</h2>
     * Session 48 fixed precisely that bug on support-ticket references. Counting rows means two
     * invoices raised in the same second get the same number, and deleting a row causes a number
     * to be reused. On a support ticket that is confusing; on an invoice it is two customers
     * holding a bill with the same number, which is the kind of thing an accountant finds in
     * March and nobody can reconstruct.
     *
     * <p>A sequence is atomic, never reuses, and does not care about concurrent callers. It also
     * deliberately leaves gaps when a transaction rolls back — a gap is fine, a duplicate is not.
     */
    @Query(value = "SELECT nextval('booking_schema.invoice_number_seq')", nativeQuery = true)
    long nextInvoiceNumber();
}
