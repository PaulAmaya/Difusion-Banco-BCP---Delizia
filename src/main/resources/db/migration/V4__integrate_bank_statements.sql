ALTER TABLE bank_statement_requests ADD COLUMN company_db VARCHAR(120) NULL;
ALTER TABLE bank_statement_requests ADD COLUMN http_status INTEGER NULL;
ALTER TABLE bank_statement_requests ADD COLUMN encrypted_request MEDIUMTEXT NULL;
ALTER TABLE bank_statement_requests ADD COLUMN encrypted_response MEDIUMTEXT NULL;
CREATE INDEX idx_statement_company_requested ON bank_statement_requests (company_db, requested_at);
