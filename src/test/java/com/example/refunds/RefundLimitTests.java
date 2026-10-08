package com.example.refunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every act of the video, with no model: the same requests against
 * the naive desk and the guarded one, and the money that moved.
 */
@SpringBootTest(properties =
        "support.jwt.secret=test-only-secret-at-least-32-bytes-long")
@Testcontainers
class RefundLimitTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17");

    @Autowired Payments payments;
    @Autowired Allowances allowances;
    @Autowired CardNetwork network;
    @Autowired KillSwitch killSwitch;
    @Autowired Reconciler reconciler;
    @Autowired JdbcClient db;
    @Autowired TransactionTemplate tx;

    static final Ticket SARAH = new Ticket("CUST-17", "agent-james");

    @BeforeEach
    void cleanDay() {
        db.sql("TRUNCATE refunds, provider_payouts, allowances").update();
        db.sql("UPDATE kill_switch SET engaged = false").update();
    }

    RefundLimits limits(long customerDaily, long merchantDaily) {
        return new RefundLimits(2_000, customerDaily, merchantDaily, "test");
    }

    CapOnlyDesk capOnly(RefundLimits l) {
        return new CapOnlyDesk(payments, network, l);
    }

    ReservingDesk reserving(RefundLimits l) {
        return new ReservingDesk(payments, allowances, network, killSwitch,
                l, db, tx);
    }

    static RefundProposal ride(int n) {
        return new RefundProposal("RIDE-%04d".formatted(400 + n), 1_999,
                "DRIVER_NO_SHOW");
    }

    long paidOut() {
        return db.sql("SELECT coalesce(sum(amount_cents), 0)"
                        + " FROM provider_payouts")
                .query(Long.class).single();
    }

    long payouts() {
        return db.sql("SELECT count(*) FROM provider_payouts")
                .query(Long.class).single();
    }

    // Act 1: the $20 loophole

    @Test
    void capOnlyPaysFortySmallRefunds() {
        var desk = capOnly(limits(5_000, 10_000));
        for (int n = 1; n <= 40; n++) {
            desk.issue(SARAH, ride(n));
        }
        assertThat(paidOut()).isEqualTo(79_960);   // $799.60
    }

    @Test
    void reservingStopsAtTheCustomersDailyAllowance() {
        var desk = reserving(limits(5_000, 10_000));
        var refused = 0;
        for (int n = 1; n <= 40; n++) {
            if (desk.issue(SARAH, ride(n)).refusal() != null) refused++;
        }
        assertThat(paidOut()).isEqualTo(3_998);    // two refunds fit $50
        assertThat(refused).isEqualTo(38);
    }

    @Test
    void eachRefundStillNeedsItsOwnReason() {
        var desk = reserving(limits(5_000, 10_000));
        var noReason = new RefundProposal("RIDE-0399", 1_000,
                "DRIVER_NO_SHOW");
        assertThat(desk.issue(SARAH, noReason).refusal())
                .isEqualTo("NOT_ELIGIBLE");
        var someoneElse = new RefundProposal("RIDE-9001", 1_000,
                "DRIVER_NO_SHOW");
        assertThat(desk.issue(SARAH, someoneElse).refusal())
                .isEqualTo("UNKNOWN_PAYMENT");
        assertThat(paidOut()).isZero();
    }

    // Act 2: the race

    long fireForty(RefundDesk desk) throws Exception {
        var pool = Executors.newVirtualThreadPerTaskExecutor();
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
        pool.shutdown();
        return paidOut();
    }

    @Test
    void readThenWriteOverspendsUnderParallelRequests() throws Exception {
        var l = limits(1_000_000, 10_000);
        var desk = new ReadThenWriteDesk(payments, capOnly(l), db, l);
        assertThat(fireForty(desk)).isGreaterThan(10_000);
    }

    @Test
    void reservingNeverPassesTheMerchantsDailyAllowance() throws Exception {
        var desk = reserving(limits(1_000_000, 10_000));
        assertThat(fireForty(desk)).isEqualTo(9_995);   // five fit $100
    }

    // Act 3: the uncertain outcome

    @Test
    void readThenWritePaysTwiceWhenTheAnswerIsLost() {
        var l = limits(5_000, 10_000);
        var desk = new ReadThenWriteDesk(payments, capOnly(l), db, l);
        network.loseNextAnswer();
        assertThatThrownBy(() -> desk.issue(SARAH, ride(1)))
                .isInstanceOf(CardNetwork.Timeout.class);
        desk.issue(SARAH, ride(1));                    // the retry
        assertThat(payouts()).isEqualTo(2);
    }

    @Test
    void reservingPaysOnceAndKeepsTheRoomSpent() {
        var desk = reserving(limits(5_000, 10_000));
        network.loseNextAnswer();
        assertThat(desk.issue(SARAH, ride(1)).status()).isEqualTo("UNKNOWN");
        assertThat(desk.issue(SARAH, ride(1)).status()).isEqualTo("EXECUTED");
        assertThat(payouts()).isEqualTo(1);
        long reserved = db.sql("SELECT reserved_cents FROM allowances"
                        + " WHERE scope = 'CUSTOMER'")
                .query(Long.class).single();
        assertThat(reserved).isEqualTo(1_999);
    }

    @Test
    void theReconcilerRecordsALostAnswerWithoutPaying() {
        var desk = reserving(limits(5_000, 10_000));
        network.loseNextAnswer();
        desk.issue(SARAH, ride(1));
        assertThat(reconciler.settle()).isEqualTo(1);
        assertThat(payouts()).isEqualTo(1);
        String status = db.sql("SELECT status FROM refunds"
                        + " WHERE idempotency_key = 'auto-refund:RIDE-0401'")
                .query(String.class).single();
        assertThat(status).isEqualTo("EXECUTED");
    }

    // Act 4: the kill switch

    @Test
    void theKillSwitchStopsTheMoneyNotJustTheAgent() {
        var desk = reserving(limits(5_000, 10_000));
        killSwitch.engage("test");
        assertThat(desk.issue(SARAH, ride(1)).refusal())
                .isEqualTo("KILL_SWITCH");
        assertThat(paidOut()).isZero();
    }
}
