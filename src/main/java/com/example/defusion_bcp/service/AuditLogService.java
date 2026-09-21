package com.example.defusion_bcp.service;

import com.example.defusion_bcp.domain.*;
import com.example.defusion_bcp.dto.AuditLogResponse;
import com.example.defusion_bcp.repository.ProcessAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditLogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditLogService.class);
    private final ProcessAuditLogRepository repository;

    public AuditLogService(ProcessAuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(ProcessType processType, String actionName, String entityType,
                       String entityId, String actor, TriggerType triggerType,
                       ProcessStatus status, String message, String correlationId,
                       String clientIp) {
        ProcessAuditLog event = new ProcessAuditLog(
            processType, actionName, entityType, entityId, actor, triggerType,
            status, message, correlationId, clientIp, LocalDateTime.now()
        );
        repository.save(event);
        LOGGER.info("audit process={} action={} status={} actor={} correlationId={}",
            processType, actionName, status, actor, correlationId);
    }

    @Transactional
    public void recordDiffusionPreview(List<Long> docEntries, String regionCode, String regionName,
                                        String actor, String correlationId, String clientIp) {
        record(ProcessType.MULTIPLE_PAYMENT, "DIFFUSION_PREVIEW_CREATED", "VendorPaymentBatch",
            regionCode, actor, TriggerType.MANUAL, ProcessStatus.COMPLETED,
            "Previsualizacion de " + docEntries.size() + " pagos en " + regionName + ". Sin envio al banco.",
            correlationId, clientIp);
        for (Long docEntry : docEntries) {
            record(ProcessType.MULTIPLE_PAYMENT, "DIFFUSION_PAYMENT_PREVIEWED", "VendorPayments",
                docEntry.toString(), actor, TriggerType.MANUAL, ProcessStatus.COMPLETED,
                "Pago SAP " + docEntry + " incluido en la previsualizacion de " + regionName
                    + ". Sin envio al banco.", correlationId, clientIp);
        }
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(ProcessType processType, ProcessStatus status,
                                         String actor, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        String normalizedActor = actor == null || actor.isBlank() ? null : actor.trim();
        return repository.search(processType, status, normalizedActor,
                PageRequest.of(Math.max(page, 0), safeSize))
            .map(this::toResponse);
    }

    private AuditLogResponse toResponse(ProcessAuditLog event) {
        return new AuditLogResponse(
            event.getId(), event.getProcessType(), event.getActionName(), event.getEntityType(),
            event.getEntityId(), event.getActor(), event.getTriggerType(), event.getStatus(),
            event.getMessage(), event.getCorrelationId(), event.getClientIp(), event.getOccurredAt()
        );
    }
}
