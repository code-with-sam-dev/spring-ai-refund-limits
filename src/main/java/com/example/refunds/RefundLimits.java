package com.example.refunds;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The authority a person used to hold, written down. The agent
 * cannot read or change these; they are server configuration.
 */
@ConfigurationProperties("refunds.limits")
public record RefundLimits(
        long maxPerRefundCents,
        long maxCustomerDailyCents,
        long maxMerchantDailyCents,
        String policyVersion) {}
