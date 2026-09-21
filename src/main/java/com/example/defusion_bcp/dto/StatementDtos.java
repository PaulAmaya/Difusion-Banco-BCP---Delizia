package com.example.defusion_bcp.dto;

import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.domain.TriggerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import java.util.List;
import com.example.defusion_bcp.service.BankPaymentClient;

import java.time.LocalDateTime;

public final class StatementDtos {
    private StatementDtos() {}

    public record BankPayload(
        @NotNull @Positive Integer companyId,
        @NotBlank @Size(max = 128) String password,
        @NotBlank @Size(max = 20) String documentNumber,
        @NotBlank @Pattern(regexp = "[OPQRTUW]") String documentType,
        @NotBlank @Pattern(regexp = "BE|CB|CH|LP|OR|PA|PE|PO|SC|TJ|SN|XX|NN") String documentExtension,
        @NotBlank @Pattern(regexp = "\\d{1,40}") String accountNumber,
        @NotBlank @Pattern(regexp = "^(19|20)\\d{2}(0[1-9]|1[0-2])$", message = "El periodo debe tener formato AAAAMM") String period
    ) {
        @Override public String toString() { return "BankPayload[credentials=REDACTED]"; }
    }
    public record CreateRequest(@NotNull @Valid BankPayload payload) {}
    public record QueryRequest(
        @NotBlank @Pattern(regexp = "\\d{1,40}") String accountNumber,
        @NotBlank @Pattern(regexp = "^(19|20)\\d{2}(0[1-9]|1[0-2])$", message = "El periodo debe tener formato AAAAMM") String period
    ) {}
    public record Account(String name, String accountNumber) {}
    public record Configuration(String accountNumber, String period, List<Account> accounts, BankPaymentClient.Availability availability) {}
    public record BankResponse(ProcessStatus status, Integer httpStatus, Boolean isOk, String rawResponse,
        String encryptedBody, String encryptedMessage, String decryptedBody, String decryptedMessage, String error) {
        @Override public String toString() { return "BankResponse[confidentialData=REDACTED]"; }
    }

    public record Response(
        Long id,
        String maskedAccountNumber,
        String period,
        String requestedBy,
        TriggerType triggerType,
        ProcessStatus status,
        String correlationId,
        String detail,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        Integer httpStatus,
        BankResponse bankResponse,
        String sentEnvelope
    ) {}
}
