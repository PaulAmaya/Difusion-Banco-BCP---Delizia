package com.example.defusion_bcp.service;

import com.example.defusion_bcp.dto.DiffusionDtos;
import com.example.defusion_bcp.dto.SapVendorPaymentDtos;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class DiffusionPreviewService {
    private final SapClient sapClient;
    private final DiffusionDtos.Catalogs catalogs;
    private final Map<String, Object> header;
    private final ObjectMapper mapper;

    public DiffusionPreviewService(SapClient sapClient, ObjectMapper mapper,
        String password, String documentNumber, String sourceAccount) {
        this.sapClient = sapClient;
        this.mapper = mapper;
        try (var catalogInput = new ClassPathResource("bank/payment-catalogs.json").getInputStream();
             var headerInput = new ClassPathResource("bank/diffusion-header.json").getInputStream()) {
            catalogs = mapper.readValue(catalogInput, DiffusionDtos.Catalogs.class);
            header = mapper.readValue(headerInput, new TypeReference<LinkedHashMap<String, Object>>() {});
            header.put("password", password);
            header.put("documentNumber", documentNumber);
            header.put("sourceAccount", sourceAccount);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo cargar la configuracion de previsualizacion", exception);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DiffusionPreviewService(SapClient sapClient, ObjectMapper mapper,
        @Value("${BANK_DIFFUSION_PASSWORD:}") String password,
        @Value("${BANK_DIFFUSION_DOCUMENT_NUMBER:}") String documentNumber,
        @Value("${BANK_DIFFUSION_SOURCE_ACCOUNT:}") String sourceAccount,
        @Value("${BCP_COMPANY_ID:2295}") Integer companyId,
        @Value("${BANK_DIFFUSION_DOCUMENT_TYPE:Q}") String documentType,
        @Value("${BANK_DIFFUSION_DOCUMENT_EXTENSION:LP}") String documentExtension,
        @Value("${BANK_DIFFUSION_FUND_SOURCE:Ambiente sandbox}") String fundSource,
        @Value("${BANK_DIFFUSION_FUND_DESTINATION:Ambiente de pruebas}") String fundDestination,
        @Value("${BANK_DIFFUSION_DESCRIPTION:Descripcion prueba}") String description,
        @Value("${BANK_DIFFUSION_SEND_VOUCHERS:}") String sendVouchers,
        @Value("${BANK_DIFFUSION_APPROVERS_JSON:}") String approvers,
        @Value("${BCP_ENVIRONMENT:SANDBOX}") String environment) {
        this(sapClient, mapper, password, documentNumber, sourceAccount);
        header.put("companyId", companyId);
        header.put("documentType", documentType);
        header.put("documentExtension", documentExtension);
        header.put("fundSource", fundSource);
        header.put("fundDestination", fundDestination);
        header.put("description", description);
        header.put("sendVouchers", sendVouchers);
        if (environment.equalsIgnoreCase("PRODUCTION") || !approvers.isBlank()) {
            try {
                var values = mapper.readTree(approvers.isBlank() ? "[]" : approvers);
                if (!values.isArray()) throw new IllegalArgumentException("Expected array");
                header.put("cismartApprovers", mapper.convertValue(values, List.class));
            } catch (Exception exception) {
                throw new IllegalStateException("BANK_DIFFUSION_APPROVERS_JSON debe ser un arreglo JSON valido");
            }
        }
    }

    public DiffusionDtos.PreparedResponse prepare(SapSession session, DiffusionDtos.PrepareRequest request) {
        validateIds(request.docEntries());
        return new DiffusionDtos.PreparedResponse(catalogs,
            request.docEntries().stream().map(id -> sapClient.vendorPayment(session, id)).toList());
    }

    public DiffusionDtos.Catalogs catalogs() { return catalogs; }

    public DiffusionDtos.PreviewResponse preview(SapSession session, DiffusionDtos.PreviewRequest request) {
        List<Long> ids = request.payments().stream().map(DiffusionDtos.Selection::docEntry).toList();
        validateIds(ids);
        var source = catalogs.sourceAccounts().stream().filter(account -> account.sapAccount().equals(request.sourceAccount()))
            .findFirst().orElseThrow(() -> invalid("Seleccione una cuenta de origen permitida: BCP LP o BCP SC"));
        List<Map<String, Object>> providers = new ArrayList<>();
        List<Map<String, Object>> ach = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<DiffusionDtos.DocumentSnapshot> documents = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        DiffusionDtos.Region batchRegion = null;

        for (DiffusionDtos.Selection selection : request.payments()) {
            // Read amounts and beneficiary accounts again instead of trusting browser snapshots.
            var payment = sapClient.vendorPayment(session, selection.docEntry());
            validatePayment(payment);
            if (!source.sapAccount().equals(payment.sapTransferAccount())) {
                throw invalid("El TransferAccount del pago " + payment.docNum()
                    + " no coincide con la cuenta de origen elegida. No mezcle cuentas de origen y actualice la previsualizacion");
            }
            var partner = payment.businessPartner();
            if (partner == null || !partner.found()) {
                throw invalid("No se encontro el Business Partner del pago " + payment.docNum());
            }
            var account = partner.bankAccounts().stream()
                .filter(item -> Objects.equals(item.index(), selection.bankAccountIndex()))
                .findFirst().orElseThrow(() -> invalid("Seleccione una cuenta valida para el pago " + payment.docNum()));
            if (text(account.accountNumber()).isEmpty()) {
                throw invalid("La cuenta beneficiaria del pago " + payment.docNum() + " esta vacia");
            }
            if (!Objects.equals(account.bankCode(), selection.bankCode())
                || !Objects.equals(account.accountNumber(), selection.accountNumber())) {
                throw invalid("La cuenta SAP del pago " + payment.docNum() + " cambio. Actualice la previsualizacion");
            }
            var resolvedBank = BankCodeResolver.resolve(account.bankCode(), account.bankName(), catalogs.banks());
            if (resolvedBank == null) {
                throw invalid("BankCode " + text(account.bankCode()) + " (" + text(account.bankName()) + ") del pago " + payment.docNum()
                    + " no tiene equivalencia en el catalogo del banco");
            }
            String bankCode = resolvedBank.code();
            DiffusionDtos.Region region = catalogs.regions().stream()
                .filter(item -> item.code().equals(selection.region())).findFirst()
                .orElseThrow(() -> invalid("Seleccione una region valida para el pago " + payment.docNum()));
            DiffusionDtos.Region sapRegion = PaymentRegionMatcher.regionForSapCity(partner.sapCity(), catalogs);
            if (sapRegion == null) {
                throw invalid("BusinessPartners.U_CITY del pago " + payment.docNum() + " no corresponde a una region del catalogo");
            }
            if (!sapRegion.code().equals(region.code())) {
                throw invalid("La region seleccionada no coincide con BusinessPartners.U_CITY del pago " + payment.docNum());
            }
            if (batchRegion != null && !batchRegion.code().equals(region.code())) {
                throw invalid("Todos los pagos del lote deben pertenecer a la misma region");
            }
            batchRegion = region;
            String documentNumber = sapOrManual(partner.documentNumber(), selection.documentNumber(), "numero de documento", payment.docNum());
            String sapType = SapDocumentMapper.documentType(partner.sapDocumentType(), catalogs);
            String documentType = sapOrManual(sapType,
                validateCode(selection.documentType(), catalogs.documentTypes(), "tipo de documento"), "tipo de documento", payment.docNum());
            String extension = sapOrManual(sapRegion.code(),
                validateCode(selection.documentExtension(), catalogs.documentExtensions(), "extension"), "extension", payment.docNum());
            if (documentNumber.isEmpty() || documentType.isEmpty() || extension.isEmpty()) {
                warnings.add("Pago " + payment.docNum() + ": identificacion incompleta; no esta listo para enviar al banco.");
            }
            String email = text(partner.emailAddress());
            if (email.isEmpty()) warnings.add("Pago " + payment.docNum() + ": Business Partner sin correo.");
            BigDecimal amount = payment.transferSum().setScale(2, RoundingMode.UNNECESSARY);
            documents.add(new DiffusionDtos.DocumentSnapshot(payment.docEntry(), payment.docNum(),
                payment.cardCode(), payment.cardName(), amount));
            Map<String, Object> line = new LinkedHashMap<>();
            boolean bcp = "1005".equals(bankCode);
            line.put("paymentType", bcp ? "PROV" : "ACH");
            line.put("line", (bcp ? providers.size() : ach.size()) + 1);
            line.put("accountNumber", account.accountNumber().trim());
            if (bcp) line.put("glossPayment", bankGloss(payment.journalRemarks()));
            else line.put("titularName", text(account.accountName()).isEmpty() ? partner.cardName() : account.accountName().trim());
            line.put("amount", amount);
            if (!bcp) line.put("branchOfficeId", region.cityCode());
            if (bcp) {
                line.put("documentType", documentType);
                line.put("documentNumber", documentNumber);
                line.put("documentExtension", extension);
                line.put("firstDetail", text(payment.reference1()));
                line.put("secondDetails", payment.paymentInvoices().stream()
                    .map(SapVendorPaymentDtos.PaymentInvoice::documentNumber)
                    .filter(Objects::nonNull).filter(number -> !number.isBlank())
                    .distinct().reduce((left, right) -> left + ", " + right).orElse(""));
            } else {
                line.put("firstDetail", bankGloss(payment.journalRemarks()));
            }
            line.put("mail", email);
            if (!bcp) {
                line.put("bankId", bankCode);
                line.put("documentNumber", documentNumber);
                line.put("documentType", documentType);
                line.put("documentExtension", extension);
                line.put("documentComplement", text(selection.documentComplement()));
            }
            (bcp ? providers : ach).add(line);
            total = total.add(amount);
        }
        Map<String, Object> payload = new LinkedHashMap<>(header);
        payload.put("sourceAccount", source.bankAccount());
        payload.put("amount", total.setScale(2));
        payload.put("spreadsheet", Map.of("formProvidersPayments", providers, "formAchPayments", ach));
        if (text((String) header.get("password")).isEmpty() || text((String) header.get("documentNumber")).isEmpty()) {
            warnings.add("Cabecera pendiente: configure BANK_DIFFUSION_PASSWORD y BANK_DIFFUSION_DOCUMENT_NUMBER.");
        }
        warnings.add("Lote pendiente de envio y autorizacion bancaria. No se ha cifrado ni enviado al banco.");
        return new DiffusionDtos.PreviewResponse(UUID.randomUUID().toString(), batchRegion, ids, payload, warnings,
            fingerprint(payload, documents), documents);
    }

    private String fingerprint(Map<String, Object> payload, List<DiffusionDtos.DocumentSnapshot> documents) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(List.of(canonical(payload), documents));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(key.toString(), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }

    public void validateForSending(DiffusionDtos.PreviewResponse preview) {
        var payload = preview.payload();
        if (!(payload.get("companyId") instanceof Number companyId) || companyId.intValue() <= 0) {
            throw invalid("Configure BCP_COMPANY_ID con el identificador de la empresa");
        }
        if (!(payload.get("cismartApprovers") instanceof List<?> approvers) || approvers.isEmpty()) {
            throw invalid("Configure BANK_DIFFUSION_APPROVERS_JSON con los autorizadores reales");
        }
        for (String field : List.of("password", "documentNumber", "documentType", "documentExtension",
            "fundSource", "fundDestination", "sourceAccount", "description")) {
            if (!(payload.get(field) instanceof String value) || value.isBlank()) {
                throw invalid("Cabecera incompleta: " + field + ". Configure los datos del preparador antes de enviar");
            }
        }
        requireLength(payload, "documentNumber", 12);
        requireLength(payload, "description", 80);
        requireLength(payload, "fundSource", 400);
        requireLength(payload, "fundDestination", 400);
        Object spreadsheet = payload.get("spreadsheet");
        if (!(spreadsheet instanceof Map<?, ?> sheets)) throw invalid("Detalle de lote invalido");
        for (Object form : sheets.values()) {
            if (!(form instanceof List<?> lines)) throw invalid("Detalle de lote invalido");
            for (Object item : lines) {
                if (!(item instanceof Map<?, ?> line)) throw invalid("Linea invalida");
                for (String field : List.of("accountNumber", "documentNumber", "documentType", "documentExtension")) {
                    if (!(line.get(field) instanceof String value) || value.isBlank()) {
                        throw invalid("Identificacion o cuenta incompleta en una linea: " + field);
                    }
                }
                requireLength(line, "accountNumber", 26);
                requireLength(line, "documentNumber", 12);
                for (String field : List.of("glossPayment", "firstDetail", "secondDetails", "titularName", "mail")) {
                    requireLength(line, field, 100);
                }
            }
        }
    }

    private void requireLength(Map<?, ?> values, String field, int max) {
        if (values.get(field) instanceof String value && value.length() > max) {
            throw invalid(field + " excede " + max + " caracteres. Corrija el dato antes de enviar");
        }
    }

    private void validatePayment(SapVendorPaymentDtos.PaymentDetail payment) {
        if (payment.cardCode() == null || !payment.cardCode().startsWith("PBL") || !"rSupplier".equals(payment.docType())) {
            throw invalid("El pago " + payment.docNum() + " no corresponde a un proveedor PBL");
        }
        if ("tYES".equals(payment.cancelled())) throw invalid("El pago " + payment.docNum() + " esta cancelado");
        if (!Set.of("BS", "BOB", "BOL").contains(text(payment.sapCurrency()).toUpperCase(Locale.ROOT))) {
            throw invalid("La cabecera de pruebas esta en BOL. No se pueden incluir otras monedas");
        }
        if (payment.transferSum() == null || payment.transferSum().signum() <= 0 || payment.transferSum().scale() > 2
            && payment.transferSum().stripTrailingZeros().scale() > 2) {
            throw invalid("El pago " + payment.docNum() + " debe tener un importe de transferencia positivo con hasta dos decimales");
        }
    }

    private void validateIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0)
            || new HashSet<>(ids).size() != ids.size()) {
            throw invalid("Seleccione entre 1 y 100 pagos diferentes");
        }
    }

    private String validateCode(String raw, List<DiffusionDtos.CodeName> entries, String label) {
        String code = text(raw);
        if (!code.isEmpty() && entries.stream().noneMatch(item -> item.code().equals(code))) {
            throw invalid("Codigo de " + label + " invalido");
        }
        return code;
    }

    private String sapOrManual(String sapValue, String manualValue, String label, long docNum) {
        String sap = text(sapValue);
        String manual = text(manualValue);
        if (sap.isEmpty()) return manual;
        if (!manual.isEmpty() && !sap.equals(manual)) {
            throw invalid("El " + label + " SAP del pago " + docNum + " cambio o no coincide. Actualice la previsualizacion");
        }
        return sap;
    }

    private String text(String value) { return value == null ? "" : value.trim(); }
    static String bankGloss(String value) {
        return value == null ? "" : value.replaceAll("(?i)BCPMN/DEBITO\\s*", "").trim();
    }

    private SapServiceException invalid(String message) {
        return new SapServiceException(HttpStatus.BAD_REQUEST, "DIFFUSION_INVALID", message);
    }
}
