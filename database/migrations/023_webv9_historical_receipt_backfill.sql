USE district_one;

-- Add index for efficient queries on payments_receipts for verified unposted receipts
-- This improves performance of the customer ledger API which queries historical payments
ALTER TABLE payments_receipts
    ADD INDEX ix_webv9_payments_verified_unposted (status, posted_at, customer_id);

INSERT IGNORE INTO webv9_migrations(version, description)
VALUES ('023', 'WEBV9 payments_receipts index for customer ledger API');
