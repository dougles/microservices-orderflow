CREATE TABLE saga (
  order_id                UUID PRIMARY KEY REFERENCES orders(id),
  state                   VARCHAR(30) NOT NULL,
  failure_reason          VARCHAR(40),
  step_deadline           TIMESTAMPTZ,
  stock_reserved          BOOLEAN NOT NULL DEFAULT false,
  credit_reserved         BOOLEAN NOT NULL DEFAULT false,
  stock_release_pending   BOOLEAN NOT NULL DEFAULT false,
  credit_release_pending  BOOLEAN NOT NULL DEFAULT false,
  shipment_cancel_pending BOOLEAN NOT NULL DEFAULT false,
  compensation_attempts   INT     NOT NULL DEFAULT 0,
  updated_at              TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_saga_deadline ON saga (step_deadline)
  WHERE state IN ('AWAITING_STOCK','AWAITING_CREDIT','AWAITING_SHIPMENT','COMPENSATING');
