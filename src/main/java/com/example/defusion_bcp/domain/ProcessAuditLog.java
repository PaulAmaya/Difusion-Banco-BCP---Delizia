package com.example.defusion_bcp.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "process_audit_logs")
public class ProcessAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ProcessType processType;

    @Column(nullable = false, length = 80)
    private String actionName;

    @Column(nullable = false, length = 60)
    private String entityType;

    @Column(length = 80)
    private String entityId;

    @Column(nullable = false, length = 120)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ProcessStatus status;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(nullable = false, length = 36)
    private String correlationId;

    @Column(length = 64)
    private String clientIp;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    protected ProcessAuditLog() {}

    public ProcessAuditLog(ProcessType processType, String actionName, String entityType,
                           String entityId, String actor, TriggerType triggerType,
                           ProcessStatus status, String message, String correlationId,
                           String clientIp, LocalDateTime occurredAt) {
        this.processType = processType;
        this.actionName = actionName;
        this.entityType = entityType;
        this.entityId = entityId;
        this.actor = actor;
        this.triggerType = triggerType;
        this.status = status;
        this.message = message;
        this.correlationId = correlationId;
        this.clientIp = clientIp;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public ProcessType getProcessType() { return processType; }
    public String getActionName() { return actionName; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getActor() { return actor; }
    public TriggerType getTriggerType() { return triggerType; }
    public ProcessStatus getStatus() { return status; }
    public String getMessage() { return message; }
    public String getCorrelationId() { return correlationId; }
    public String getClientIp() { return clientIp; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}
