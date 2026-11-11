USE district_one;

-- Structured Receipt Entry metadata. Nullable to preserve historical receipts;
-- no inference or backfill is performed by this migration.
ALTER TABLE payments_receipts
    ADD COLUMN payment_reference VARCHAR(120) NULL,
    ADD COLUMN sales_classification VARCHAR(20) NULL,
    ADD COLUMN bank_instrument_type VARCHAR(24) NULL;

INSERT IGNORE INTO webv9_migrations(version, description)
VALUES ('025', 'WEBV9 structured receipt sales classification and bank instrument type');
