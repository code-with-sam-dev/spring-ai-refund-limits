package com.example.refunds;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The desk that holds. The model proposes; this decides.
 *
 * 1. The kill switch and the payment's own facts come first.
 * 2. One transaction reserves from the customer's and the
 *    merchant's daily allowance, and records the refund. Both fit,
 *    or nothing is reserved.
 * 3. Only after that commits does it call the card network, with a
 *    key that is the same every time this payment is asked about.
 * 4. A lost answer keeps the reservation: the money may have moved,
 *    so the room stays spent until the reconciler knows.
 */
public class ReservingDesk implements RefundDesk {

    private final Payments payments;
    private final Allowances allowances;
    private final CardNetwork network;
    private final KillSwitch killSwitch;
    private final RefundLimits limits;
    private final JdbcClient db;
    private final TransactionTemplate tx;

    public ReservingDesk(Payments payments, Allowances allowances,
                         CardNetwork network, KillSwitch killSwitch,
                         RefundLimits limits, JdbcClient db,
                         TransactionTemplate tx) {
        this.payments = payments;
        this.allowances = allowances;
        this.network = network;
        this.killSwitch = killSwitch;
        this.limits = limits;
        this.db = db;
        this.tx = tx;
    }

    @Override
    public Decision issue(Ticket ticket, RefundProposal p) {
        if (killSwitch.engaged()) {
            return refuse(ticket, p, null, "KILL_SWITCH");
        }
        var found = payments.find(ticket, p.paymentId());
        if (found.isEmpty()) {
            return Decision.refused(p, "UNKNOWN_PAYMENT");
        }
        var payment = found.get();
        var problem = checkPayment(p, payment);
        if (problem != null) {
            return refuse(ticket, p, payment, problem);
        }
        String key = "auto-refund:" + p.paymentId();
        var earlier = status(key);
        if (earlier.isPresent()) {
            return answerAgain(p, key, earlier.get());
        }
        Reservation reserved;
        try {
            reserved = tx.execute(s -> reserve(ticket, p, payment, key));
        } catch (RefusedInside rolledBack) {
            return refuse(ticket, p, payment, rolledBack.reason);
        } catch (DuplicateKeyException sameMomentSamePayment) {
            // Another request for this payment won the insert.
            return answerAgain(p, key, status(key).orElse("RESERVED"));
        }
        if (reserved.refusal() != null) {
            return refuse(ticket, p, payment, reserved.refusal());
        }
        return dispatch(p, payment, key);
    }

    private String checkPayment(RefundProposal p, Payments.Payment pay) {
        if (!p.reasonCode().equals(pay.eligibleReason())) {
            return "NOT_ELIGIBLE";
        }
        if (p.amountCents() <= 0 || p.amountCents() > pay.amountCents()) {
            return "MORE_THAN_PAYMENT";
        }
        if (p.amountCents() >= limits.maxPerRefundCents()) {
            return "OVER_PER_REFUND_LIMIT";
        }
        return null;
    }

    private record Reservation(String refusal) {}

    private Reservation reserve(Ticket ticket, RefundProposal p,
                                Payments.Payment pay, String key) {
        long customerLeft = allowances.reserve("CUSTOMER",
                pay.customerId(), limits.maxCustomerDailyCents(),
                p.amountCents());
        if (customerLeft < 0) {
            return new Reservation("CUSTOMER_DAILY_LIMIT");
        }
        long merchantLeft = allowances.reserve("MERCHANT",
                pay.merchantId(), limits.maxMerchantDailyCents(),
                p.amountCents());
        if (merchantLeft < 0) {
            // Undo the customer reservation with the rest of the work.
            throw new RefusedInside("MERCHANT_DAILY_LIMIT");
        }
        record(ticket, p, pay, key, "RESERVED", null,
                customerLeft, merchantLeft);
        return new Reservation(null);
    }

    private Decision dispatch(RefundProposal p, Payments.Payment pay,
                              String key) {
        if (killSwitch.engaged()) {
            release(key, pay, p.amountCents(), "RELEASED");
            return Decision.refused(p, "KILL_SWITCH");
        }
        try {
            network.refund(key, p.paymentId(), p.amountCents());
        } catch (CardNetwork.Timeout lost) {
            setStatus(key, "UNKNOWN");
            return Decision.unknown(p);
        }
        setStatus(key, "EXECUTED");
        return Decision.paid(p);
    }

    /** The same proposal again, after a timeout or a retry. */
    private Decision answerAgain(RefundProposal p, String key,
                                 String status) {
        if ("UNKNOWN".equals(status) && network.paid(key)) {
            setStatus(key, "EXECUTED");
            return Decision.paid(p);
        }
        return switch (status) {
            case "EXECUTED" -> Decision.paid(p);
            case "UNKNOWN", "RESERVED" -> Decision.unknown(p);
            default -> Decision.refused(p, "ALREADY_DECIDED");
        };
    }

    void release(String key, Payments.Payment pay, long cents,
                 String status) {
        tx.executeWithoutResult(s -> {
            allowances.release("CUSTOMER", pay.customerId(), cents);
            allowances.release("MERCHANT", pay.merchantId(), cents);
            setStatus(key, status);
        });
    }

    private Decision refuse(Ticket ticket, RefundProposal p,
                            Payments.Payment pay, String why) {
        if (pay != null) {
            record(ticket, p, pay, "refused:" + UUID.randomUUID(),
                    "REFUSED", why, null, null);
        }
        return Decision.refused(p, why);
    }

    private Optional<String> status(String key) {
        return db.sql("SELECT status FROM refunds"
                        + " WHERE idempotency_key = :k")
                .param("k", key)
                .query(String.class)
                .optional();
    }

    private void setStatus(String key, String status) {
        db.sql("""
                UPDATE refunds SET status = :s, updated_at = now()
                WHERE idempotency_key = :k""")
                .param("s", status).param("k", key)
                .update();
    }

    private void record(Ticket ticket, RefundProposal p,
                        Payments.Payment pay, String key, String status,
                        String refusal, Long customerLeft,
                        Long merchantLeft) {
        db.sql("""
                INSERT INTO refunds (id, idempotency_key, payment_id,
                    customer_id, merchant_id, amount_cents, reason_code,
                    status, refusal, ticket_agent, policy_version,
                    customer_left, merchant_left)
                VALUES (:id, :key, :payment, :customer, :merchant,
                    :cents, :reason, :status, :refusal, :agent,
                    :policy, :cLeft, :mLeft)""")
                .param("id", UUID.randomUUID())
                .param("key", key)
                .param("payment", p.paymentId())
                .param("customer", pay.customerId())
                .param("merchant", pay.merchantId())
                .param("cents", p.amountCents())
                .param("reason", p.reasonCode())
                .param("status", status)
                .param("refusal", refusal)
                .param("agent", ticket.agent())
                .param("policy", limits.policyVersion())
                .param("cLeft", customerLeft)
                .param("mLeft", merchantLeft)
                .update();
    }

    /** Rolls back the transaction it is thrown in. */
    static class RefusedInside extends RuntimeException {
        final String reason;

        RefusedInside(String reason) {
            super(reason);
            this.reason = reason;
        }
    }
}
