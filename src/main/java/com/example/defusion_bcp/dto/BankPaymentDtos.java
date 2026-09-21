package com.example.defusion_bcp.dto;

import com.example.defusion_bcp.domain.ProcessStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class BankPaymentDtos {
    private BankPaymentDtos() {}
    public record SubmissionResponse(Long id, String requestId, String requestedBy, String sourceAccount,
        String region, BigDecimal amount, ProcessStatus status, String bankTransactionId, Integer httpStatus,
        String message, LocalDateTime createdAt, LocalDateTime completedAt,
        List<DiffusionDtos.DocumentSnapshot> documents, String decryptedBody,
        LocalDateTime releasedAt, String releasedBy, String releaseReason, BankResponseInfo bankResponse,
        boolean canRevertDocuments) {
        public SubmissionResponse(Long id, String requestId, String requestedBy, String sourceAccount,
            String region, BigDecimal amount, ProcessStatus status, String bankTransactionId, Integer httpStatus,
            String message, LocalDateTime createdAt, LocalDateTime completedAt, List<DiffusionDtos.DocumentSnapshot> documents) {
            this(id, requestId, requestedBy, sourceAccount, region, amount, status, bankTransactionId, httpStatus,
                message, createdAt, completedAt, documents, null, null, null, null, null, false);
        }
    }
    public record BankResponseInfo(boolean received, String rawResponse, String encryptedBody, String error) {}
    public record ReleaseRequest(@jakarta.validation.constraints.AssertTrue boolean acknowledgeDuplicateRisk,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
        Boolean confirmBankReview) {
        public ReleaseRequest(boolean acknowledgeDuplicateRisk, String reason) { this(acknowledgeDuplicateRisk, reason, false); }
    }
}
