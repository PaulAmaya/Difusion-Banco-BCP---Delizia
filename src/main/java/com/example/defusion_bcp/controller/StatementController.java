package com.example.defusion_bcp.controller;

import com.example.defusion_bcp.dto.StatementDtos;
import com.example.defusion_bcp.service.StatementRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import com.example.defusion_bcp.service.SapSession;
import com.example.defusion_bcp.service.SapServiceException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/statements")
public class StatementController {
    private final StatementRequestService service;

    public StatementController(StatementRequestService service) {
        this.service = service;
    }

    @GetMapping
    public List<StatementDtos.Response> listRecent(Authentication authentication, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.listRecent(session(authentication).companyDb());
    }

    @GetMapping("/config")
    public StatementDtos.Configuration config(Authentication authentication, HttpServletResponse response) {
        session(authentication);
        response.setHeader("Cache-Control", "no-store");
        return service.configuration();
    }

    @GetMapping("/{id}")
    public StatementDtos.Response detail(@PathVariable Long id, Authentication authentication, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.detail(id, session(authentication).companyDb());
    }

    @PostMapping
    public StatementDtos.Response create(@Valid @RequestBody StatementDtos.QueryRequest request,
                                         Authentication authentication,
                                         HttpServletRequest servletRequest, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.query(request, authentication.getName(), session(authentication).companyDb(), clientIp(servletRequest));
    }

    private SapSession session(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof SapSession session)) {
            throw new SapServiceException(HttpStatus.UNAUTHORIZED, "SAP_SESSION_REQUIRED", "Inicie sesion nuevamente con SAP");
        }
        return session;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
            ? request.getRemoteAddr()
            : forwarded.split(",")[0].trim();
    }
}
