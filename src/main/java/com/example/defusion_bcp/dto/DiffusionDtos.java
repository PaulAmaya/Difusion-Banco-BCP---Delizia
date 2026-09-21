package com.example.defusion_bcp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

public final class DiffusionDtos {
    private DiffusionDtos() {}

    public record CodeName(String code, String name) {}
    public record Region(String code, String name, int cityCode) {}
    public record SapCity(String value, String name, String region) {}
    public record SourceAccount(String sapAccount, String name, String region, String bankAccount) {}
    public record Catalogs(List<CodeName> banks, List<CodeName> documentTypes,
                           List<CodeName> documentExtensions, List<Region> regions,
                           List<SapCity> sapCities, int notApplicableCityCode, List<SourceAccount> sourceAccounts) {
        public Catalogs(List<CodeName> banks, List<CodeName> documentTypes,
                        List<CodeName> documentExtensions, List<Region> regions) {
            this(banks, documentTypes, documentExtensions, regions, List.of(), 999, List.of());
        }
    }

    public record PrepareRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> docEntries
    ) {}

    public record PreparedResponse(Catalogs catalogs, List<SapVendorPaymentDtos.PaymentDetail> payments) {}

    public record Selection(
        @Positive long docEntry,
        @NotNull @Min(0) Integer bankAccountIndex,
        @NotBlank @Size(max = 30) String bankCode,
        @NotBlank @Size(max = 50) String accountNumber,
        @NotBlank @Size(max = 2) String region,
        @Size(max = 40) String documentNumber,
        @Size(max = 1) String documentType,
        @Size(max = 2) String documentExtension,
        @Size(max = 20) String documentComplement
    ) {}

    public record PreviewRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Selection> payments,
        @NotBlank @Size(max = 20) String sourceAccount
    ) {
        public PreviewRequest(List<Selection> payments) { this(payments, "11010501"); }
    }

    public record DocumentSnapshot(long docEntry, long docNum, String cardCode, String cardName,
                                   java.math.BigDecimal amount) {}
    public record PreviewResponse(String correlationId, Region region, List<Long> docEntries,
                                  Map<String, Object> payload, List<String> warnings,
                                  String fingerprint, List<DocumentSnapshot> documents) {}
    public record SendRequest(@NotNull @Valid PreviewRequest selection,
                              @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String fingerprint,
                              @NotBlank @Pattern(regexp = "[a-fA-F0-9-]{36}") String requestId) {}
}
