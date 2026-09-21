package com.example.defusion_bcp.service;

import com.example.defusion_bcp.domain.*;
import com.example.defusion_bcp.dto.*;
import com.example.defusion_bcp.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
public class BankPaymentStore {
    private final BankPaymentSubmissionRepository submissions;
    private final BankPaymentDocumentRepository documents;
    private final AuditLogService audit;
    private final BankCryptoService crypto;
    private final tools.jackson.databind.ObjectMapper mapper;
    private final com.example.defusion_bcp.config.BankDocumentReversalPolicy reversalPolicy;
    public BankPaymentStore(BankPaymentSubmissionRepository submissions, BankPaymentDocumentRepository documents,
                            AuditLogService audit, BankCryptoService crypto, tools.jackson.databind.ObjectMapper mapper,
                            com.example.defusion_bcp.config.BankDocumentReversalPolicy reversalPolicy) {
        this.submissions = submissions; this.documents = documents; this.audit = audit; this.crypto = crypto;
        this.mapper = mapper;
        this.reversalPolicy = reversalPolicy;
    }
    @Transactional(readOnly = true)
    public Optional<BankPaymentDtos.SubmissionResponse> find(String requestId, String company) {
        return submissions.findByRequestIdAndCompanyDb(requestId, company).map(this::response);
    }
    @Transactional(readOnly = true)
    public List<BankPaymentDtos.SubmissionResponse> history(String company) {
        return submissions.findTop50ByCompanyDbOrderByCreatedAtDesc(company).stream().map(this::response).toList();
    }
    @Transactional(readOnly = true)
    public Set<Long> blockedIds(String company) { return documents.blockedIds(company); }

    @Transactional
    public BankPaymentDtos.SubmissionResponse release(Long id, String company, String actor,
        BankPaymentDtos.ReleaseRequest request, String ip) {
        if (!reversalPolicy.allowed()) {
            throw new SapServiceException(org.springframework.http.HttpStatus.FORBIDDEN, "BANK_REVERSAL_DISABLED",
                "La reversion local esta deshabilitada. Esta opcion temporal solo puede habilitarse en el sandbox de BCP.");
        }
        if (!request.acknowledgeDuplicateRisk() || request.reason() == null || request.reason().isBlank()
            || request.reason().length() > 500) {
            throw new SapServiceException(org.springframework.http.HttpStatus.BAD_REQUEST, "RELEASE_CONFIRMATION_REQUIRED",
                "Confirme el riesgo de duplicar el pago y registre un motivo");
        }
        var submission = submissions.lockForRelease(id, company).orElseThrow(() ->
            new SapServiceException(org.springframework.http.HttpStatus.NOT_FOUND, "BANK_SUBMISSION_NOT_FOUND", "Lote no encontrado"));
        if ((submission.getStatus() != ProcessStatus.SENT && submission.getStatus() != ProcessStatus.UNKNOWN)
            || submission.getCompletedAt() == null) {
            throw new SapServiceException(org.springframework.http.HttpStatus.CONFLICT, "BANK_RELEASE_NOT_ALLOWED",
                "No se puede liberar un lote que aun esta procesando. Espere a que termine el intento de envio.");
        }
        if (submission.getReleasedAt() != null) return response(submission);
        submission.releaseLocally(actor, request.reason().trim());
        audit.record(ProcessType.MULTIPLE_PAYMENT, "BANK_DOCUMENTS_RELEASED_LOCALLY", "BankPaymentSubmission",
            id.toString(), actor, TriggerType.MANUAL, ProcessStatus.COMPLETED,
            "Documentos liberados localmente para pruebas. No cancela el lote BCP ni modifica SAP."
                + " Opcion temporal sandbox, sin exigir conciliacion. Estado bancario conservado: " + submission.getStatus(),
            submission.getRequestId(), ip);
        for (var document : submission.getDocuments()) {
            audit.record(ProcessType.MULTIPLE_PAYMENT, "BANK_DOCUMENT_RELEASED_LOCALLY", "VendorPayments",
                Long.toString(document.snapshot().docEntry()), actor, TriggerType.MANUAL, ProcessStatus.COMPLETED,
                "Documento habilitado nuevamente en pendientes. Existe riesgo de duplicar el pago al reenviar.",
                submission.getRequestId(), ip);
        }
        return response(submission);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long reserve(String requestId, SapSession session, String actor, String sourceAccount,
                        DiffusionDtos.PreviewResponse preview, String encryptedRequest, String ip) {
        var submission = submissions.saveAndFlush(new BankPaymentSubmission(requestId, session.companyDb(), actor,
            sourceAccount, preview, encryptedRequest));
        audit.record(ProcessType.MULTIPLE_PAYMENT, "BANK_MULTIPLE_RESERVED", "BankPaymentSubmission",
            submission.getId().toString(), actor, TriggerType.MANUAL, ProcessStatus.PROCESSING,
            "Lote de pruebas reservado con " + preview.documents().size() + " documentos SAP", requestId, ip);
        for (Long docEntry : preview.docEntries()) {
            audit.record(ProcessType.MULTIPLE_PAYMENT, "BANK_DOCUMENT_RESERVED", "VendorPayments", docEntry.toString(),
                actor, TriggerType.MANUAL, ProcessStatus.PROCESSING, "Documento reservado para envio sandbox", requestId, ip);
        }
        return submission.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BankPaymentDtos.SubmissionResponse complete(Long id, String company, BankPaymentClient.Result result, String ip) {
        var submission = submissions.findByIdAndCompanyDb(id, company).orElseThrow();
        // Store confidential bank responses encrypted; audit messages contain only operational metadata.
        submission.complete(result.status(), result.transactionId(), result.httpStatus(),
            result.rawResponse().isBlank() ? null : crypto.encrypt(result.rawResponse()), crypto.encrypt(result.message()));
        audit.record(ProcessType.MULTIPLE_PAYMENT, "BANK_MULTIPLE_" + result.status(), "BankPaymentSubmission",
            id.toString(), submission.getRequestedBy(), TriggerType.MANUAL, result.status(),
            "Resultado del envio sandbox: " + result.status() + ". HTTP: " + result.httpStatus(), submission.getRequestId(), ip);
        for (var document : submission.getDocuments()) {
            audit.record(ProcessType.MULTIPLE_PAYMENT, "BANK_DOCUMENT_" + result.status(), "VendorPayments",
                Long.toString(document.snapshot().docEntry()), submission.getRequestedBy(), TriggerType.MANUAL,
                result.status(), "Documento SAP: resultado sandbox " + result.status(), submission.getRequestId(), ip);
        }
        return response(submission);
    }

    private BankPaymentDtos.SubmissionResponse response(BankPaymentSubmission submission) {
        String message = "Envio en proceso o sin cierre confirmado. No reenviar; revisar el historial y conciliar.";
        if (submission.getEncryptedMessage() != null) {
            try { message = crypto.decrypt(submission.getEncryptedMessage()); }
            catch (CryptoOperationException exception) { message = "Mensaje guardado no disponible con el certificado actual"; }
        }
        var bankResponse = readBankResponse(submission);
        return new BankPaymentDtos.SubmissionResponse(submission.getId(), submission.getRequestId(),
            submission.getRequestedBy(), submission.getSourceAccount(), submission.getRegion(), submission.getAmount(),
            submission.getStatus(), submission.getBankTransactionId(), submission.getHttpStatus(), message,
            submission.getCreatedAt(), submission.getCompletedAt(), submission.getDocuments().stream()
                .map(BankPaymentDocument::snapshot).toList(), bankResponse.decryptedBody(),
            submission.getReleasedAt(), submission.getReleasedBy(), submission.getReleaseReason(), bankResponse.info(),
            reversalPolicy.allowed() && submission.getReleasedAt() == null && submission.getCompletedAt() != null
                && (submission.getStatus() == ProcessStatus.SENT || submission.getStatus() == ProcessStatus.UNKNOWN)
                && !submission.getDocuments().isEmpty());
    }

    private record ResponseData(String decryptedBody, BankPaymentDtos.BankResponseInfo info) {}
    private ResponseData readBankResponse(BankPaymentSubmission submission) {
        if (submission.getEncryptedResponse() == null) {
            return new ResponseData(null, new BankPaymentDtos.BankResponseInfo(false, null, null,
                "No se recibio ni se guardo una respuesta HTTP del banco. No existe un body para desencriptar."));
        }
        String raw;
        try {
            raw = crypto.decrypt(submission.getEncryptedResponse());
        } catch (RuntimeException exception) {
            return new ResponseData(null, new BankPaymentDtos.BankResponseInfo(true, null, null,
                "No se pudo abrir la respuesta guardada con el certificado actual."));
        }
        String encryptedBody;
        try {
            var body = mapper.readTree(raw).path("body");
            encryptedBody = body.isString() ? body.asString() : null;
        } catch (RuntimeException exception) {
            return new ResponseData(null, new BankPaymentDtos.BankResponseInfo(true, raw, null,
                "La respuesta bancaria no es un JSON valido. Revise la respuesta original."));
        }
        if (encryptedBody == null || encryptedBody.isBlank()) {
            return new ResponseData(null, new BankPaymentDtos.BankResponseInfo(true, raw, encryptedBody,
                "La respuesta bancaria no contiene un body encriptado."));
        }
        try {
            return new ResponseData(crypto.decrypt(encryptedBody), new BankPaymentDtos.BankResponseInfo(true, raw, encryptedBody, null));
        } catch (RuntimeException exception) {
            return new ResponseData(null, new BankPaymentDtos.BankResponseInfo(true, raw, encryptedBody,
                "El banco devolvio un body, pero no se pudo desencriptar con el certificado BUSINESS configurado."));
        }
    }
}
