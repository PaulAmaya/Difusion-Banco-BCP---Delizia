package com.example.defusion_bcp.controller;

import com.example.defusion_bcp.dto.SapVendorPaymentDtos;
import com.example.defusion_bcp.dto.DiffusionDtos;
import com.example.defusion_bcp.service.DiffusionPreviewService;
import com.example.defusion_bcp.service.BankPaymentStore;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.domain.ProcessType;
import com.example.defusion_bcp.domain.TriggerType;
import com.example.defusion_bcp.service.AuditLogService;
import com.example.defusion_bcp.service.SapClient;
import com.example.defusion_bcp.service.SapServiceException;
import com.example.defusion_bcp.service.SapSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/sap/vendor-payments")
public class SapVendorPaymentController {
    private final SapClient sapClient;
    private final AuditLogService auditLogService;
    private final DiffusionPreviewService diffusionService;
    private final BankPaymentStore bankStore;

    public SapVendorPaymentController(SapClient sapClient, AuditLogService auditLogService,
                                      DiffusionPreviewService diffusionService, BankPaymentStore bankStore) {
        this.sapClient = sapClient;
        this.auditLogService = auditLogService;
        this.diffusionService = diffusionService;
        this.bankStore = bankStore;
    }

    @PostMapping("/diffusion/prepare")
    public DiffusionDtos.PreparedResponse prepareDiffusion(
        @Valid @RequestBody DiffusionDtos.PrepareRequest body,
        Authentication authentication,
        HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        return diffusionService.prepare(requireSession(authentication), body);
    }

    @GetMapping("/diffusion/catalogs")
    public DiffusionDtos.Catalogs diffusionCatalogs() {
        return diffusionService.catalogs();
    }

    @PostMapping("/diffusion/preview")
    public DiffusionDtos.PreviewResponse previewDiffusion(
        @Valid @RequestBody DiffusionDtos.PreviewRequest body,
        Authentication authentication,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        var preview = diffusionService.preview(requireSession(authentication), body);
        auditLogService.recordDiffusionPreview(preview.docEntries(), preview.region().code(),
            preview.region().name(), authentication.getName(), preview.correlationId(), request.getRemoteAddr());
        var publicPayload = new java.util.LinkedHashMap<>(preview.payload());
        publicPayload.remove("password");
        return new DiffusionDtos.PreviewResponse(preview.correlationId(), preview.region(), preview.docEntries(),
            publicPayload, preview.warnings(), preview.fingerprint(), preview.documents());
    }

    @GetMapping
    public SapVendorPaymentDtos.PaymentListResponse list(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "") String department,
        @RequestParam(defaultValue = "") String bank,
        @RequestParam(defaultValue = "11010501") String sourceAccount,
        Authentication authentication,
        HttpServletRequest request
    ) {
        if (from == null) from = to == null ? LocalDate.now(java.time.ZoneId.of("America/La_Paz")) : to;
        if (to == null) to = from;
        validatePageRequest(from, to, page, size);
        String departmentCode = department.trim().toUpperCase(java.util.Locale.ROOT);
        DiffusionDtos.Region region = departmentCode.isEmpty() ? null : diffusionService.catalogs().regions().stream()
            .filter(item -> item.code().equals(departmentCode)).findFirst()
            .orElseThrow(() -> new SapServiceException(HttpStatus.BAD_REQUEST, "SAP_DEPARTMENT_INVALID",
                "Seleccione uno de los nueve departamentos del catalogo"));
        String bankCode = bank.trim();
        DiffusionDtos.CodeName bankEntry = bankCode.isEmpty() ? null : diffusionService.catalogs().banks().stream()
            .filter(item -> item.code().equals(bankCode)).findFirst()
            .orElseThrow(() -> new SapServiceException(HttpStatus.BAD_REQUEST, "SAP_BANK_INVALID",
                "Seleccione un banco del catalogo"));
        var session = requireSession(authentication);
        SapVendorPaymentDtos.PaymentListResponse response =
            sapClient.vendorPayments(session, from, to, page, size, region, bankCode, sourceAccount.trim(),
                bankStore.blockedIds(session.companyDb()));
        auditLogService.record(
            ProcessType.MULTIPLE_PAYMENT,
            "SAP_VENDOR_PAYMENTS_READ",
            "VendorPayments",
            from + ":" + to,
            authentication.getName(),
            TriggerType.MANUAL,
            ProcessStatus.COMPLETED,
            "Página " + (page + 1) + " consultada: " + response.totalElements()
                + " pagos PBL entre " + from + " y " + to
                + (region == null ? "" : " en " + region.name())
                + (bankEntry == null ? "" : " del banco " + bankEntry.name())
                + " con TransferAccount " + response.sourceAccount(),
            UUID.randomUUID().toString(),
            request.getRemoteAddr()
        );
        return response;
    }

    private void validatePageRequest(LocalDate from, LocalDate to, int page, int size) {
        if (from.isAfter(to)) {
            throw new SapServiceException(
                HttpStatus.BAD_REQUEST,
                "SAP_DATE_RANGE_INVALID",
                "La fecha desde no puede ser posterior a la fecha hasta"
            );
        }
        if (page < 0 || page > 1_000_000) {
            throw new SapServiceException(
                HttpStatus.BAD_REQUEST,
                "SAP_PAGE_INVALID",
                "La página solicitada no es válida"
            );
        }
        if (size < 1 || size > 100) {
            throw new SapServiceException(
                HttpStatus.BAD_REQUEST,
                "SAP_PAGE_SIZE_INVALID",
                "El tamaño de página debe estar entre 1 y 100"
            );
        }
    }

    @GetMapping("/{docEntry}")
    public SapVendorPaymentDtos.PaymentDetail detail(
        @PathVariable long docEntry,
        Authentication authentication,
        HttpServletRequest request
    ) {
        if (docEntry <= 0) {
            throw new SapServiceException(
                HttpStatus.BAD_REQUEST,
                "SAP_PAYMENT_INVALID",
                "DocEntry debe ser mayor que cero"
            );
        }
        SapVendorPaymentDtos.PaymentDetail response =
            sapClient.vendorPayment(requireSession(authentication), docEntry);
        auditLogService.record(
            ProcessType.MULTIPLE_PAYMENT,
            "SAP_VENDOR_PAYMENT_VIEWED",
            "VendorPayments",
            Long.toString(docEntry),
            authentication.getName(),
            TriggerType.MANUAL,
            ProcessStatus.COMPLETED,
            "Se consultó el detalle del pago SAP " + response.docNum(),
            UUID.randomUUID().toString(),
            request.getRemoteAddr()
        );
        return response;
    }

    private SapSession requireSession(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof SapSession session)) {
            throw new SapServiceException(
                HttpStatus.UNAUTHORIZED,
                "SAP_SESSION_REQUIRED",
                "Debe iniciar sesión nuevamente con SAP"
            );
        }
        return session;
    }
}
