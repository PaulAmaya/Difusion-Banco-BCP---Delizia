package com.example.defusion_bcp.service;

import com.example.defusion_bcp.domain.*;
import com.example.defusion_bcp.dto.StatementDtos;
import com.example.defusion_bcp.repository.BankStatementRequestRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class StatementRequestStore {
    private final BankStatementRequestRepository repository;
    private final AuditLogService audit;
    private final BankCryptoService crypto;
    private final ObjectMapper mapper;
    private final SensitiveDataMasker masker;
    public StatementRequestStore(BankStatementRequestRepository repository, AuditLogService audit,
                                BankCryptoService crypto, ObjectMapper mapper, SensitiveDataMasker masker) {
        this.repository = repository; this.audit = audit; this.crypto = crypto; this.mapper = mapper; this.masker = masker;
    }
    @Transactional
    public Long reserve(String account, String period, String actor, String company, String correlation,
                        String envelope, String ip) {
        var entity = new BankStatementRequest(account, period, actor, TriggerType.MANUAL, ProcessStatus.PROCESSING,
            correlation, "Consulta BCP en proceso", LocalDateTime.now());
        entity.initializeBankQuery(company, envelope);
        entity = repository.saveAndFlush(entity);
        audit.record(ProcessType.BANK_STATEMENT, "STATEMENT_REQUESTED", "BankStatementRequest", entity.getId().toString(),
            actor, TriggerType.MANUAL, ProcessStatus.PROCESSING, "Consulta BCP para el periodo " + period, correlation, ip);
        return entity.getId();
    }
    @Transactional
    public StatementDtos.Response complete(Long id, String company, StatementDtos.BankResponse result, String ip) {
        var entity = find(id, company);
        entity.complete(result.status(), result.httpStatus(), crypto.encrypt(mapper.writeValueAsString(result)));
        audit.record(ProcessType.BANK_STATEMENT, "STATEMENT_" + result.status(), "BankStatementRequest", id.toString(),
            entity.getRequestedBy(), TriggerType.MANUAL, result.status(), entity.getDetail(), entity.getCorrelationId(), ip);
        return response(entity, true);
    }
    @Transactional(readOnly = true)
    public List<StatementDtos.Response> history(String company) {
        return repository.findTop50ByCompanyDbOrderByRequestedAtDesc(company).stream().map(item -> response(item, false)).toList();
    }
    @Transactional(readOnly = true)
    public StatementDtos.Response detail(Long id, String company) { return response(find(id, company), true); }
    private BankStatementRequest find(Long id, String company) {
        return repository.findByIdAndCompanyDb(id, company).orElseThrow(() ->
            new SapServiceException(HttpStatus.NOT_FOUND, "STATEMENT_NOT_FOUND", "Consulta de extracto no encontrada"));
    }
    private StatementDtos.Response response(BankStatementRequest entity, boolean includeBankResponse) {
        StatementDtos.BankResponse bank = null;
        if (includeBankResponse && entity.getEncryptedResponse() != null) {
            try { bank = mapper.readValue(crypto.decrypt(entity.getEncryptedResponse()), StatementDtos.BankResponse.class); }
            catch (RuntimeException exception) {
                bank = new StatementDtos.BankResponse(entity.getStatus(), entity.getHttpStatus(), null, null,
                    null, null, null, null, "No se pudo abrir la respuesta guardada con el certificado actual.");
            }
        }
        return new StatementDtos.Response(entity.getId(), masker.mask(entity.getAccountNumber()), entity.getPeriod(),
            entity.getRequestedBy(), entity.getTriggerType(), entity.getStatus(), entity.getCorrelationId(),
            entity.getDetail(), entity.getRequestedAt(), entity.getCompletedAt(), entity.getHttpStatus(), bank,
            includeBankResponse ? entity.getEncryptedRequest() : null);
    }
}
