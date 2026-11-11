USE district_one;

CREATE TABLE IF NOT EXISTS payments_receipts (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT UNSIGNED NOT NULL,
    receipt_no VARCHAR(80) NOT NULL UNIQUE,
    payment_date DATE NOT NULL,
    amount DECIMAL(16,2) NOT NULL,
    payment_method VARCHAR(30) NOT NULL,
    receiving_account VARCHAR(180) NULL,
    deposit_reference VARCHAR(120) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'RECOVERY_ENTRY',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_webv9_payment_customer FOREIGN KEY (customer_id) REFERENCES members(member_id),
    INDEX ix_webv9_payments_customer_status (customer_id, status)
) ENGINE=InnoDB;
