package com.example.defusion_bcp.domain;

public enum ProcessStatus {
    PENDING_INTEGRATION,
    PROCESSING,
    UNKNOWN,
    SENT,
    AUTHORIZED,
    REJECTED,
    FAILED,
    COMPLETED
}
