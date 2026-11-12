CREATE TABLE product (
  sku        VARCHAR(32)   PRIMARY KEY CHECK (sku ~ '^[A-Z0-9-]{3,32}$'),
  name       VARCHAR(200)  NOT NULL CHECK (length(name) >= 1 AND position(',' IN name) = 0),
  base_price NUMERIC(12,2) NOT NULL CHECK (base_price > 0),
  active     BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE TABLE company_discount (
  company_id   VARCHAR(50)  PRIMARY KEY,
  discount_pct NUMERIC(5,2) NOT NULL CHECK (discount_pct BETWEEN 0 AND 50)
);
