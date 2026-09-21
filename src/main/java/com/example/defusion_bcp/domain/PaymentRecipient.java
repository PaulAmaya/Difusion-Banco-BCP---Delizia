package com.example.defusion_bcp.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "payment_recipients")
public class PaymentRecipient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private PaymentBatch batch;

    @Column(nullable = false, length = 180)
    private String beneficiaryName;

    @Column(nullable = false, length = 40)
    private String documentNumber;

    @Column(nullable = false, length = 40)
    private String accountNumber;

    @Column(length = 80)
    private String sapReference;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ProcessStatus status;

    protected PaymentRecipient() {}

    public PaymentRecipient(String beneficiaryName, String documentNumber, String accountNumber,
                            String sapReference, String currency, BigDecimal amount,
                            ProcessStatus status) {
        this.beneficiaryName = beneficiaryName;
        this.documentNumber = documentNumber;
        this.accountNumber = accountNumber;
        this.sapReference = sapReference;
        this.currency = currency;
        this.amount = amount;
        this.status = status;
    }

    void assignBatch(PaymentBatch batch) { this.batch = batch; }

    public Long getId() { return id; }
    public String getBeneficiaryName() { return beneficiaryName; }
    public String getDocumentNumber() { return documentNumber; }
    public String getAccountNumber() { return accountNumber; }
    public String getSapReference() { return sapReference; }
    public String getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
    public ProcessStatus getStatus() { return status; }
}
