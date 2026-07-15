/**
 * Payment MODULE — Razorpay Route, webhook source-of-truth, payouts, commission.
 *
 * <p>Public surface: com.bmp.payment.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: payment_schema — payment_order, webhook_event, razorpay_linked_account,
 * payout_queue_item, payout_batch, commission_ledger, refund_execution, bmp_account,
 * saas_subscription, saas_invoice.
 *
 * <p>UPDATED: this comment previously listed a simpler 4-table version (payment,
 * payout_record, payout_queue, bank_account) matching an earlier skeleton-stage
 * plan. CONTEXT.md's later, more detailed Session-3 design (explicitly the
 * project's locked source of truth) specifies the 10 tables above instead —
 * this doc comment has been corrected to match. See V006__payment_schema.sql.
 *
 * <p><b>Session 5:</b> this is now an independently-deployable Spring Boot
 * service (see its own pom.xml/Application.java/application.yml), not a Modulith
 * module of one shared deployable. Spring Modulith annotation removed accordingly.
 */
package com.bmp.payment;
