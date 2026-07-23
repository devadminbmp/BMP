package com.bmp.payment.repositories;

import com.bmp.payment.entities.SaasInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SaasInvoiceRepository extends JpaRepository<SaasInvoice, UUID> {
}
