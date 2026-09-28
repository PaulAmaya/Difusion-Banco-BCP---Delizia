package com.example.defusion_bcp.service;

import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.domain.ProcessType;
import com.example.defusion_bcp.domain.TriggerType;
import com.example.defusion_bcp.dto.BankBatchDetailDtos;
import com.example.defusion_bcp.dto.BankPaymentDtos;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class BankBatchDetailService {
    private static final Logger log = LoggerFactory.getLogger(BankBatchDetailService.class);
    private final BankPaymentStore store;
    private final BankBatchDetailClient bank;
    private final BankCryptoService crypto;
    private final AuditLogService audit;
    private final ObjectMapper mapper;
    private final Integer companyId;
    private final String password;
    private final String documentNumber;
    private final String documentType;
    private final String documentExtension;
    private final String documentComplement;

    public BankBatchDetailService(BankPaymentStore store, BankBatchDetailClient bank, BankCryptoService crypto,
        AuditLogService audit, ObjectMapper mapper,
        @Value("${BCP_COMPANY_ID:2295}") Integer companyId,
        @Value("${BANK_BATCH_DETAIL_PASSWORD:${BANK_DIFFUSION_PASSWORD:}}") String password,
        @Value("${BANK_BATCH_DETAIL_DOCUMENT_NUMBER:${BANK_DIFFUSION_DOCUMENT_NUMBER:}}") String documentNumber,
        @Value("${BANK_BATCH_DETAIL_DOCUMENT_TYPE:${BANK_DIFFUSION_DOCUMENT_TYPE:Q}}") String documentType,
        @Value("${BANK_BATCH_DETAIL_DOCUMENT_EXTENSION:${BANK_DIFFUSION_DOCUMENT_EXTENSION:LP}}") String documentExtension,
        @Value("${BANK_BATCH_DETAIL_DOCUMENT_COMPLEMENT:}") String documentComplement) {
        this.store = store; this.bank = bank; this.crypto = crypto; this.audit = audit; this.mapper = mapper;
        this.companyId = companyId; this.password = password; this.documentNumber = documentNumber;
        this.documentType = documentType; this.documentExtension = documentExtension;
        this.documentComplement = documentComplement;
    }

    public BankBatchDetailDtos.History history(String company, int page) {
        var candidates = store.successfulCandidates(company, page);
        var items = candidates.getContent().stream().filter(item -> confirmedId(item) != null)
            .map(this::summary).toList();
        return new BankBatchDetailDtos.History(items, candidates.getNumber(),
            candidates.getTotalPages(), candidates.getTotalElements());
    }

    public BankBatchDetailDtos.Detail query(Long submissionId, String company, String actor, String ip) {
        var submission = store.findById(submissionId, company).orElseThrow(() ->
            new SapServiceException(HttpStatus.NOT_FOUND, "BANK_SUBMISSION_NOT_FOUND", "Lote no encontrado"));
        Long transactionId = confirmedId(submission);
        if (transactionId == null) throw new SapServiceException(HttpStatus.CONFLICT,
            "BANK_BATCH_DETAIL_NOT_ELIGIBLE", "Solo se consultan lotes enviados exitosamente con ID bancario valido");
        validateHeader();
        String requestId = UUID.randomUUID().toString();
        try (var ignored = MDC.putCloseable("bcpRequestId", requestId);
             var client = bank.prepare()) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("companyId", companyId);
            payload.put("password", password);
            payload.put("documentNumber", documentNumber);
            payload.put("documentType", documentType);
            payload.put("documentExtension", documentExtension);
            payload.put("documentComplement", documentComplement);
            payload.put("transactionsId", List.of(transactionId));
            String data = crypto.encrypt(mapper.writeValueAsString(payload));
            var envelope = new LinkedHashMap<String, Object>();
            envelope.put("companyId", companyId);
            envelope.put("data", data);
            envelope.put("signature", crypto.sign(data));
            log.info("BCP_BATCH_DETAIL_QUERY submissionId={} transactionIdPresent=true plaintextOmitted=true", submissionId);
            var response = bank.send(client, mapper.writeValueAsString(envelope));
            JsonNode body = null;
            String code = null;
            String message = response.decryptedMessage();
            String error = response.error();
            ProcessStatus status = ProcessStatus.FAILED;
            if (Boolean.TRUE.equals(response.isOk()) && response.decryptedBody() != null) {
                try {
                    body = mapper.readTree(response.decryptedBody());
                    code = text(body, "Code");
                    message = text(body, "Message");
                    status = "00".equals(code) ? ProcessStatus.COMPLETED : ProcessStatus.REJECTED;
                } catch (RuntimeException exception) {
                    error = "El body descifrado no es un JSON valido.";
                }
            } else if (Boolean.FALSE.equals(response.isOk()) && error == null) {
                status = ProcessStatus.REJECTED;
            }
            audit.record(ProcessType.MULTIPLE_PAYMENT, "BANK_BATCH_DETAIL_QUERIED", "BankPaymentSubmission",
                submissionId.toString(), actor, TriggerType.MANUAL, status,
                "Consulta de detalle BCP. HTTP: " + response.httpStatus(), submission.requestId(), ip);
            log.info("BCP_BATCH_DETAIL_COMPLETE submissionId={} status={} httpStatus={} bankCode={}",
                submissionId, status, response.httpStatus(), code == null ? "none" : BankNetworkDiagnostics.redact(code));
            return new BankBatchDetailDtos.Detail(summary(submission), status, response.httpStatus(),
                response.isOk(), code, message, body, error, LocalDateTime.now());
        }
    }

    private BankBatchDetailDtos.Summary summary(BankPaymentDtos.SubmissionResponse submission) {
        return new BankBatchDetailDtos.Summary(submission.id(), submission.bankTransactionId(),
            submission.requestedBy(), submission.sourceAccount(), submission.region(), submission.amount(),
            submission.completedAt(), submission.documents());
    }

    private Long confirmedId(BankPaymentDtos.SubmissionResponse submission) {
        if (submission.status() != ProcessStatus.SENT || submission.bankTransactionId() == null
            || !submission.bankTransactionId().matches("[1-9]\\d{0,18}") || submission.decryptedBody() == null) return null;
        try {
            var body = mapper.readTree(submission.decryptedBody());
            if (!"00".equals(text(body, "Code"))
                || !submission.bankTransactionId().equals(text(body, "TransactionId"))) return null;
            return Long.parseLong(submission.bankTransactionId());
        } catch (RuntimeException exception) { return null; }
    }

    private String text(JsonNode node, String key) {
        var value = node == null ? null : node.get(key);
        return value != null && value.isString() ? value.asString().trim() : "";
    }

    private void validateHeader() {
        if (companyId == null || companyId <= 0 || password == null || password.isBlank()
            || documentNumber == null || documentNumber.isBlank()
            || documentType == null || !documentType.matches("[OPQRTUW]")
            || documentExtension == null || !documentExtension.matches("BE|CB|CH|LP|OR|PA|PE|PO|SC|TJ|SN|XX|NN")
            || documentComplement == null || documentComplement.length() > 20) {
            throw new SapServiceException(HttpStatus.BAD_REQUEST, "BANK_BATCH_DETAIL_HEADER_INCOMPLETE",
                "Configure las credenciales y el documento del usuario consultor BCP en el servidor");
        }
    }
}
