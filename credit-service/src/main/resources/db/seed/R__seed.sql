INSERT INTO credit_line (company_id, credit_limit, used)
VALUES ('ACME', 1000000.00, 0), ('GLOBEX', 500.00, 0)
ON CONFLICT (company_id) DO NOTHING;
