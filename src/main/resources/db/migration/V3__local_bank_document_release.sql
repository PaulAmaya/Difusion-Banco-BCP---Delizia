ALTER TABLE bank_payment_submissions
    ADD COLUMN released_at DATETIME(6) NULL,
    ADD COLUMN released_by VARCHAR(120) NULL,
    ADD COLUMN release_reason VARCHAR(500) NULL;
