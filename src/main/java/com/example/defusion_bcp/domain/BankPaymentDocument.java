package com.example.defusion_bcp.domain;

import jakarta.persistence.*;
import com.example.defusion_bcp.dto.DiffusionDtos;
import java.math.BigDecimal;

@Entity
@Table(name = "bank_payment_documents")
public class BankPaymentDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "submission_id")
    private BankPaymentSubmission submission;
    @Column(nullable = false, length = 128) private String companyDb;
    @Column(nullable = false) private long docEntry;
    @Column(nullable = false) private long docNum;
    @Column(nullable = false, length = 50) private String cardCode;
    @Column(nullable = false, length = 200) private String cardName;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    // NULL releases only an explicitly rejected attempt; other outcomes remain unique and blocked.
    @Column(unique = true, length = 160) private String activeKey;
    protected BankPaymentDocument() {}
    public BankPaymentDocument(BankPaymentSubmission submission, String companyDb, DiffusionDtos.DocumentSnapshot document) {
        this.submission = submission; this.companyDb = companyDb; this.docEntry = document.docEntry();
        this.docNum = document.docNum(); this.cardCode = document.cardCode(); this.cardName = document.cardName();
        this.amount = document.amount(); this.activeKey = companyDb + ":" + docEntry;
    }
    public void release() { activeKey = null; }
    public DiffusionDtos.DocumentSnapshot snapshot() {
        return new DiffusionDtos.DocumentSnapshot(docEntry, docNum, cardCode, cardName, amount);
    }
}
