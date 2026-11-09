CREATE TABLE shipping_capacity (
  delivery_date DATE PRIMARY KEY,
  max_orders    INT NOT NULL CHECK (max_orders >= 0),
  booked        INT NOT NULL DEFAULT 0 CHECK (booked >= 0)
);

-- shipment_id y delivery_date son nulos en las lápidas: CancelShipment solo trae orderId.
CREATE TABLE shipment (
  order_id      UUID PRIMARY KEY,
  shipment_id   UUID,
  delivery_date DATE,
  status        VARCHAR(10) NOT NULL CHECK (status IN ('SCHEDULED', 'CANCELLED')),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
