package com.example.defusion_bcp.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "payment_batches")
public class PaymentBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String batchCode;

    @Column(nullable = false, length = 40)
    private String sourceAccount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

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

    @Column(length = 100)
    private String bankTransactionId;

    @Column(length = 500)
    private String detail;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime sentAt;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<PaymentRecipient> recipients = new ArrayList<>();

    protected PaymentBatch() {}

    public PaymentBatch(String batchCode, String sourceAccount, String currency,
                        BigDecimal totalAmount, String requestedBy, TriggerType triggerType,
                        ProcessStatus status, String correlationId, String detail,
                        LocalDateTime createdAt) {
        this.batchCode = batchCode;
        this.sourceAccount = sourceAccount;
        this.currency = currency;
        this.totalAmount = totalAmount;
        this.requestedBy = requestedBy;
        this.triggerType = triggerType;
        this.status = status;
        this.correlationId = correlationId;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public void addRecipient(PaymentRecipient recipient) {
        recipients.add(recipient);
        recipient.assignBatch(this);
    }

    public Long getId() { return id; }
    public String getBatchCode() { return batchCode; }
    public String getSourceAccount() { return sourceAccount; }
    public String getCurrency() { return currency; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getRequestedBy() { return requestedBy; }
    public TriggerType getTriggerType() { return triggerType; }
    public ProcessStatus getStatus() { return status; }
    public String getCorrelationId() { return correlationId; }
    public String getBankTransactionId() { return bankTransactionId; }
    public String getDetail() { return detail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getSentAt() { return sentAt; }
    public List<PaymentRecipient> getRecipients() { return List.copyOf(recipients); }
}
