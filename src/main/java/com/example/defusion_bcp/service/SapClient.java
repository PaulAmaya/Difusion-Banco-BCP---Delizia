package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.SapProperties;
import com.example.defusion_bcp.dto.SapVendorPaymentDtos;
import com.example.defusion_bcp.dto.DiffusionDtos;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class SapClient {
    private static final Logger log = LoggerFactory.getLogger(SapClient.class);
    private static final String VENDOR_PAYMENT_LIST_FIELDS = String.join(",",
        "DocEntry", "DocNum", "DocType", "DocDate", "DueDate", "CardCode", "CardName",
        "DocCurrency", "TransferSum", "TransferDate", "TransferAccount", "Reference1",
        "JournalRemarks", "Cancelled", "AuthorizationStatus"
    );
    private static final String VENDOR_PAYMENT_DETAIL_FIELDS = VENDOR_PAYMENT_LIST_FIELDS + String.join(",",
        ",TaxDate", "TransferReference", "Reference2", "BPLID", "BPLName", "PaymentInvoices"
    );

    private final SapProperties properties;
    private final RestClient restClient;
    private final BankCodeResolver bankResolver;

    @Autowired
    public SapClient(SapProperties properties, BankCodeResolver bankResolver) {
        this.properties = properties;
        this.bankResolver = bankResolver;
        this.restClient = RestClient.builder()
            .requestFactory(createRequestFactory(properties))
            .build();
    }

    public SapClient(SapProperties properties) {
        this(properties, new BankCodeResolver(new tools.jackson.databind.ObjectMapper()));
    }

    SapClient(SapProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
        this.bankResolver = new BankCodeResolver(new tools.jackson.databind.ObjectMapper());
    }

    public SapSession login(String username, String password) {
        try {
            ResponseEntity<SapLoginResponse> response = restClient.post()
                .uri(operationUri("Login"))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(new SapLoginRequest(properties.getCompanyDb(), username, password))
                .retrieve()
                .toEntity(SapLoginResponse.class);

            SapLoginResponse body = response.getBody();
            if (body == null || body.sessionId() == null || body.sessionId().isBlank()) {
                throw new AuthenticationServiceException("SAP no devolvió una sesión válida");
            }

            String cookie = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .map(value -> value.split(";", 2)[0])
                .filter(value -> {
                    String upper = value.toUpperCase(Locale.ROOT);
                    return upper.startsWith("B1SESSION=") || upper.startsWith("ROUTEID=");
                })
                .collect(Collectors.joining("; "));
            if (cookie.isBlank()) {
                cookie = "B1SESSION=" + body.sessionId();
            }

            int timeoutMinutes = body.sessionTimeout() == null ? 30 : Math.max(1, body.sessionTimeout());
            return new SapSession(
                body.sessionId(),
                cookie,
                Instant.now().plusSeconds(timeoutMinutes * 60L),
                properties.getCompanyDb(),
                body.version()
            );
        } catch (BadCredentialsException | AuthenticationServiceException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new BadCredentialsException("Credenciales SAP inválidas");
            }
            throw new AuthenticationServiceException("SAP no está disponible", exception);
        } catch (ResourceAccessException exception) {
            throw new AuthenticationServiceException("No se pudo conectar con SAP", exception);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new AuthenticationServiceException("No se pudo iniciar sesión en SAP", exception);
        }
    }

    public void logoutQuietly(SapSession session) {
        if (session == null || session.cookieHeader() == null || session.cookieHeader().isBlank()) {
            return;
        }
        try {
            restClient.post()
                .uri(operationUri("Logout"))
                .header(HttpHeaders.COOKIE, session.cookieHeader())
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientException | IllegalArgumentException exception) {
            log.warn("No se pudo cerrar la sesión remota de SAP; expirará automáticamente");
        }
    }

    public SapVendorPaymentDtos.PaymentListResponse vendorPayments(
        SapSession session,
        LocalDate dateFrom,
        LocalDate dateTo,
        int page,
        int size
    ) {
        return vendorPayments(session, dateFrom, dateTo, page, size, null);
    }

    public SapVendorPaymentDtos.PaymentListResponse vendorPayments(
        SapSession session, LocalDate dateFrom, LocalDate dateTo, int page, int size,
        DiffusionDtos.Region department
    ) {
        return vendorPayments(session, dateFrom, dateTo, page, size, department, "");
    }

    public SapVendorPaymentDtos.PaymentListResponse vendorPayments(
        SapSession session, LocalDate dateFrom, LocalDate dateTo, int page, int size,
        DiffusionDtos.Region department, String bank
    ) {
        return vendorPayments(session, dateFrom, dateTo, page, size, department, bank, "11010501");
    }

    public SapVendorPaymentDtos.PaymentListResponse vendorPayments(
        SapSession session, LocalDate dateFrom, LocalDate dateTo, int page, int size,
        DiffusionDtos.Region department, String bank, String sourceAccount
    ) {
        return vendorPayments(session, dateFrom, dateTo, page, size, department, bank, sourceAccount, Set.of());
    }

    public SapVendorPaymentDtos.PaymentListResponse vendorPayments(
        SapSession session, LocalDate dateFrom, LocalDate dateTo, int page, int size,
        DiffusionDtos.Region department, String bank, String sourceAccount, Set<Long> excluded
    ) {
        requireSourceAccount(sourceAccount);
        bank = bank == null ? "" : bank.trim();
        final String bankCode = bank;
        if (!bank.isEmpty() && bankResolver.catalogs().banks().stream().noneMatch(item -> item.code().equals(bankCode))) {
            throw new SapServiceException(HttpStatus.BAD_REQUEST, "SAP_BANK_INVALID", "Seleccione un banco del catalogo");
        }
        if (department != null) {
            String code = department.code() == null ? "" : department.code().trim().toUpperCase(Locale.ROOT);
            department = bankResolver.catalogs().regions().stream().filter(item -> item.code().equals(code)).findFirst()
                .orElseThrow(() -> new SapServiceException(HttpStatus.BAD_REQUEST, "SAP_DEPARTMENT_INVALID",
                    "Seleccione uno de los nueve departamentos del catalogo"));
        }
        if (department != null || !bank.isEmpty() || !excluded.isEmpty()) {
            return vendorPaymentsWithFilters(session, dateFrom, dateTo, page, size, department, bank, sourceAccount, excluded);
        }
        URI uri = vendorPaymentsUri(dateFrom, dateTo, (long) page * size, size, sourceAccount);
        SapVendorPaymentPage response = getSap(
            uri, session, SapVendorPaymentPage.class, "No se encontraron pagos de proveedores en SAP"
        );
        Map<String, SapVendorPaymentDtos.BusinessPartnerMatch> partners = new HashMap<>();
        Map<String, String> bankNames = new HashMap<>();
        List<SapVendorPaymentDtos.PaymentSummary> items = response == null || response.value() == null
            ? List.of()
            : response.value().stream().peek(payment -> verifySourceAccount(payment, sourceAccount)).map(payment -> toSummary(payment,
                partners.computeIfAbsent(payment.cardCode(), code -> businessPartner(session, code, bankNames))
            )).toList();
        long totalElements = parseTotal(response == null ? null : response.totalCount(), page, size, items.size());
        return paymentListResponse(items, page, size, totalElements, dateFrom, dateTo, null, null, sourceAccount);
    }

    private URI vendorPaymentsUri(LocalDate dateFrom, LocalDate dateTo, long skip, int size, String sourceAccount) {
        String filter = "startswith(CardCode,'PBL') and DocDate ge '" + dateFrom
            + "' and DocDate le '" + dateTo + "' and TransferAccount eq '" + sourceAccount + "'";
        return UriComponentsBuilder.fromUri(operationUri("VendorPayments"))
            .queryParam("$select", VENDOR_PAYMENT_LIST_FIELDS)
            .queryParam("$filter", filter)
            .queryParam("$orderby", "DocDate desc,DocEntry desc")
            .queryParam("$skip", skip)
            .queryParam("$top", size)
            .queryParam("$inlinecount", "allpages")
            .build()
            .encode()
            .toUri();
    }

    private SapVendorPaymentDtos.PaymentListResponse vendorPaymentsWithFilters(
        SapSession session, LocalDate dateFrom, LocalDate dateTo, int page, int size,
        DiffusionDtos.Region department, String bank, String sourceAccount, Set<Long> excluded
    ) {
        Map<String, SapVendorPaymentDtos.BusinessPartnerMatch> partners = new HashMap<>();
        Map<String, String> bankNames = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        List<SapVendorPaymentDtos.PaymentSummary> items = new ArrayList<>();
        long skip = 0;
        long totalElements = 0;
        long firstMatch = (long) page * size;
        // Partner filters must be applied to all SAP pages before counting and slicing.
        while (true) {
            var response = getSap(vendorPaymentsUri(dateFrom, dateTo, skip, 100, sourceAccount), session,
                SapVendorPaymentPage.class, "No se encontraron pagos de proveedores en SAP");
            List<SapVendorPayment> payments = response.value() == null ? List.of() : response.value();
            Long sapTotal = knownTotal(response.totalCount());
            if (payments.isEmpty()) {
                if (sapTotal != null && skip < sapTotal) {
                    throw new SapServiceException(HttpStatus.BAD_GATEWAY, "SAP_PAYMENT_PAGE_INCOMPLETE",
                        "SAP devolvio una pagina incompleta. Consulte nuevamente.");
                }
                break;
            }
            for (var payment : payments) {
                verifySourceAccount(payment, sourceAccount);
                if (!seen.add(payment.docEntry())) {
                    throw new SapServiceException(HttpStatus.BAD_GATEWAY, "SAP_PAYMENT_PAGE_REPEATED",
                        "SAP repitio pagos durante la consulta. Consulte nuevamente.");
                }
                if (excluded.contains(payment.docEntry())) continue;
                var partner = partners.computeIfAbsent(payment.cardCode(), code -> businessPartner(session, code, bankNames));
                if (department != null && (partner.region() == null || !department.code().equals(partner.region().code()))) continue;
                if (!bank.isEmpty()) {
                    partner = partnerForBank(partner, bank);
                    if (partner.bankAccounts().isEmpty()) continue;
                }
                if (totalElements >= firstMatch && items.size() < size) items.add(toSummary(payment, partner));
                totalElements++;
            }
            skip += payments.size();
            if (sapTotal != null && skip >= sapTotal) break;
        }
        return paymentListResponse(List.copyOf(items), page, size, totalElements, dateFrom, dateTo,
            department == null ? null : department.code(), bank.isEmpty() ? null : bank, sourceAccount);
    }

    private SapVendorPaymentDtos.BusinessPartnerMatch partnerForBank(SapVendorPaymentDtos.BusinessPartnerMatch partner, String bank) {
        var accounts = partner.bankAccounts().stream().filter(account -> bank.equals(account.bcpBankCode())).toList();
        Integer selected = partner.selectedBankAccountIndex();
        if (accounts.stream().noneMatch(account -> Objects.equals(account.index(), partner.selectedBankAccountIndex()))) {
            selected = accounts.size() == 1 ? Integer.valueOf(accounts.getFirst().index()) : null;
        }
        return new SapVendorPaymentDtos.BusinessPartnerMatch(partner.found(), partner.cardCode(), partner.cardName(),
            partner.resource(), partner.emailAddress(), partner.documentNumber(), selected, accounts,
            partner.sapCity(), partner.region(), partner.sapDocumentType(), partner.documentType(), partner.documentExtension());
    }

    private SapVendorPaymentDtos.PaymentListResponse paymentListResponse(
        List<SapVendorPaymentDtos.PaymentSummary> items, int page, int size, long totalElements,
        LocalDate dateFrom, LocalDate dateTo, String department, String bank, String sourceAccount
    ) {
        int totalPages = totalElements == 0
            ? 0
            : (int) Math.min(Integer.MAX_VALUE, (totalElements + size - 1) / size);
        return new SapVendorPaymentDtos.PaymentListResponse(
            items,
            page,
            size,
            totalElements,
            totalPages,
            page > 0,
            page + 1 < totalPages,
            dateFrom,
            dateTo,
            department,
            bank,
            sourceAccount
        );
    }

    public SapVendorPaymentDtos.PaymentDetail vendorPayment(SapSession session, long docEntry) {
        URI uri = UriComponentsBuilder.fromUri(operationUri("VendorPayments(" + docEntry + ")"))
            .queryParam("$select", VENDOR_PAYMENT_DETAIL_FIELDS)
            .build()
            .encode()
            .toUri();
        SapVendorPayment payment = getSap(
            uri,
            session,
            SapVendorPayment.class,
            "No existe el pago SAP con DocEntry " + docEntry
        );
        requireSourceAccount(payment.transferAccount());
        SapVendorPaymentDtos.BusinessPartnerMatch partner = businessPartner(session, payment.cardCode(), new HashMap<>());
        return toDetail(payment, partner);
    }

    private void requireSourceAccount(String account) {
        if (bankResolver.catalogs().sourceAccounts().stream().noneMatch(source -> source.sapAccount().equals(account))) {
            throw new SapServiceException(HttpStatus.BAD_REQUEST, "SAP_SOURCE_ACCOUNT_INVALID", "Seleccione una cuenta de origen permitida: BCP LP o BCP SC");
        }
    }

    private void verifySourceAccount(SapVendorPayment payment, String expected) {
        if (!expected.equals(payment.transferAccount())) {
            throw new SapServiceException(HttpStatus.BAD_GATEWAY, "SAP_SOURCE_ACCOUNT_MISMATCH",
                "SAP devolvio un pago con TransferAccount distinto al solicitado");
        }
    }

    private SapVendorPaymentDtos.BusinessPartnerMatch businessPartner(SapSession session, String cardCode,
        Map<String, String> bankNames) {
        if (cardCode == null || cardCode.isBlank()) {
            return new SapVendorPaymentDtos.BusinessPartnerMatch(false, cardCode, null, null,
                null, null, null, List.of());
        }
        String escapedCode = cardCode.replace("'", "''");
        String resource = "/BusinessPartners('" + escapedCode + "')";
        URI uri = UriComponentsBuilder.fromUri(operationUri(resource.substring(1)))
            .queryParam("$select", "CardCode,CardName,EmailAddress,FederalTaxID,U_CITY,U_TIPDOC,DefaultBankCode,DefaultAccount,BPBankAccounts")
            .build()
            .encode()
            .toUri();
        try {
            SapBusinessPartner partner = getSap(
                uri,
                session,
                SapBusinessPartner.class,
                "No existe el socio de negocio " + cardCode
            );
            List<SapVendorPaymentDtos.BankAccount> accounts = new ArrayList<>();
            if (partner.bankAccounts() != null) {
                for (int index = 0; index < partner.bankAccounts().size(); index++) {
                    SapBankAccount account = partner.bankAccounts().get(index);
                    var bank = bankResolver.resolve(account.bankCode(), null);
                    String bankName = bank == null ? bankName(session, account.bankCode(), bankNames) : bank.name();
                    if (bank == null) bank = bankResolver.resolve(account.bankCode(), bankName);
                    accounts.add(new SapVendorPaymentDtos.BankAccount(index, account.bankCode(),
                        account.accountNumber(), account.accountName(), account.state(), account.city(),
                        bankName, bank == null ? null : bank.code()));
                }
            }
            List<SapVendorPaymentDtos.BankAccount> defaults = accounts.stream()
                .filter(account -> partner.defaultAccount() != null
                    && partner.defaultAccount().equals(account.accountNumber())
                    && (partner.defaultBankCode() == null
                        || partner.defaultBankCode().equals(account.bankCode())))
                .toList();
            Integer selectedIndex = null;
            if (defaults.size() == 1) selectedIndex = defaults.getFirst().index();
            else if (accounts.size() == 1) selectedIndex = accounts.getFirst().index();
            return new SapVendorPaymentDtos.BusinessPartnerMatch(
                true,
                partner.cardCode(),
                partner.cardName(),
                resource,
                partner.emailAddress(),
                partner.documentNumber(),
                selectedIndex,
                List.copyOf(accounts),
                partner.city(),
                PaymentRegionMatcher.regionForSapCity(partner.city(), bankResolver.catalogs()),
                partner.documentType(),
                SapDocumentMapper.documentType(partner.documentType(), bankResolver.catalogs()),
                documentExtensionFromCity(partner.city())
            );
        } catch (SapServiceException exception) {
            if (exception.getStatus() == HttpStatus.NOT_FOUND) {
                return new SapVendorPaymentDtos.BusinessPartnerMatch(false, cardCode, null, resource,
                    null, null, null, List.of());
            }
            throw exception;
        }
    }

    private String documentExtensionFromCity(String city) {
        var region = PaymentRegionMatcher.regionForSapCity(city, bankResolver.catalogs());
        return region == null ? "" : region.code();
    }

    private String bankName(SapSession session, String bankCode, Map<String, String> bankNames) {
        if (bankCode == null || bankCode.isBlank()) return null;
        if (bankNames.containsKey(bankCode)) return bankNames.get(bankCode);
        URI uri = UriComponentsBuilder.fromUri(operationUri("Banks"))
            .queryParam("$select", "BankCode,BankName")
            .queryParam("$filter", "BankCode eq '" + bankCode.replace("'", "''") + "'")
            .queryParam("$top", 2).build().encode().toUri();
        String name = null;
        try {
            var response = getSap(uri, session, SapBankPage.class, "No existe el banco en SAP");
            var matches = response.value() == null ? List.<SapBank>of() : response.value().stream()
                .filter(bank -> bankCode.equals(bank.bankCode())).toList();
            // BankCode may be repeated across countries. Never pick an arbitrary bank.
            if (matches.size() == 1) name = matches.getFirst().bankName();
        } catch (SapServiceException exception) {
            if (exception.getStatus() != HttpStatus.NOT_FOUND) throw exception;
        }
        bankNames.put(bankCode, name);
        return name;
    }

    private <T> T getSap(URI uri, SapSession session, Class<T> responseType, String notFoundMessage) {
        requireActiveSession(session);
        try {
            T response = restClient.get()
                .uri(uri)
                .header(HttpHeaders.COOKIE, session.cookieHeader())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(responseType);
            if (response == null) {
                throw new SapServiceException(
                    HttpStatus.BAD_GATEWAY,
                    "SAP_EMPTY_RESPONSE",
                    "SAP devolvió una respuesta vacía"
                );
            }
            session.successfulQuery(Instant.now());
            if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attributes
                && attributes.getResponse() != null) {
                attributes.getResponse().setHeader("X-SAP-Expires-At", session.expiresAt().toString());
            }
            return response;
        } catch (SapServiceException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
                session.expire();
                throw new SapServiceException(
                    HttpStatus.UNAUTHORIZED,
                    "SAP_SESSION_EXPIRED",
                    "La sesión de SAP expiró. Inicie sesión nuevamente.",
                    exception
                );
            }
            if (exception.getStatusCode().value() == 404) {
                throw new SapServiceException(HttpStatus.NOT_FOUND, "SAP_NOT_FOUND", notFoundMessage, exception);
            }
            throw new SapServiceException(
                HttpStatus.BAD_GATEWAY,
                "SAP_REQUEST_FAILED",
                "SAP no pudo completar la consulta",
                exception
            );
        } catch (ResourceAccessException exception) {
            throw new SapServiceException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SAP_UNAVAILABLE",
                "No se pudo conectar con SAP",
                exception
            );
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new SapServiceException(
                HttpStatus.BAD_GATEWAY,
                "SAP_INVALID_RESPONSE",
                "No se pudo interpretar la respuesta de SAP",
                exception
            );
        }
    }

    private void requireActiveSession(SapSession session) {
        if (session == null || session.cookieHeader() == null || session.cookieHeader().isBlank()
            || session.expiresAt() == null || !session.expiresAt().isAfter(Instant.now())) {
            throw new SapServiceException(
                HttpStatus.UNAUTHORIZED,
                "SAP_SESSION_EXPIRED",
                "La sesión de SAP expiró. Inicie sesión nuevamente."
            );
        }
    }

    private SapVendorPaymentDtos.PaymentSummary toSummary(SapVendorPayment payment,
        SapVendorPaymentDtos.BusinessPartnerMatch partner) {
        return new SapVendorPaymentDtos.PaymentSummary(
            payment.docEntry(), payment.docNum(), payment.docType(), payment.docDate(), payment.dueDate(),
            payment.cardCode(), payment.cardName(), normalizeCurrency(payment.docCurrency()),
            payment.docCurrency(), zeroIfNull(payment.transferSum()), payment.transferDate(),
            beneficiaryAccount(partner), payment.reference1(), payment.journalRemarks(), payment.cancelled(),
            payment.authorizationStatus(), partner, payment.transferAccount()
        );
    }

    private SapVendorPaymentDtos.PaymentDetail toDetail(
        SapVendorPayment payment,
        SapVendorPaymentDtos.BusinessPartnerMatch partner
    ) {
        List<SapVendorPaymentDtos.PaymentInvoice> invoices = payment.paymentInvoices() == null
            ? List.of()
            : payment.paymentInvoices().stream().map(invoice -> new SapVendorPaymentDtos.PaymentInvoice(
                invoice.lineNum(), invoice.docEntry(), zeroIfNull(invoice.sumApplied()), invoice.invoiceType(),
                invoice.installmentId(), invoice.documentDate(), invoice.documentNumber(), invoice.taxId(),
                invoice.businessName(), invoice.accountNumber(), zeroIfNull(invoice.documentAmount())
            )).toList();
        return new SapVendorPaymentDtos.PaymentDetail(
            payment.docEntry(), payment.docNum(), payment.docType(), payment.docDate(), payment.dueDate(),
            payment.taxDate(), payment.cardCode(), payment.cardName(), normalizeCurrency(payment.docCurrency()),
            payment.docCurrency(), zeroIfNull(payment.transferSum()), payment.transferDate(),
            beneficiaryAccount(partner), payment.transferReference(), payment.reference1(), payment.reference2(),
            payment.journalRemarks(), payment.cancelled(), payment.authorizationStatus(), payment.branchId(),
            payment.branchName(), invoices, partner, payment.transferAccount()
        );
    }

    private String beneficiaryAccount(SapVendorPaymentDtos.BusinessPartnerMatch partner) {
        return partner.selectedBankAccountIndex() == null ? null : partner.bankAccounts().stream()
            .filter(account -> account.index() == partner.selectedBankAccountIndex())
            .map(SapVendorPaymentDtos.BankAccount::accountNumber).filter(value -> value != null).findFirst().orElse(null);
    }

    private String normalizeCurrency(String currency) {
        return "BS".equalsIgnoreCase(currency) ? "BOB" : currency;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private long parseTotal(Object value, int page, int size, int itemCount) {
        Long total = knownTotal(value);
        if (total != null) return total;
        long currentEnd = (long) page * size + itemCount;
        return itemCount == size ? currentEnd + 1 : currentEnd;
    }

    private Long knownTotal(Object value) {
        if (value instanceof Number number) {
            return number.longValue() >= 0 ? number.longValue() : null;
        }
        if (value != null) {
            try {
                long total = Long.parseLong(value.toString());
                return total >= 0 ? total : null;
            } catch (NumberFormatException ignored) {
                // Some SAP installations omit the count or return a nonnumeric value.
            }
        }
        return null;
    }

    private URI operationUri(String operation) {
        String baseUrl = properties.getBaseUrl().trim().replaceAll("/+$", "");
        baseUrl = baseUrl.replaceFirst("(?i)/Login$", "");
        baseUrl = baseUrl.replaceFirst("(?i)/b1s/v2$", "/b1s/v1");
        if (!baseUrl.matches("(?i)^https?://.+/b1s/v1$")) {
            throw new AuthenticationServiceException("La URL de SAP Service Layer no es válida");
        }
        return URI.create(baseUrl + "/" + operation);
    }

    private static SimpleClientHttpRequestFactory createRequestFactory(SapProperties properties) {
        String expectedHostname = properties.getTlsExpectedHostname() == null
            ? ""
            : properties.getTlsExpectedHostname().trim();
        SSLContext trustContext = createSapTrustContext(properties.getTlsTrustCertificatePath());
        SimpleClientHttpRequestFactory factory;
        if (!properties.isTlsRejectUnauthorized()) {
            factory = new InsecureTlsRequestFactory();
        } else if (!expectedHostname.isBlank() || trustContext != null) {
            factory = new VerifiedTlsRequestFactory(expectedHostname, trustContext);
        } else {
            factory = new SimpleClientHttpRequestFactory();
        }
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        return factory;
    }

    private static SSLContext createSapTrustContext(String certificatePath) {
        if (certificatePath == null || certificatePath.isBlank()) {
            return null;
        }
        Path path = Path.of(certificatePath.trim());
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException("El certificado de confianza SAP no existe o no es legible");
        }
        try (InputStream input = Files.newInputStream(path)) {
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
            if (certificates.isEmpty()) {
                throw new IllegalStateException("El archivo de confianza SAP no contiene certificados X.509");
            }
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (Certificate certificate : certificates) {
                trustStore.setCertificateEntry("sap-" + index++, certificate);
            }
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            );
            trustManagerFactory.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
            return context;
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo cargar el certificado de confianza SAP", exception);
        }
    }

    private record SapLoginRequest(
        @JsonProperty("CompanyDB") String companyDb,
        @JsonProperty("UserName") String username,
        @JsonProperty("Password") String password
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SapLoginResponse(
        @JsonProperty("SessionId") String sessionId,
        @JsonProperty("Version") String version,
        @JsonProperty("SessionTimeout") Integer sessionTimeout
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SapVendorPaymentPage(
        @JsonProperty("odata.count") Object totalCount,
        @JsonProperty("value") List<SapVendorPayment> value
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SapVendorPayment(
        @JsonProperty("DocEntry") long docEntry,
        @JsonProperty("DocNum") long docNum,
        @JsonProperty("DocType") String docType,
        @JsonProperty("DocDate") String docDate,
        @JsonProperty("DueDate") String dueDate,
        @JsonProperty("TaxDate") String taxDate,
        @JsonProperty("CardCode") String cardCode,
        @JsonProperty("CardName") String cardName,
        @JsonProperty("DocCurrency") String docCurrency,
        @JsonProperty("TransferSum") BigDecimal transferSum,
        @JsonProperty("TransferDate") String transferDate,
        @JsonProperty("TransferAccount") String transferAccount,
        @JsonProperty("TransferReference") String transferReference,
        @JsonProperty("Reference1") String reference1,
        @JsonProperty("Reference2") String reference2,
        @JsonProperty("JournalRemarks") String journalRemarks,
        @JsonProperty("Cancelled") String cancelled,
        @JsonProperty("AuthorizationStatus") String authorizationStatus,
        @JsonProperty("BPLID") Integer branchId,
        @JsonProperty("BPLName") String branchName,
        @JsonProperty("PaymentInvoices") List<SapPaymentInvoice> paymentInvoices
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SapPaymentInvoice(
        @JsonProperty("LineNum") int lineNum,
        @JsonProperty("DocEntry") long docEntry,
        @JsonProperty("SumApplied") BigDecimal sumApplied,
        @JsonProperty("InvoiceType") String invoiceType,
        @JsonProperty("InstallmentId") Integer installmentId,
        @JsonProperty("U_FechaFac") String documentDate,
        @JsonProperty("U_NumDoc") String documentNumber,
        @JsonProperty("U_NIT") String taxId,
        @JsonProperty("U_RSocial") String businessName,
        @JsonProperty("U_NumCuenta") String accountNumber,
        @JsonProperty("U_MontoDoc") BigDecimal documentAmount
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SapBusinessPartner(
        @JsonProperty("CardCode") String cardCode,
        @JsonProperty("CardName") String cardName,
        @JsonProperty("EmailAddress") String emailAddress,
        @JsonProperty("FederalTaxID") String documentNumber,
        @JsonProperty("DefaultBankCode") String defaultBankCode,
        @JsonProperty("DefaultAccount") String defaultAccount,
        @JsonProperty("U_CITY") String city,
        @JsonProperty("U_TIPDOC") String documentType,
        @JsonProperty("BPBankAccounts") List<SapBankAccount> bankAccounts
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SapBankAccount(
        @JsonProperty("BankCode") String bankCode,
        @JsonProperty("AccountNo") String accountNumber,
        @JsonProperty("AccountName") String accountName,
        @JsonProperty("State") String state,
        @JsonProperty("City") String city
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SapBankPage(@JsonProperty("value") List<SapBank> value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SapBank(@JsonProperty("BankCode") String bankCode, @JsonProperty("BankName") String bankName) {}

    private static final class VerifiedTlsRequestFactory extends SimpleClientHttpRequestFactory {
        private static final HostnameVerifier DEFAULT_HOSTNAME_VERIFIER =
            HttpsURLConnection.getDefaultHostnameVerifier();
        private final String expectedHostname;
        private final SSLContext trustContext;

        private VerifiedTlsRequestFactory(String expectedHostname, SSLContext trustContext) {
            this.expectedHostname = expectedHostname;
            this.trustContext = trustContext;
        }

        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            super.prepareConnection(connection, httpMethod);
            if (connection instanceof HttpsURLConnection httpsConnection) {
                if (trustContext != null) {
                    httpsConnection.setSSLSocketFactory(trustContext.getSocketFactory());
                }
                if (!expectedHostname.isBlank()) {
                    httpsConnection.setHostnameVerifier((ignoredUrlHostname, session) ->
                        DEFAULT_HOSTNAME_VERIFIER.verify(expectedHostname, session));
                }
            }
        }
    }

    private static final class InsecureTlsRequestFactory extends SimpleClientHttpRequestFactory {
        private static final HostnameVerifier ACCEPT_ANY_HOST = (hostname, session) -> true;
        private static final SSLContext TRUST_ALL_CONTEXT = createTrustAllContext();

        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            super.prepareConnection(connection, httpMethod);
            if (connection instanceof HttpsURLConnection httpsConnection) {
                httpsConnection.setSSLSocketFactory(TRUST_ALL_CONTEXT.getSocketFactory());
                httpsConnection.setHostnameVerifier(ACCEPT_ANY_HOST);
            }
        }

        private static SSLContext createTrustAllContext() {
            try {
                TrustManager[] trustManagers = {new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }
                }};
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trustManagers, new SecureRandom());
                return context;
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("No se pudo preparar TLS para SAP", exception);
            }
        }
    }
}
