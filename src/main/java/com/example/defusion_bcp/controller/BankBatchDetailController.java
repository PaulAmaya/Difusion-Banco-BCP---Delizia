package com.example.defusion_bcp.controller;

import com.example.defusion_bcp.dto.BankBatchDetailDtos;
import com.example.defusion_bcp.service.BankBatchDetailService;
import com.example.defusion_bcp.service.SapServiceException;
import com.example.defusion_bcp.service.SapSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bank/batch-details")
public class BankBatchDetailController {
    private final BankBatchDetailService service;

    public BankBatchDetailController(BankBatchDetailService service) { this.service = service; }

    @GetMapping
    public BankBatchDetailDtos.History history(@RequestParam(defaultValue = "0") int page,
        Authentication authentication, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.history(session(authentication).companyDb(), page);
    }

    @PostMapping("/{submissionId}/query")
    public BankBatchDetailDtos.Detail query(@PathVariable Long submissionId, Authentication authentication,
        HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var session = session(authentication);
        return service.query(submissionId, session.companyDb(), authentication.getName(), request.getRemoteAddr());
    }

    private SapSession session(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof SapSession session)) {
            throw new SapServiceException(HttpStatus.UNAUTHORIZED, "SAP_SESSION_REQUIRED", "Inicie sesion nuevamente con SAP");
        }
        return session;
    }
}
