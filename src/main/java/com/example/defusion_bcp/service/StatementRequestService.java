package com.example.defusion_bcp.service;

import com.example.defusion_bcp.dto.StatementDtos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class StatementRequestService {
    private static final Logger log = LoggerFactory.getLogger(StatementRequestService.class);
    private final StatementRequestStore store;
    private final BankStatementClient bank;
    private final BankCryptoService crypto;
    private final ObjectMapper mapper;
    private final BankPaymentClient payments;
    private final DiffusionPreviewService diffusion;
    private final StatementDtos.BankPayload defaults;
    @org.springframework.beans.factory.annotation.Value("${BANK_DIFFUSION_DOCUMENT_TYPE:Q}")
    private String documentType = "Q";
    @org.springframework.beans.factory.annotation.Value("${BANK_DIFFUSION_DOCUMENT_EXTENSION:LP}")
    private String documentExtension = "LP";
    public StatementRequestService(StatementRequestStore store, BankStatementClient bank, BankCryptoService crypto,
        ObjectMapper mapper, BankPaymentClient payments, DiffusionPreviewService diffusion,
        @Value("${BCP_COMPANY_ID:2295}") Integer companyId,
        @Value("${BANK_STATEMENT_PASSWORD:${BANK_DIFFUSION_PASSWORD:}}") String password,
        @Value("${BANK_STATEMENT_DOCUMENT_NUMBER:${BANK_DIFFUSION_DOCUMENT_NUMBER:}}") String documentNumber) {
        this.store = store; this.bank = bank; this.crypto = crypto; this.mapper = mapper;
        this.payments = payments; this.diffusion = diffusion;
        this.defaults = new StatementDtos.BankPayload(companyId, password, documentNumber, "Q", "LP", "", "");
    }
    public StatementDtos.Configuration configuration() {
        var accounts = diffusion.catalogs().sourceAccounts().stream()
            .map(account -> new StatementDtos.Account(account.name(), account.bankAccount())).toList();
        String period = java.time.LocalDate.now(java.time.ZoneId.of("America/La_Paz"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        return new StatementDtos.Configuration(accounts.isEmpty() ? "" : accounts.getFirst().accountNumber(), period, accounts, payments.availability());
    }
    public StatementDtos.Response query(StatementDtos.QueryRequest request, String actor, String company, String ip) {
        if (diffusion.catalogs().sourceAccounts().stream().noneMatch(account -> account.bankAccount().equals(request.accountNumber()))) {
            throw new SapServiceException(org.springframework.http.HttpStatus.BAD_REQUEST, "BANK_STATEMENT_ACCOUNT_INVALID", "Seleccione BCP LP o BCP SC");
        }
        var payload = new StatementDtos.BankPayload(defaults.companyId(), defaults.password(), defaults.documentNumber(),
            documentType, documentExtension, request.accountNumber(), request.period());
        try (var validator = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            if (!validator.getValidator().validate(payload).isEmpty()) {
                throw new SapServiceException(org.springframework.http.HttpStatus.BAD_REQUEST, "BANK_STATEMENT_HEADER_INCOMPLETE",
                    "Configure las credenciales genericas y el documento BCP en el servidor");
            }
        }
        return create(new StatementDtos.CreateRequest(payload), actor, company, ip);
    }
    public StatementDtos.Response create(StatementDtos.CreateRequest request, String actor, String company, String ip) {
        String correlation = UUID.randomUUID().toString();
        String stage = "TLS_CLIENT_INIT";
        Long id = null;
        try (var ignored = MDC.putCloseable("bcpRequestId", correlation); var client = bank.prepare()) {
            stage = "ENCRYPT_AND_SIGN";
            String data = crypto.encrypt(mapper.writeValueAsString(request.payload()));
            var envelope = new LinkedHashMap<String, Object>();
            envelope.put("companyId", request.payload().companyId());
            envelope.put("data", data); envelope.put("signature", crypto.sign(data));
            String serialized = mapper.writeValueAsString(envelope);
            stage = "SAVE_REQUEST";
            id = store.reserve(request.payload().accountNumber(), request.payload().period(), actor, company, correlation, serialized, ip);
            stage = "BANK_HTTP_POST";
            log.info("BCP_EXTRACTS_SEND requestId={} statementId={} stage={} plaintextOmitted=true", correlation, id, stage);
            var result = bank.send(client, serialized);
            stage = "SAVE_RESULT";
            var response = store.complete(id, company, result, ip);
            log.info("BCP_EXTRACTS_COMPLETE statementId={} status={} httpStatus={} isOk={}", id, result.status(), result.httpStatus(), result.isOk());
            return response;
        } catch (RuntimeException exception) {
            log.error("BCP_EXTRACTS_FAILED requestId={} statementId={} stage={} exceptionType={} messageOmitted=true",
                correlation, id, stage, exception.getClass().getName());
            throw exception;
        }
    }
    public List<StatementDtos.Response> listRecent(String company) { return store.history(company); }
    public StatementDtos.Response detail(Long id, String company) { return store.detail(id, company); }
}
