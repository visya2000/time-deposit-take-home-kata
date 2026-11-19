-- Demo data for manual testing via Swagger; loaded only with the "local" profile.
-- Covers every plan and the main interest boundaries. Repeatable migration (re-applied when this
-- file changes) and idempotent: existing rows are kept (edits to them have no effect on an existing
-- database), so balances updated by the endpoint survive.
-- To re-run interest on the same day: UPDATE time_deposits SET last_interest_applied_on = NULL;

INSERT INTO time_deposits (id, plan_type, days, balance) VALUES
    (1,  'basic',   30,  1000.00),    -- grace period: no interest
    (2,  'basic',   31,  1200.00),    -- first day with interest: +1.00
    (3,  'student', 31,  1200.00),    -- +3.00
    (4,  'student', 365, 1200.00),    -- last day with interest: +3.00
    (5,  'student', 366, 1200.00),    -- after one year: no interest
    (6,  'premium', 45,  1200.00),    -- premium waiting period: no interest
    (7,  'premium', 46,  1200.00),    -- first premium day with interest: +5.00
    (8,  'gold',    100, 5000.00),    -- unknown plan: no interest
    (9,  'basic',   45,  1234567.00), -- large balance: +1028.81
    (10, 'basic',   31,  18.00)       -- double rounding edge: +0.01 (exact decimal maths: +0.02)
ON CONFLICT (id) DO NOTHING;

INSERT INTO withdrawals (id, time_deposit_id, amount, date) VALUES
    (1, 2, 100.00, DATE '2026-11-01'),
    (2, 2, 50.00,  DATE '2026-11-15'),
    (3, 7, 200.00, DATE '2026-11-10')
ON CONFLICT (id) DO NOTHING;

-- Explicit ids do not advance the identity sequences: move them past the seeded ids.
SELECT setval(pg_get_serial_sequence('time_deposits', 'id'), (SELECT MAX(id) FROM time_deposits));
SELECT setval(pg_get_serial_sequence('withdrawals', 'id'), (SELECT MAX(id) FROM withdrawals));
