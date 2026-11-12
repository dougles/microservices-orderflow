INSERT INTO product (sku, name, base_price, active)
SELECT 'SKU-' || to_char(n, 'FM0000'), 'Producto ' || to_char(n, 'FM0000'), n * 10.00, TRUE
FROM generate_series(1, 20) AS n
ON CONFLICT (sku) DO NOTHING;

INSERT INTO company_discount (company_id, discount_pct)
VALUES ('ACME', 10), ('GLOBEX', 0), ('INITECH', 5)
ON CONFLICT (company_id) DO NOTHING;
