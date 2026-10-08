package com.example.refunds;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Which desk the agent talks to. The video runs the same agent
 * against each: refunds.desk=cap-only, read-then-write or reserving.
 */
@Configuration
@EnableScheduling
public class DeskConfig {

    @Bean
    RefundDesk refundDesk(@Value("${refunds.desk:reserving}") String desk,
                          Payments payments, Allowances allowances,
                          CardNetwork network, KillSwitch killSwitch,
                          RefundLimits limits, JdbcClient db,
                          TransactionTemplate tx) {
        var capOnly = new CapOnlyDesk(payments, network, limits);
        return switch (desk) {
            case "cap-only" -> capOnly;
            case "read-then-write" ->
                    new ReadThenWriteDesk(payments, capOnly, db, limits);
            case "reserving" -> new ReservingDesk(payments, allowances,
                    network, killSwitch, limits, db, tx);
            default -> throw new IllegalArgumentException(
                    "unknown refunds.desk " + desk);
        };
    }
}
