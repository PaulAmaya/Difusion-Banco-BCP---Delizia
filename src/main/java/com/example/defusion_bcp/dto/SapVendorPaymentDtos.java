package com.example.defusion_bcp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class SapVendorPaymentDtos {
    private SapVendorPaymentDtos() {
    }

    public record PaymentListResponse(
        List<PaymentSummary> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasPrevious,
        boolean hasNext,
        LocalDate dateFrom,
        LocalDate dateTo,
        String department,
        String bank,
        String sourceAccount
    ) {
        public PaymentListResponse(List<PaymentSummary> items, int page, int size, long totalElements,
                                   int totalPages, boolean hasPrevious, boolean hasNext, LocalDate dateFrom,
                                   LocalDate dateTo, String department) {
            this(items, page, size, totalElements, totalPages, hasPrevious, hasNext, dateFrom, dateTo, department, null, "11010501");
        }
        public PaymentListResponse(List<PaymentSummary> items, int page, int size, long totalElements,
                                   int totalPages, boolean hasPrevious, boolean hasNext, LocalDate dateFrom,
                                   LocalDate dateTo, String department, String bank) {
            this(items, page, size, totalElements, totalPages, hasPrevious, hasNext, dateFrom, dateTo, department, bank, "11010501");
        }
    }

    public record PaymentSummary(
        long docEntry,
        long docNum,
        String docType,
        String docDate,
        String dueDate,
        String cardCode,
        String cardName,
        String currency,
        String sapCurrency,
        BigDecimal transferSum,
        String transferDate,
        String transferAccount,
        String reference1,
        String journalRemarks,
        String cancelled,
        String authorizationStatus,
        BusinessPartnerMatch businessPartner,
        String sapTransferAccount
    ) {
    }

    public record PaymentDetail(
        long docEntry,
        long docNum,
        String docType,
        String docDate,
        String dueDate,
        String taxDate,
        String cardCode,
        String cardName,
        String currency,
        String sapCurrency,
        BigDecimal transferSum,
        String transferDate,
        String transferAccount,
        String transferReference,
        String reference1,
        String reference2,
        String journalRemarks,
        String cancelled,
        String authorizationStatus,
        Integer branchId,
        String branchName,
        List<PaymentInvoice> paymentInvoices,
        BusinessPartnerMatch businessPartner,
        String sapTransferAccount
    ) {
    }

    public record PaymentInvoice(
        int lineNum,
        long docEntry,
        BigDecimal sumApplied,
        String invoiceType,
        Integer installmentId,
        String documentDate,
        String documentNumber,
        String taxId,
        String businessName,
        String accountNumber,
        BigDecimal documentAmount
    ) {
    }

    public record BusinessPartnerMatch(
        boolean found,
        String cardCode,
        String cardName,
        String resource,
        String emailAddress,
        String documentNumber,
        Integer selectedBankAccountIndex,
        List<BankAccount> bankAccounts,
        String sapCity,
        DiffusionDtos.Region region,
        String sapDocumentType,
        String documentType,
        String documentExtension
    ) {
        public BusinessPartnerMatch(boolean found, String cardCode, String cardName, String resource,
                                    String emailAddress, String documentNumber, Integer selectedBankAccountIndex,
                                    List<BankAccount> bankAccounts) {
            this(found, cardCode, cardName, resource, emailAddress, documentNumber, selectedBankAccountIndex,
                bankAccounts, null, null, null, "", "");
        }
    }

    public record BankAccount(
        int index,
        String bankCode,
        String accountNumber,
        String accountName,
        String state,
        String city,
        String bankName,
        String bcpBankCode
    ) {
        public BankAccount(int index, String bankCode, String accountNumber, String accountName, String state, String city) {
            this(index, bankCode, accountNumber, accountName, state, city, null, null);
        }
    }
}
