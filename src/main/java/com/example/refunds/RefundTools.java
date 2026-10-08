package com.example.refunds;

import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The agent's tools. issue_small_refund pays with nobody approving
 * it: the authority a person used to hold now lives in the desk.
 */
@Component
public class RefundTools {

    private final RefundDesk desk;
    private final JdbcClient db;

    public RefundTools(RefundDesk desk, JdbcClient db) {
        this.desk = desk;
        this.db = db;
    }

    public record PaymentLine(
            String id, long amountCents, String eligibleReason) {}

    @McpTool(name = "list_payments",
            description = "This customer's recent payments, with any "
                    + "refund reason the trip system recorded.")
    public List<PaymentLine> listPayments() {
        return db.sql("""
                SELECT id, amount_cents, eligible_reason FROM payments
                WHERE customer_id = :c ORDER BY captured_at DESC""")
                .param("c", Ticket.current().customerId())
                .query(PaymentLine.class)
                .list();
    }

    @McpTool(name = "issue_small_refund",
            description = "Refund part or all of one payment. Small "
                    + "refunds are paid at once, with no approval.")
    public Decision issueSmallRefund(
            @McpToolParam(description = "Payment id, e.g. RIDE-0412")
            String paymentId,
            @McpToolParam(description = "Amount in US cents")
            long amountCents,
            @McpToolParam(description = "Reason code, e.g. DRIVER_NO_SHOW")
            String reasonCode) {
        return desk.issue(Ticket.current(),
                new RefundProposal(paymentId, amountCents, reasonCode));
    }
}
