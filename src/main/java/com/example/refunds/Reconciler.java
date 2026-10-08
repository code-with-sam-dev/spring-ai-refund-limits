package com.example.refunds;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Settles every refund whose answer was lost. It never pays: it asks
 * the network what happened to the key, then either records the
 * payout or gives the room back.
 */
@Component
public class Reconciler {

    private final JdbcClient db;
    private final CardNetwork network;
    private final Allowances allowances;

    public Reconciler(JdbcClient db, CardNetwork network,
                      Allowances allowances) {
        this.db = db;
        this.network = network;
        this.allowances = allowances;
    }

    @Scheduled(fixedDelay = 30_000)
    public int settle() {
        var unknown = db.sql("""
                SELECT idempotency_key, customer_id, merchant_id,
                       amount_cents
                FROM refunds WHERE status = 'UNKNOWN'""")
                .query((rs, n) -> new String[] {
                        rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)})
                .list();
        for (var r : unknown) {
            if (network.paid(r[0])) {
                mark(r[0], "EXECUTED");
            } else {
                long cents = Long.parseLong(r[3]);
                allowances.release("CUSTOMER", r[1], cents);
                allowances.release("MERCHANT", r[2], cents);
                mark(r[0], "RELEASED");
            }
        }
        return unknown.size();
    }

    private void mark(String key, String status) {
        db.sql("""
                UPDATE refunds SET status = :s, updated_at = now()
                WHERE idempotency_key = :k AND status = 'UNKNOWN'""")
                .param("s", status).param("k", key)
                .update();
    }
}
