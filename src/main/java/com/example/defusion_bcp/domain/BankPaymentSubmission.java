package com.example.defusion_bcp.domain;

import jakarta.persistence.*;
import com.example.defusion_bcp.dto.DiffusionDtos;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "bank_payment_submissions")
public class BankPaymentSubmission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 36) private String requestId;
    @Column(nullable = false, length = 128) private String companyDb;
    @Column(nullable = false, length = 120) private String requestedBy;
    @Column(nullable = false, length = 20) private String sourceAccount;
    @Column(nullable = false, length = 2) private String region;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private ProcessStatus status;
    @Column(length = 100) private String bankTransactionId;
    private Integer httpStatus;
    @Lob @Column(nullable = false, columnDefinition = "MEDIUMTEXT") private String encryptedRequest;
    @Lob @Column(columnDefinition = "MEDIUMTEXT") private String encryptedResponse;
    @Lob @Column(columnDefinition = "TEXT") private String encryptedMessage;
    @Column(nullable = false) private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime releasedAt;
    @Column(length = 120) private String releasedBy;
    @Column(length = 500) private String releaseReason;
    @OneToMany(mappedBy = "submission", cascade = CascadeType.ALL) @OrderBy("id ASC")
    private List<BankPaymentDocument> documents = new ArrayList<>();

    protected BankPaymentSubmission() {}
    public BankPaymentSubmission(String requestId, String companyDb, String actor, String sourceAccount,
                                  DiffusionDtos.PreviewResponse preview, String encryptedRequest) {
        this.requestId = requestId; this.companyDb = companyDb; this.requestedBy = actor;
        this.sourceAccount = sourceAccount; this.region = preview.region().code();
        this.amount = (BigDecimal) preview.payload().get("amount"); this.fingerprint = preview.fingerprint();
        this.encryptedRequest = encryptedRequest; this.status = ProcessStatus.PROCESSING;
        this.createdAt = LocalDateTime.now();
        preview.documents().forEach(document -> documents.add(new BankPaymentDocument(this, companyDb, document)));
    }
    public void complete(ProcessStatus status, String transactionId, Integer httpStatus,
                         String encryptedResponse, String encryptedMessage) {
        this.status = status; this.bankTransactionId = transactionId; this.httpStatus = httpStatus;
        this.encryptedResponse = encryptedResponse; this.encryptedMessage = encryptedMessage;
        this.completedAt = LocalDateTime.now();
        if (status == ProcessStatus.REJECTED) documents.forEach(BankPaymentDocument::release);
    }
    public Long getId() { return id; }
    public void releaseLocally(String actor, String reason) {
        documents.forEach(BankPaymentDocument::release);
        releasedAt = LocalDateTime.now(); releasedBy = actor; releaseReason = reason;
    }
    public String getEncryptedResponse() { return encryptedResponse; }
    public LocalDateTime getReleasedAt() { return releasedAt; }
    public String getReleasedBy() { return releasedBy; }
    public String getReleaseReason() { return releaseReason; }
    public String getRequestId() { return requestId; }
    public String getCompanyDb() { return companyDb; }
    public String getRequestedBy() { return requestedBy; }
    public String getSourceAccount() { return sourceAccount; }
    public String getRegion() { return region; }
    public BigDecimal getAmount() { return amount; }
    public ProcessStatus getStatus() { return status; }
    public String getBankTransactionId() { return bankTransactionId; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getEncryptedMessage() { return encryptedMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public List<BankPaymentDocument> getDocuments() { return List.copyOf(documents); }
}
