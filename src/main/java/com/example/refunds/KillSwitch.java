package com.example.refunds;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One row, checked on the payout path itself. Removing the tool from
 * the agent's prompt would stop the agent; this stops the money.
 */
@Component
public class KillSwitch {

    private final JdbcClient db;

    public KillSwitch(JdbcClient db) {
        this.db = db;
    }

    public boolean engaged() {
        return db.sql("SELECT engaged FROM kill_switch WHERE id = 1")
                .query(Boolean.class)
                .single();
    }

    public void engage(String reason) {
        db.sql("""
                UPDATE kill_switch SET engaged = true, reason = :r
                WHERE id = 1""")
                .param("r", reason)
                .update();
    }
}
