package com.example.refunds;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Acts 2 and 3, naive. Adds up what the merchant refunded today
 * before paying, which stops the salami one request at a time.
 * Two flaws remain: it reads the total, then pays, so parallel
 * requests all see the same room; and a retried request gets a new
 * key, so the network pays it again.
 */
public class ReadThenWriteDesk implements RefundDesk {

    private final Payments payments;
    private final CapOnlyDesk perRefund;
    private final JdbcClient db;
    private final RefundLimits limits;

    public ReadThenWriteDesk(Payments payments, CapOnlyDesk perRefund,
                             JdbcClient db, RefundLimits limits) {
        this.payments = payments;
        this.perRefund = perRefund;
        this.db = db;
        this.limits = limits;
    }

    @Override
    public Decision issue(Ticket ticket, RefundProposal p) {
        var payment = payments.find(ticket, p.paymentId());
        if (payment.isEmpty()) {
            return Decision.refused(p, "UNKNOWN_PAYMENT");
        }
        long spentToday = db.sql("""
                SELECT coalesce(sum(o.amount_cents), 0)
                FROM provider_payouts o
                JOIN payments p ON p.id = o.payment_id
                WHERE p.merchant_id = :merchant
                  AND o.paid_at >= current_date""")
                .param("merchant", payment.get().merchantId())
                .query(Long.class)
                .single();
        if (spentToday + p.amountCents()
                > limits.maxMerchantDailyCents()) {
            return Decision.refused(p, "MERCHANT_DAILY_LIMIT");
        }
        return perRefund.issue(ticket, p);
    }
}
