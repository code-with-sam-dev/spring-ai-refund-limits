package com.example.refunds;

import java.util.UUID;

/**
 * Act 1, naive. Each refund is checked on its own: under the cap,
 * eligible, not more than the payment. Nothing adds them up.
 */
public class CapOnlyDesk implements RefundDesk {

    private final Payments payments;
    private final CardNetwork network;
    private final RefundLimits limits;

    public CapOnlyDesk(Payments payments, CardNetwork network,
                       RefundLimits limits) {
        this.payments = payments;
        this.network = network;
        this.limits = limits;
    }

    @Override
    public Decision issue(Ticket ticket, RefundProposal p) {
        var payment = payments.find(ticket, p.paymentId());
        if (payment.isEmpty()) {
            return Decision.refused(p, "UNKNOWN_PAYMENT");
        }
        if (!p.reasonCode().equals(payment.get().eligibleReason())) {
            return Decision.refused(p, "NOT_ELIGIBLE");
        }
        if (p.amountCents() > payment.get().amountCents()) {
            return Decision.refused(p, "MORE_THAN_PAYMENT");
        }
        if (p.amountCents() < limits.maxPerRefundCents()) {
            network.refund(UUID.randomUUID().toString(),
                    p.paymentId(), p.amountCents());
            return Decision.paid(p);
        }
        return Decision.refused(p, "OVER_PER_REFUND_LIMIT");
    }
}
