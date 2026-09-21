package com.example.defusion_bcp.controller;

import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.domain.ProcessType;
import com.example.defusion_bcp.dto.AuditLogResponse;
import com.example.defusion_bcp.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {
    private final AuditLogService service;

    public AuditLogController(AuditLogService service) {
        this.service = service;
    }

    @GetMapping
    public Page<AuditLogResponse> search(
        @RequestParam(required = false) ProcessType processType,
        @RequestParam(required = false) ProcessStatus status,
        @RequestParam(required = false) String actor,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return service.search(processType, status, actor, page, size);
    }
}
