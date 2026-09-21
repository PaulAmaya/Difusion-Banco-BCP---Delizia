CREATE TABLE bank_statement_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_number VARCHAR(40) NOT NULL,
    period VARCHAR(6) NOT NULL,
    requested_by VARCHAR(120) NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(40) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    detail VARCHAR(500) NULL,
    requested_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_statement_correlation (correlation_id),
    KEY idx_statement_period (period),
    KEY idx_statement_requested_at (requested_at),
    KEY idx_statement_requested_by (requested_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_batches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_code VARCHAR(50) NOT NULL,
    source_account VARCHAR(40) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    total_amount DECIMAL(19,2) NOT NULL,
    requested_by VARCHAR(120) NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(40) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    bank_transaction_id VARCHAR(100) NULL,
    detail VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_code (batch_code),
    UNIQUE KEY uk_batch_correlation (correlation_id),
    KEY idx_batch_created_at (created_at),
    KEY idx_batch_status (status),
    KEY idx_batch_requested_by (requested_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_recipients (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    beneficiary_name VARCHAR(180) NOT NULL,
    document_number VARCHAR(40) NOT NULL,
    account_number VARCHAR(40) NOT NULL,
    sap_reference VARCHAR(80) NULL,
    currency VARCHAR(3) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    status VARCHAR(40) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_recipient_batch (batch_id),
    KEY idx_recipient_document (document_number),
    CONSTRAINT fk_recipient_batch FOREIGN KEY (batch_id) REFERENCES payment_batches (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE process_audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    process_type VARCHAR(40) NOT NULL,
    action_name VARCHAR(80) NOT NULL,
    entity_type VARCHAR(60) NOT NULL,
    entity_id VARCHAR(80) NULL,
    actor VARCHAR(120) NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(40) NOT NULL,
    message VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    client_ip VARCHAR(64) NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_occurred_at (occurred_at),
    KEY idx_audit_process_status (process_type, status),
    KEY idx_audit_actor (actor),
    KEY idx_audit_correlation (correlation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
