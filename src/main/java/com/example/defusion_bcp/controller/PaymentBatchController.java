package com.example.defusion_bcp.controller;

import com.example.defusion_bcp.dto.PaymentDtos;
import com.example.defusion_bcp.service.PaymentBatchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payment-batches")
public class PaymentBatchController {
    private final PaymentBatchService service;

    public PaymentBatchController(PaymentBatchService service) {
        this.service = service;
    }

    @GetMapping
    public List<PaymentDtos.BatchResponse> listRecent() {
        return service.listRecent();
    }

    @GetMapping("/{id}")
    public PaymentDtos.BatchResponse findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public PaymentDtos.BatchResponse create(@Valid @RequestBody PaymentDtos.CreateBatchRequest request,
                                            Authentication authentication,
                                            HttpServletRequest servletRequest) {
        return service.create(request, authentication.getName(), clientIp(servletRequest));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
            ? request.getRemoteAddr()
            : forwarded.split(",")[0].trim();
    }
}
