CREATE TABLE stock (
  sku       VARCHAR(32) PRIMARY KEY,
  available INT NOT NULL CHECK (available >= 0)
);

CREATE TABLE reservation (
  order_id   UUID PRIMARY KEY,
  status     VARCHAR(10) NOT NULL CHECK (status IN ('RESERVED', 'RELEASED')),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservation_item (
  order_id UUID        NOT NULL REFERENCES reservation (order_id),
  sku      VARCHAR(32) NOT NULL REFERENCES stock (sku),
  quantity INT         NOT NULL CHECK (quantity > 0),
  PRIMARY KEY (order_id, sku)
);
