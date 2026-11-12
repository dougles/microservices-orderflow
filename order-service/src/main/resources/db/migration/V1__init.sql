CREATE TABLE orders (
  id            UUID          PRIMARY KEY,
  company_id    VARCHAR(50)   NOT NULL,
  delivery_date DATE          NOT NULL,
  currency      VARCHAR(3)    NOT NULL,
  total         NUMERIC(14,2) NOT NULL,
  created_at    TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_orders_created ON orders (created_at DESC);
CREATE INDEX idx_orders_company_created ON orders (company_id, created_at DESC);

CREATE TABLE order_line (
  order_id   UUID          NOT NULL REFERENCES orders(id),
  sku        VARCHAR(32)   NOT NULL,
  quantity   INT           NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
  unit_price NUMERIC(12,2) NOT NULL,
  PRIMARY KEY (order_id, sku)
);

CREATE TABLE idempotency_key (
  company_id      VARCHAR(50) NOT NULL,
  idempotency_key UUID        NOT NULL,
  request_hash    CHAR(64)    NOT NULL,
  response_status INT         NOT NULL,
  response_body   TEXT        NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (company_id, idempotency_key)
);
CREATE INDEX idx_idempotency_created ON idempotency_key (created_at);
