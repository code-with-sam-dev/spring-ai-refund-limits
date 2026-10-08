package com.example.refunds;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What may still be refunded today. Reserving is one conditional
 * UPDATE: the check and the write are the same statement, so two
 * requests can never both see the same room.
 */
@Component
public class Allowances {

    private final JdbcClient db;

    public Allowances(JdbcClient db) {
        this.db = db;
    }

    /** Returns what is left after reserving, or -1 if it would not fit. */
    public long reserve(String scope, String id, long limit, long cents) {
        db.sql("""
                INSERT INTO allowances
                    (scope, scope_id, business_day, limit_cents)
                VALUES (:scope, :id, current_date, :limit)
                ON CONFLICT DO NOTHING""")
                .param("scope", scope).param("id", id)
                .param("limit", limit)
                .update();
        return db.sql("""
                UPDATE allowances
                SET reserved_cents = reserved_cents + :cents
                WHERE scope = :scope AND scope_id = :id
                  AND business_day = current_date
                  AND reserved_cents + :cents <= limit_cents
                RETURNING limit_cents - reserved_cents""")
                .param("cents", cents)
                .param("scope", scope).param("id", id)
                .query(Long.class)
                .optional()
                .orElse(-1L);
    }

    public void release(String scope, String id, long cents) {
        db.sql("""
                UPDATE allowances
                SET reserved_cents = reserved_cents - :cents
                WHERE scope = :scope AND scope_id = :id
                  AND business_day = current_date""")
                .param("cents", cents)
                .param("scope", scope).param("id", id)
                .update();
    }
}
