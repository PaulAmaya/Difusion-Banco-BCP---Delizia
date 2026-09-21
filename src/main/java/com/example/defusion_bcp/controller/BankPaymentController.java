package com.example.defusion_bcp.controller;

import com.example.defusion_bcp.dto.*;
import com.example.defusion_bcp.service.*;
import jakarta.validation.Valid;
import jakarta.servlet.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;

@RestController
@RequestMapping("/api/bank/multiple-payments")
public class BankPaymentController {
    private final BankPaymentService service;
    private final BankPaymentClient client;
    private final BankPaymentStore store;
    public BankPaymentController(BankPaymentService service, BankPaymentClient client, BankPaymentStore store) {
        this.service = service; this.client = client; this.store = store;
    }
    @GetMapping("/config")
    public BankPaymentClient.Availability config() { return client.availability(); }
    @GetMapping
    public List<BankPaymentDtos.SubmissionResponse> history(Authentication authentication, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return store.history(session(authentication).companyDb());
    }
    @PostMapping
    public BankPaymentDtos.SubmissionResponse send(@Valid @RequestBody DiffusionDtos.SendRequest body,
        Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.send(session(authentication), body, authentication.getName(), request.getRemoteAddr());
    }
    @PostMapping("/{id}/release")
    public BankPaymentDtos.SubmissionResponse release(@PathVariable Long id,
        @Valid @RequestBody BankPaymentDtos.ReleaseRequest body, Authentication authentication,
        HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return store.release(id, session(authentication).companyDb(), authentication.getName(), body, request.getRemoteAddr());
    }
    private SapSession session(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof SapSession session)) {
            throw new SapServiceException(HttpStatus.UNAUTHORIZED, "SAP_SESSION_REQUIRED", "Inicie sesion nuevamente con SAP");
        }
        return session;
    }
}
