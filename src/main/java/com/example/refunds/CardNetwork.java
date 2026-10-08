package com.example.refunds;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Stands in for the card network. Every row it writes is money
 * that moved. Like a real provider, it pays a given idempotency key
 * once, however many times it is asked.
 */
@Component
public class CardNetwork {

    /** Thrown when the payout happened but the answer never came back. */
    public static class Timeout extends RuntimeException {
        Timeout() {
            super("card network did not answer in time");
        }
    }

    private final JdbcClient db;
    private final AtomicBoolean loseNextAnswer = new AtomicBoolean();

    public CardNetwork(JdbcClient db) {
        this.db = db;
    }

    public void refund(String key, String paymentId, long cents) {
        db.sql("""
                INSERT INTO provider_payouts
                    (idempotency_key, payment_id, amount_cents)
                VALUES (:key, :payment, :cents)
                ON CONFLICT (idempotency_key) DO NOTHING""")
                .param("key", key)
                .param("payment", paymentId)
                .param("cents", cents)
                .update();
        if (loseNextAnswer.getAndSet(false)) {
            throw new Timeout();
        }
    }

    /** What the network says about a key: did that payout happen? */
    public boolean paid(String key) {
        return db.sql("""
                SELECT count(*) FROM provider_payouts
                WHERE idempotency_key = :key""")
                .param("key", key)
                .query(Long.class)
                .single() > 0;
    }

    /** Demo hook: the next payout succeeds, its answer is lost. */
    public void loseNextAnswer() {
        loseNextAnswer.set(true);
    }
}
