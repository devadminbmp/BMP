package com.bmp.booking.repositories;

import com.bmp.booking.entities.InvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Invoice line items. V008 (Session 49). */
public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, UUID> {

    /** Always ordered — see InvoiceLine.lineNo for why an unordered bill looks tampered with. */
    List<InvoiceLine> findByInvoiceIdOrderByLineNoAsc(UUID invoiceId);
}
