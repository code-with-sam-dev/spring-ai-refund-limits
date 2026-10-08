package com.example.refunds;

/** The desk's answer to one proposal. */
public record Decision(
        String paymentId,
        long amountCents,
        String status,
        String refusal) {

    static Decision paid(RefundProposal p) {
        return new Decision(p.paymentId(), p.amountCents(),
                "EXECUTED", null);
    }

    static Decision refused(RefundProposal p, String why) {
        return new Decision(p.paymentId(), p.amountCents(),
                "REFUSED", why);
    }

    static Decision unknown(RefundProposal p) {
        return new Decision(p.paymentId(), p.amountCents(),
                "UNKNOWN", null);
    }
}
