package com.example.defusion_bcp.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "bank_statement_requests")
public class BankStatementRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String accountNumber;

    @Column(nullable = false, length = 6)
    private String period;

    @Column(nullable = false, length = 120)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ProcessStatus status;

    @Column(nullable = false, unique = true, length = 36)
    private String correlationId;

    @Column(length = 500)
    private String detail;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime completedAt;

    @Column(length = 120)
    private String companyDb;

    private Integer httpStatus;

    @Lob @Column(columnDefinition = "MEDIUMTEXT")
    private String encryptedRequest;

    @Lob @Column(columnDefinition = "MEDIUMTEXT")
    private String encryptedResponse;

    protected BankStatementRequest() {}

    public BankStatementRequest(String accountNumber, String period, String requestedBy,
                                TriggerType triggerType, ProcessStatus status,
                                String correlationId, String detail, LocalDateTime requestedAt) {
        this.accountNumber = accountNumber;
        this.period = period;
        this.requestedBy = requestedBy;
        this.triggerType = triggerType;
        this.status = status;
        this.correlationId = correlationId;
        this.detail = detail;
        this.requestedAt = requestedAt;
    }

    public Long getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public String getPeriod() { return period; }
    public String getRequestedBy() { return requestedBy; }
    public TriggerType getTriggerType() { return triggerType; }
    public ProcessStatus getStatus() { return status; }
    public String getCorrelationId() { return correlationId; }
    public String getDetail() { return detail; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public String getCompanyDb() { return companyDb; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getEncryptedRequest() { return encryptedRequest; }
    public String getEncryptedResponse() { return encryptedResponse; }
    public void initializeBankQuery(String companyDb, String encryptedRequest) {
        this.companyDb = companyDb;
        this.encryptedRequest = encryptedRequest;
    }
    public void complete(ProcessStatus status, Integer httpStatus, String encryptedResponse) {
        this.status = status;
        this.httpStatus = httpStatus;
        this.encryptedResponse = encryptedResponse;
        this.completedAt = LocalDateTime.now();
        this.detail = "Consulta BCP: " + status + ". HTTP: " + (httpStatus == null ? "sin respuesta" : httpStatus);
    }
}
