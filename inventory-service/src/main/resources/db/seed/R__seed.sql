INSERT INTO stock (sku, available)
SELECT 'SKU-' || to_char(n, 'FM0000'), 1000
FROM generate_series(1, 20) AS n
ON CONFLICT (sku) DO NOTHING;
