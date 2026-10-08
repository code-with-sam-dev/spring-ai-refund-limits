package com.example.refunds;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The payment facts the desk trusts: they come from the database. */
@Component
public class Payments {

    public record Payment(
            String id,
            String customerId,
            String merchantId,
            long amountCents,
            String eligibleReason) {}

    private final JdbcClient db;

    public Payments(JdbcClient db) {
        this.db = db;
    }

    /** Only a payment of this ticket's customer is ever found. */
    public Optional<Payment> find(Ticket ticket, String paymentId) {
        return db.sql("""
                SELECT id, customer_id, merchant_id, amount_cents,
                       eligible_reason
                FROM payments
                WHERE id = :id AND customer_id = :customer""")
                .param("id", paymentId)
                .param("customer", ticket.customerId())
                .query(Payment.class)
                .optional();
    }
}
