package com.example.refunds;

/**
 * What the agent may put forward. Everything else, the customer,
 * the merchant, the limits, comes from the server, not from here.
 */
public record RefundProposal(
        String paymentId,
        long amountCents,
        String reasonCode) {}
