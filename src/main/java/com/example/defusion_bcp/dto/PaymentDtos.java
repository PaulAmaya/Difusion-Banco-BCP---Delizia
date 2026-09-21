package com.example.defusion_bcp.dto;

import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.domain.TriggerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class PaymentDtos {
    private PaymentDtos() {}

    public record RecipientRequest(
        @NotBlank @Size(max = 180) String beneficiaryName,
        @NotBlank @Size(max = 40) String documentNumber,
        @NotBlank @Size(max = 40) String accountNumber,
        @Size(max = 80) String sapReference,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount
    ) {}

    public record CreateBatchRequest(
        @NotBlank @Size(max = 40) String sourceAccount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        TriggerType triggerType,
        @NotEmpty List<@Valid RecipientRequest> recipients
    ) {}

    public record RecipientResponse(
        Long id,
        String beneficiaryName,
        String maskedDocumentNumber,
        String maskedAccountNumber,
        String sapReference,
        String currency,
        BigDecimal amount,
        ProcessStatus status
    ) {}

    public record BatchResponse(
        Long id,
        String batchCode,
        String maskedSourceAccount,
        String currency,
        BigDecimal totalAmount,
        String requestedBy,
        TriggerType triggerType,
        ProcessStatus status,
        String correlationId,
        String bankTransactionId,
        String detail,
        LocalDateTime createdAt,
        LocalDateTime sentAt,
        List<RecipientResponse> recipients
    ) {}
}
