package com.example.defusion_bcp.dto;

import com.example.defusion_bcp.domain.ProcessStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class BankBatchDetailDtos {
    private BankBatchDetailDtos() {}

    public record Summary(Long submissionId, String transactionId, String requestedBy,
        String sourceAccount, String region, BigDecimal amount, LocalDateTime sentAt,
        List<DiffusionDtos.DocumentSnapshot> documents) {}

    public record History(List<Summary> items, int page, int totalPages, long totalElements) {}

    public record Detail(Summary submission, ProcessStatus status, Integer httpStatus,
        Boolean isOk, String code, String message, JsonNode body, String error,
        LocalDateTime checkedAt) {}
}
