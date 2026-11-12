INSERT INTO shipping_capacity (delivery_date, max_orders, booked)
SELECT current_date + n, 100, 0
FROM generate_series(0, 90) AS n
ON CONFLICT (delivery_date) DO NOTHING;
