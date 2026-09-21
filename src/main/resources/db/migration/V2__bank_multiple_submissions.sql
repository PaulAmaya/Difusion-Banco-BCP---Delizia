CREATE TABLE bank_payment_submissions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL UNIQUE,
    company_db VARCHAR(128) NOT NULL,
    requested_by VARCHAR(120) NOT NULL,
    source_account VARCHAR(20) NOT NULL,
    region VARCHAR(2) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    bank_transaction_id VARCHAR(100) NULL,
    http_status INT NULL,
    encrypted_request MEDIUMTEXT NOT NULL,
    encrypted_response MEDIUMTEXT NULL,
    encrypted_message TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    KEY idx_bank_submission_company_date (company_db, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE bank_payment_documents (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    submission_id BIGINT NOT NULL,
    company_db VARCHAR(128) NOT NULL,
    doc_entry BIGINT NOT NULL,
    doc_num BIGINT NOT NULL,
    card_code VARCHAR(50) NOT NULL,
    card_name VARCHAR(200) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    active_key VARCHAR(160) NULL UNIQUE,
    KEY idx_bank_document_company_entry (company_db, doc_entry),
    CONSTRAINT fk_bank_document_submission FOREIGN KEY (submission_id) REFERENCES bank_payment_submissions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
