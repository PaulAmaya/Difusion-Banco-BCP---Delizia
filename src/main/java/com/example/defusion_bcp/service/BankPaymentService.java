package com.example.defusion_bcp.service;

import com.example.defusion_bcp.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@Service
public class BankPaymentService {
    private static final Logger log = LoggerFactory.getLogger(BankPaymentService.class);
    private final DiffusionPreviewService preview;
    private final BankPaymentClient bank;
    private final BankPaymentStore store;
    private final BankCryptoService crypto;
    private final ObjectMapper mapper;
    public BankPaymentService(DiffusionPreviewService preview, BankPaymentClient bank, BankPaymentStore store,
                              BankCryptoService crypto, ObjectMapper mapper) {
        this.preview = preview; this.bank = bank; this.store = store; this.crypto = crypto; this.mapper = mapper;
    }
    public BankPaymentDtos.SubmissionResponse send(SapSession session, DiffusionDtos.SendRequest request,
                                                  String actor, String ip) {
        String requestId;
        try { requestId = UUID.fromString(request.requestId()).toString(); }
        catch (IllegalArgumentException exception) {
            log.warn("BCP_SEND_REJECTED stage=REQUEST_ID code=BANK_SEND_INVALID bankPostAttempted=false");
            throw invalid("Identificador de envio invalido");
        }
        try (var context = MDC.putCloseable("bcpRequestId", requestId)) {
            return sendTracked(session, request, actor, ip, requestId);
        }
    }

    private BankPaymentDtos.SubmissionResponse sendTracked(SapSession session, DiffusionDtos.SendRequest request,
        String actor, String ip, String requestId) {
        long started = System.nanoTime();
        String stage = "IDEMPOTENCY";
        boolean postAttempted = false;
        Long submissionId = null;
        log.info("BCP_SEND_START requestId={} stage={}", requestId, stage);
        try {
            var existing = store.find(requestId, session.companyDb());
            if (existing.isPresent()) {
                log.info("BCP_SEND_REUSED submissionId={} status={} bankPostAttempted=false", existing.get().id(), existing.get().status());
                return existing.get();
            }
            stage = "SAP_PREVIEW";
            log.info("BCP_STAGE_START stage={}", stage);
            var current = preview.preview(session, request.selection());
            log.info("BCP_STAGE_OK stage={} documentCount={}", stage, current.documents().size());
            stage = "PREVIEW_VALIDATION";
            if (!current.fingerprint().equals(request.fingerprint())) {
                throw invalid("SAP o la configuracion cambiaron desde la previsualizacion. Genere el JSON y confirme de nuevo");
            }
            preview.validateForSending(current);
            if (session.companyDb() == null || session.companyDb().isBlank() || session.companyDb().length() > 128) {
                throw invalid("Sesion SAP sin empresa valida");
            }
            log.info("BCP_STAGE_OK stage={}", stage);
            stage = "TLS_CLIENT_INIT";
            log.info("BCP_STAGE_START stage={}", stage);
            try (var client = bank.createClient()) {
                log.info("BCP_STAGE_OK stage={}", stage);
                stage = "JSON_ENCRYPT";
                var data = crypto.encrypt(mapper.writeValueAsString(current.payload()));
                log.info("BCP_STAGE_OK stage={} cipherBase64Length={} plaintextOmitted=true", stage, data.length());
                stage = "CIPHERTEXT_SIGN";
                var envelope = new LinkedHashMap<String, Object>();
                envelope.put("companyId", current.payload().get("companyId"));
                var signature = crypto.sign(data);
                envelope.put("data", data); envelope.put("signature", signature);
                String encryptedRequest = mapper.writeValueAsString(envelope);
                log.info("BCP_STAGE_OK stage={} signatureBase64Length={} envelopeFields=companyId,data,signature payloadOmitted=true", stage, signature.length());
                stage = "DOCUMENT_RESERVATION";
                Long id;
                try {
                    id = store.reserve(requestId, session, actor, request.selection().sourceAccount(), current, encryptedRequest, ip);
                } catch (DataIntegrityViolationException exception) {
                    var duplicate = store.find(requestId, session.companyDb());
                    if (duplicate.isPresent()) {
                        log.info("BCP_SEND_REUSED submissionId={} stage={} bankPostAttempted=false", duplicate.get().id(), stage);
                        return duplicate.get();
                    }
                    throw new SapServiceException(HttpStatus.CONFLICT, "BANK_DOCUMENT_BLOCKED",
                        "Uno de los documentos ya fue enviado o esta reservado. Actualice SAP y revise el historial; no reenviar");
                }
                submissionId = id;
                log.info("BCP_STAGE_OK stage={} submissionId={} documentCount={}", stage, id, current.documents().size());
                // Reservation is committed before the only POST. Never retry an ambiguous network result.
                stage = "BANK_HTTP_POST";
                postAttempted = true;
                var result = bank.send(client, encryptedRequest);
                log.info("BCP_STAGE_RESULT stage={} submissionId={} status={} httpStatus={}", stage, id, result.status(), result.httpStatus());
                stage = "RESULT_PERSISTENCE";
                var response = store.complete(id, session.companyDb(), result, ip);
                log.info("BCP_SEND_COMPLETE submissionId={} status={} httpStatus={} elapsedMs={}", id, result.status(), result.httpStatus(),
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                return response;
            }
        } catch (RuntimeException exception) {
            log.error("BCP_SEND_FAILED stage={} submissionId={} bankPostAttempted={} elapsedMs={} errorCode={} exceptionType={} causeType={} exceptionMessageOmitted=true",
                stage, submissionId, postAttempted, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                exception instanceof SapServiceException sap ? sap.getCode() : "INTERNAL_OR_CRYPTO",
                exception.getClass().getName(), exception.getCause() == null ? "none" : exception.getCause().getClass().getName());
            throw exception;
        }
    }
    private SapServiceException invalid(String message) {
        return new SapServiceException(HttpStatus.BAD_REQUEST, "BANK_SEND_INVALID", message);
    }
}
