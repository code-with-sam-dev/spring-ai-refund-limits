package com.example.refunds;

/**
 * Decides a refund the agent proposed, with no person approving it.
 * Three implementations: two naive ones the video breaks, and the
 * one that holds.
 */
public interface RefundDesk {

    Decision issue(Ticket ticket, RefundProposal proposal);
}
