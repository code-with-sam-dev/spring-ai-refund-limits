-- One customer, one merchant, and the payments a support agent may refund.
-- All data is fictional. Amounts are US cents.

CREATE TABLE payments (
    id              text PRIMARY KEY,
    customer_id     text   NOT NULL,
    merchant_id     text   NOT NULL,
    amount_cents    bigint NOT NULL CHECK (amount_cents > 0),
    currency        text   NOT NULL DEFAULT 'USD',
    -- Set by the system that knows what happened, never by the agent:
    -- the trip system marks a ride the driver never showed up for.
    eligible_reason text,
    captured_at     timestamptz NOT NULL
);

-- Every refund the desk decided on, paid or not, and why.
CREATE TABLE refunds (
    id               uuid PRIMARY KEY,
    idempotency_key  text   NOT NULL UNIQUE,
    payment_id       text   NOT NULL REFERENCES payments (id),
    customer_id      text   NOT NULL,
    merchant_id      text   NOT NULL,
    amount_cents     bigint NOT NULL,
    reason_code      text   NOT NULL,
    status           text   NOT NULL,   -- RESERVED, EXECUTED, UNKNOWN, RELEASED, REFUSED
    refusal          text,
    ticket_agent     text   NOT NULL,
    policy_version   text   NOT NULL,
    customer_left    bigint,             -- allowance left after this decision
    merchant_left    bigint,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

-- What may still be refunded today, per customer and per merchant. A refund
-- reserves from both rows in the same transaction, or from neither.
CREATE TABLE allowances (
    scope          text   NOT NULL,   -- CUSTOMER or MERCHANT
    scope_id       text   NOT NULL,
    business_day   date   NOT NULL,
    limit_cents    bigint NOT NULL,
    reserved_cents bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (scope, scope_id, business_day),
    CHECK (reserved_cents <= limit_cents)
);

-- The fake card network's own ledger: every row is money that moved.
-- Like a real provider, a repeated idempotency key does not pay twice.
CREATE TABLE provider_payouts (
    idempotency_key text PRIMARY KEY,
    payment_id      text   NOT NULL,
    amount_cents    bigint NOT NULL,
    paid_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE kill_switch (
    id      int PRIMARY KEY CHECK (id = 1),
    engaged boolean NOT NULL DEFAULT false,
    reason  text
);
INSERT INTO kill_switch (id, engaged) VALUES (1, false);
