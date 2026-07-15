package com.bmp.payment.internal.service;

import com.bmp.common.money.Money;
import com.bmp.payment.internal.dto.PaymentDtos.*;
import com.bmp.payment.internal.entity.PaymentOrder;
import com.bmp.payment.internal.repository.PaymentOrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * BMP-26: payment_order CRUD, data model only — NO real Razorpay API call in this ticket.
 * razorpay_order_id stays NULL; the TODO below marks exactly where the real create-order
 * call goes in Phase 3 (BMP-19).
 */
@Service
public class PaymentOrderService {

    private static final int COMMISSION_BPS = 1200; // 12% per locked 88/12 split

    /** Guards the dev-only manual status endpoint outside local/dev profiles. */
    @Value("${bmp.payment.allow-manual-status:true}")
    private boolean allowManualStatus;

    private final PaymentOrderRepository repo;

    public PaymentOrderService(PaymentOrderRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public PaymentOrderResponse create(UUID bookingId, CreatePaymentOrderRequest req) {
        String idempotencyKey = bookingId + ":1";
        if (repo.existsByIdempotencyKey(idempotencyKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PAYMENT_ORDER_ALREADY_EXISTS");
        }
        Money amount = Money.ofPaise(req.amountPaise());
        Money commission = amount.percentBps(COMMISSION_BPS);
        Money salonShare = amount.minus(commission);

        // TODO(Phase 3 / BMP-19): real Razorpay create-order API call goes HERE.
        // razorpayOrderId stays null until that integration exists.
        PaymentOrder order = new PaymentOrder(bookingId, null, idempotencyKey, amount, commission,
                salonShare, null, null, "created");
        order = repo.save(order);
        return toResponse(order);
    }

    public PaymentOrderResponse getById(UUID id) {
        return repo.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND"));
    }

    public PaymentOrderResponse getByBookingId(UUID bookingId) {
        return repo.findByBookingId(bookingId).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND"));
    }

    /**
     * DEV-ONLY. Exists ONLY so booking flows can be tested locally without a live Razorpay
     * webhook. Directly violates "Razorpay webhook = ONLY source of payment truth" if left
     * reachable in production. Feature-flagged via bmp.payment.allow-manual-status — set to
     * false outside local/dev profiles, or delete this endpoint once BMP-19 wires the real webhook.
     */
    @Transactional
    public PaymentOrderResponse updateStatusDevOnly(UUID id, UpdateStatusRequest req) {
        if (!allowManualStatus) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MANUAL_STATUS_UPDATE_DISABLED");
        }
        PaymentOrder order = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND"));
        order.setStatus(req.status());
        if ("captured".equals(req.status())) {
            order.setPaymentCapturedAt(java.time.Instant.now());
        }
        return toResponse(order);
    }

    private PaymentOrderResponse toResponse(PaymentOrder o) {
        return new PaymentOrderResponse(o.getId(), o.getBookingId(), o.getAmountPaise().paise(),
                o.getCommissionPaise().paise(), o.getSalonSharePaise().paise(), o.getRazorpayOrderId(),
                o.getStatus(), o.getPaymentCapturedAt(), o.getCreatedAt());
    }
}
