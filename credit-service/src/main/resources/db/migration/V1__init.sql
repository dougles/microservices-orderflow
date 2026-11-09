CREATE TABLE credit_line (
  company_id   VARCHAR(100)   PRIMARY KEY,
  credit_limit NUMERIC(14, 2) NOT NULL CHECK (credit_limit >= 0),
  used         NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (used >= 0)
);

-- company_id y amount son nulos en las lápidas: ReleaseCredit solo trae orderId.
CREATE TABLE credit_reservation (
  order_id   UUID PRIMARY KEY,
  company_id VARCHAR(100),
  amount     NUMERIC(14, 2),
  status     VARCHAR(10) NOT NULL CHECK (status IN ('RESERVED', 'RELEASED')),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
