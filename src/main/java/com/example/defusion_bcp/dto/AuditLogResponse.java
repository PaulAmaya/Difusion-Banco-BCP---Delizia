package com.example.defusion_bcp.dto;

import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.domain.ProcessType;
import com.example.defusion_bcp.domain.TriggerType;

import java.time.LocalDateTime;

public record AuditLogResponse(
    Long id,
    ProcessType processType,
    String actionName,
    String entityType,
    String entityId,
    String actor,
    TriggerType triggerType,
    ProcessStatus status,
    String message,
    String correlationId,
    String clientIp,
    LocalDateTime occurredAt
) {}
