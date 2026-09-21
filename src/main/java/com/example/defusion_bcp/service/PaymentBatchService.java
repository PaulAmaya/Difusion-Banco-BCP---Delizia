package com.example.defusion_bcp.service;

import com.example.defusion_bcp.domain.*;
import com.example.defusion_bcp.dto.PaymentDtos;
import com.example.defusion_bcp.repository.PaymentBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PaymentBatchService {
    private final PaymentBatchRepository repository;
    private final AuditLogService auditLogService;
    private final SensitiveDataMasker masker;

    public PaymentBatchService(PaymentBatchRepository repository,
                               AuditLogService auditLogService,
                               SensitiveDataMasker masker) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.masker = masker;
    }

    @Transactional
    public PaymentDtos.BatchResponse create(PaymentDtos.CreateBatchRequest request,
                                            String authenticatedUser, String clientIp) {
        TriggerType triggerType = request.triggerType() == null ? TriggerType.MANUAL : request.triggerType();
        String actor = triggerType == TriggerType.AUTOMATIC ? "SYSTEM" : authenticatedUser;
        String correlationId = UUID.randomUUID().toString();
        String currency = request.currency().toUpperCase(Locale.ROOT);
        BigDecimal total = request.recipients().stream()
            .map(PaymentDtos.RecipientRequest::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        String batchCode = "LOT-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            + "-" + correlationId.substring(0, 4).toUpperCase(Locale.ROOT);

        PaymentBatch newBatch = new PaymentBatch(
            batchCode, request.sourceAccount().trim(), currency, total, actor, triggerType,
            ProcessStatus.PENDING_INTEGRATION, correlationId,
            "Lote registrado. Pendiente de integrar cifrado, firma y envío al banco.",
            LocalDateTime.now()
        );

        request.recipients().forEach(item -> newBatch.addRecipient(new PaymentRecipient(
            item.beneficiaryName().trim(), item.documentNumber().trim(), item.accountNumber().trim(),
            item.sapReference(), currency, item.amount(), ProcessStatus.PENDING_INTEGRATION
        )));

        PaymentBatch batch = repository.save(newBatch);
        auditLogService.record(
            ProcessType.MULTIPLE_PAYMENT, "PAYMENT_BATCH_CREATED", "PaymentBatch",
            batch.getId().toString(), actor, triggerType, batch.getStatus(),
            "Lote " + batch.getBatchCode() + " registrado con " + batch.getRecipients().size() + " beneficiarios",
            correlationId, clientIp
        );
        return toResponse(batch);
    }

    @Transactional(readOnly = true)
    public List<PaymentDtos.BatchResponse> listRecent() {
        return repository.findTop50ByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PaymentDtos.BatchResponse findById(Long id) {
        return repository.findDetailedById(id)
            .map(this::toResponse)
            .orElseThrow(() -> new IllegalArgumentException("No se encontró el lote solicitado"));
    }

    private PaymentDtos.BatchResponse toResponse(PaymentBatch batch) {
        List<PaymentDtos.RecipientResponse> recipients = batch.getRecipients().stream()
            .map(item -> new PaymentDtos.RecipientResponse(
                item.getId(), item.getBeneficiaryName(), masker.mask(item.getDocumentNumber()),
                masker.mask(item.getAccountNumber()), item.getSapReference(), item.getCurrency(),
                item.getAmount(), item.getStatus()
            )).toList();
        return new PaymentDtos.BatchResponse(
            batch.getId(), batch.getBatchCode(), masker.mask(batch.getSourceAccount()), batch.getCurrency(),
            batch.getTotalAmount(), batch.getRequestedBy(), batch.getTriggerType(), batch.getStatus(),
            batch.getCorrelationId(), batch.getBankTransactionId(), batch.getDetail(), batch.getCreatedAt(),
            batch.getSentAt(), recipients
        );
    }
}
