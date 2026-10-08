package com.example.refunds;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Prints every act of the video, no model involved: the same
 * requests against the naive desks and the guarded one, and the
 * money the card network actually paid. Run with
 * --spring.profiles.active=demo (scripts/acts.sh does).
 */
@Component
@Profile("demo")
public class ActsDemo implements CommandLineRunner {

    private final Payments payments;
    private final Allowances allowances;
    private final CardNetwork network;
    private final KillSwitch killSwitch;
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final ConfigurableApplicationContext context;

    public ActsDemo(ConfigurableApplicationContext context,
                    Payments payments, Allowances allowances,
                    CardNetwork network, KillSwitch killSwitch,
                    JdbcClient db, TransactionTemplate tx) {
        this.payments = payments;
        this.allowances = allowances;
        this.network = network;
        this.killSwitch = killSwitch;
        this.db = db;
        this.tx = tx;
        this.context = context;
    }

    static final Ticket SARAH = new Ticket("CUST-17", "agent-james");
    static final RefundLimits LIMITS =
            new RefundLimits(2_000, 5_000, 10_000, "demo");
    static final RefundLimits MERCHANT_ONLY =
            new RefundLimits(2_000, 1_000_000, 10_000, "demo");

    @Override
    public void run(String... args) throws Exception {
        act1();
        act2();
        act3();
        act4();
        System.exit(SpringApplication.exit(context));
    }

    void act1() {
        reset();
        var capOnly = new CapOnlyDesk(payments, network, LIMITS);
        forty(capOnly);
        print("act 1", "cap only", "40 refunds of $19.99");
        reset();
        var refused = forty(reserving(LIMITS));
        print("act 1", "reserving", "40 refunds of $19.99, "
                + refused + " refused");
    }

    void act2() throws Exception {
        reset();
        var naive = new ReadThenWriteDesk(payments,
                new CapOnlyDesk(payments, network, MERCHANT_ONLY),
                db, MERCHANT_ONLY);
        fortyAtOnce(naive);
        print("act 2", "read then write", "40 at once, limit $100.00");
        reset();
        fortyAtOnce(reserving(MERCHANT_ONLY));
        print("act 2", "reserving", "40 at once, limit $100.00");
    }

    void act3() {
        reset();
        var naive = new ReadThenWriteDesk(payments,
                new CapOnlyDesk(payments, network, LIMITS), db, LIMITS);
        network.loseNextAnswer();
        try {
            naive.issue(SARAH, ride(1));
        } catch (CardNetwork.Timeout lost) {
            naive.issue(SARAH, ride(1));            // the retry
        }
        print("act 3", "read then write", "answer lost, retried once");
        reset();
        var desk = reserving(LIMITS);
        network.loseNextAnswer();
        var first = desk.issue(SARAH, ride(1)).status();
        var retry = desk.issue(SARAH, ride(1)).status();
        print("act 3", "reserving", "answer lost: " + first
                + ", retried: " + retry);
    }

    void act4() {
        reset();
        killSwitch.engage("demo");
        forty(reserving(LIMITS));
        print("act 4", "reserving", "kill switch on, 40 proposed");
    }

    ReservingDesk reserving(RefundLimits limits) {
        return new ReservingDesk(payments, allowances, network,
                killSwitch, limits, db, tx);
    }

    static RefundProposal ride(int n) {
        return new RefundProposal("RIDE-%04d".formatted(400 + n), 1_999,
                "DRIVER_NO_SHOW");
    }

    int forty(RefundDesk desk) {
        int refused = 0;
        for (int n = 1; n <= 40; n++) {
            if (desk.issue(SARAH, ride(n)).refusal() != null) refused++;
        }
        return refused;
    }

    void fortyAtOnce(RefundDesk desk) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Callable<Decision>> calls = new ArrayList<>();
            for (int n = 1; n <= 40; n++) {
                var p = ride(n);
                calls.add(() -> {
                    start.await();
                    return desk.issue(SARAH, p);
                });
            }
            var futures = calls.stream().map(pool::submit).toList();
            start.countDown();
            for (var f : futures) f.get();
        }
    }

    void print(String act, String desk, String what) {
        var paid = db.sql("SELECT count(*) || ' ' || coalesce(sum("
                        + "amount_cents), 0) FROM provider_payouts")
                .query(String.class).single().split(" ");
        System.out.printf("%-6s %-16s %-44s paid %2s, $%.2f%n", act,
                desk, what, paid[0], Long.parseLong(paid[1]) / 100.0);
    }

    void reset() {
        db.sql("TRUNCATE refunds, provider_payouts, allowances").update();
        db.sql("UPDATE kill_switch SET engaged = false").update();
    }
}
