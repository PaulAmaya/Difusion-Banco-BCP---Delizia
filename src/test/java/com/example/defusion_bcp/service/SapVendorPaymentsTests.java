package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.SapProperties;
import com.example.defusion_bcp.dto.SapVendorPaymentDtos;
import com.example.defusion_bcp.dto.DiffusionDtos;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SapVendorPaymentsTests {
    private HttpServer server;
    private SapClient client;
    private SapSession session;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();

        SapProperties properties = new SapProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/b1s/v1");
        properties.setCompanyDb("TEST_DB");
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(2));
        client = new SapClient(properties);
        session = new SapSession(
            "session-123",
            "B1SESSION=session-123; ROUTEID=.node1",
            Instant.now().plusSeconds(300),
            "TEST_DB",
            "10.0"
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void listsLatestPblVendorPaymentsUsingSapQueryOptions() {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> cookie = new AtomicReference<>();
        server.createContext("/b1s/v1/VendorPayments", exchange -> {
            query.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            send(exchange, 200, """
                {"odata.count":"41","value":[{
                  "DocEntry":93595,"DocNum":1048421,"DocType":"rSupplier",
                  "DocDate":"2026-07-02T00:00:00Z","DueDate":"2026-07-02T00:00:00Z",
                  "CardCode":"PBL12318","CardName":"HIPERSABOR SRL","DocCurrency":"BS",
                  "TransferSum":247665.60,"TransferAccount":"11010501","AuthorizationStatus":"pasWithout","Cancelled":"tNO"
                }]}
                """);
        });
        server.createContext("/b1s/v1/BusinessPartners('PBL12318')", exchange -> send(exchange, 200, """
            {"CardCode":"PBL12318","CardName":"HIPERSABOR SRL",
             "BPBankAccounts":[{"BankCode":"1005","AccountNo":"00123456","City":"LA PAZ"}]}
            """));

        Instant previousExpiry = session.expiresAt();
        SapVendorPaymentDtos.PaymentListResponse response = client.vendorPayments(
            session,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            1,
            20
        );

        assertThat(session.expiresAt()).isAfter(previousExpiry);
        assertThat(query.get())
            .contains("$filter=startswith(CardCode,'PBL') and DocDate ge '2026-07-01' and DocDate le '2026-07-31'")
            .contains("$orderby=DocDate desc,DocEntry desc")
            .contains("$skip=20")
            .contains("$top=20")
            .contains("$inlinecount=allpages");
        assertThat(cookie.get()).contains("B1SESSION=session-123", "ROUTEID=.node1");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(41);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.hasPrevious()).isTrue();
        assertThat(response.hasNext()).isTrue();
        assertThat(response.items().getFirst().docEntry()).isEqualTo(93595);
        assertThat(response.items().getFirst().cardCode()).startsWith("PBL");
        assertThat(response.items().getFirst().currency()).isEqualTo("BOB");
        assertThat(response.items().getFirst().transferAccount()).isEqualTo("00123456");
        assertThat(response.items().getFirst().businessPartner().bankAccounts().getFirst().city()).isEqualTo("LA PAZ");
    }

    @Test
    void loadsVendorPaymentDetailAndMatchesBusinessPartnerByCardCode() {
        server.createContext("/b1s/v1/VendorPayments(93595)", exchange -> send(exchange, 200, """
            {
              "DocEntry":93595,"DocNum":1048421,"DocType":"rSupplier",
              "DocDate":"2026-07-02T00:00:00Z","CardCode":"PBL12318",
              "CardName":"HIPERSABOR SRL","DocCurrency":"BS","TransferSum":247665.60,"TransferAccount":"11010501",
              "PaymentInvoices":[{
                "LineNum":0,"DocEntry":224329,"SumApplied":123832.80,
                "InvoiceType":"it_PurchaseInvoice","U_NumDoc":"2022",
                "U_NIT":"176950021","U_RSocial":"HIPERSABOR SRL",
                "U_NumCuenta":"1000145757","U_MontoDoc":123832.80
              }]
            }
            """));
        server.createContext("/b1s/v1/BusinessPartners('PBL12318')", exchange -> send(exchange, 200, """
            {"CardCode":"PBL12318","CardName":"HIPERSABOR SRL","FederalTaxID":"176950021","U_CITY":"4","U_TIPDOC":"NIT",
             "EmailAddress":"proveedor@example.com","DefaultBankCode":"1014","DefaultAccount":"00456789",
             "BPBankAccounts":[
               {"BankCode":"1005","AccountNo":"00123456","City":"LA PAZ"},
               {"BankCode":"1014","AccountNo":"00456789","AccountName":"HIPERSABOR SRL","City":"LA PAZ"}
             ]}
            """));

        SapVendorPaymentDtos.PaymentDetail payment = client.vendorPayment(session, 93595);

        assertThat(payment.docNum()).isEqualTo(1048421);
        assertThat(payment.paymentInvoices()).hasSize(1);
        assertThat(payment.paymentInvoices().getFirst().documentNumber()).isEqualTo("2022");
        assertThat(payment.businessPartner().found()).isTrue();
        assertThat(payment.businessPartner().cardCode()).isEqualTo(payment.cardCode());
        assertThat(payment.businessPartner().resource())
            .isEqualTo("/BusinessPartners('PBL12318')");
        assertThat(payment.transferAccount()).isEqualTo("00456789");
        assertThat(payment.businessPartner().selectedBankAccountIndex()).isEqualTo(1);
        assertThat(payment.businessPartner().documentNumber()).isEqualTo("176950021");
        assertThat(payment.businessPartner().emailAddress()).isEqualTo("proveedor@example.com");
        assertThat(payment.businessPartner().documentType()).isEqualTo("T");
        assertThat(payment.businessPartner().documentExtension()).isEqualTo("LP");
        assertThat(payment.businessPartner().region().cityCode()).isEqualTo(201);
    }

    @Test
    void doesNotChooseAnArbitraryAccountWhenSeveralAccountsHaveNoDefault() {
        server.createContext("/b1s/v1/VendorPayments(1)", exchange -> send(exchange, 200,
            "{\"DocEntry\":1,\"DocNum\":1001,\"CardCode\":\"PBL1\",\"TransferAccount\":\"11010501\"}"));
        server.createContext("/b1s/v1/BusinessPartners('PBL1')", exchange -> send(exchange, 200, """
            {"CardCode":"PBL1","BPBankAccounts":[
              {"BankCode":"1005","AccountNo":"001"},
              {"BankCode":"1014","AccountNo":"002"}
            ]}
            """));
        var result = client.vendorPayment(session, 1);
        assertThat(result.businessPartner().selectedBankAccountIndex()).isNull();
        assertThat(result.transferAccount()).isNull();
        assertThat(result.businessPartner().bankAccounts()).hasSize(2);
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void filtersAllSapPagesBeforeCountingAndPaginatingAndReusesPartnersWithinTheRequest() {
        List<Integer> offsets = new ArrayList<>();
        AtomicInteger repeatedPartnerReads = new AtomicInteger();
        server.createContext("/b1s/v1/VendorPayments", exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            int skip = querySkip(query);
            offsets.add(skip);
            assertThat(query).contains("$top=100", "$filter=startswith(CardCode,'PBL')", "$orderby=DocDate desc,DocEntry desc");
            send(exchange, 200, new ObjectMapper().writeValueAsString(Map.of("odata.count", "6", "value",
                List.of(paymentRow(skip + 1, skip + 1 == 5 ? "PBL3" : "PBL" + (skip + 1)),
                    paymentRow(skip + 2, skip + 2 == 5 ? "PBL3" : "PBL" + (skip + 2))))));
        });
        partnerFixture("PBL1", "SANTA CRUZ");
        partnerFixture("PBL2", "LA PAZ");
        server.createContext("/b1s/v1/BusinessPartners('PBL3')", exchange -> {
            repeatedPartnerReads.incrementAndGet();
            send(exchange, 200, partnerJson("PBL3", "  la  paz  "));
        });
        partnerFixture("PBL4", "La Paz");
        partnerFixture("PBL6", "DESCONOCIDA");

        var result = client.vendorPayments(session, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
            1, 2, new DiffusionDtos.Region("LP", "La Paz", 201));

        assertThat(offsets).containsExactly(0, 2, 4);
        assertThat(result.totalElements()).isEqualTo(4);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.department()).isEqualTo("LP");
        assertThat(result.hasNext()).isFalse();
        assertThat(result.hasPrevious()).isTrue();
        assertThat(result.items()).extracting(SapVendorPaymentDtos.PaymentSummary::docEntry).containsExactly(4L, 5L);
        assertThat(repeatedPartnerReads).hasValue(1);
    }

    @Test
    void filtersByPartnerHeaderCityAndKeepsTheDefaultAccountRegardlessOfBankAccountCity() {
        server.createContext("/b1s/v1/VendorPayments", exchange -> send(exchange, 200,
            new ObjectMapper().writeValueAsString(Map.of("odata.count", 1, "value", List.of(paymentRow(1, "PBL1"))))));
        server.createContext("/b1s/v1/BusinessPartners('PBL1')", exchange -> send(exchange, 200, """
            {"CardCode":"PBL1","U_CITY":4,"DefaultBankCode":"1005","DefaultAccount":"001",
             "BPBankAccounts":[
                {"BankCode":"1005","AccountNo":"001","City":"SANTA CRUZ"},
                {"BankCode":"1014","AccountNo":"002","City":"LA PAZ"},
                {"BankCode":"1009","AccountNo":"003","City":"COCHABAMBA"}
             ]}
            """));
        var result = departmentPage("LP");
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(payment -> {
            assertThat(payment.transferAccount()).isEqualTo("001");
            assertThat(payment.businessPartner().selectedBankAccountIndex()).isEqualTo(0);
            assertThat(payment.businessPartner().bankAccounts()).hasSize(3);
            assertThat(payment.businessPartner().region().code()).isEqualTo("LP");
        });
    }

    @Test
    void doesNotDuplicatePaymentsOrChooseAnArbitraryAccountWhenTwoAccountsMatch() {
        server.createContext("/b1s/v1/VendorPayments", exchange -> send(exchange, 200,
            new ObjectMapper().writeValueAsString(Map.of("odata.count", 1, "value", List.of(paymentRow(1, "PBL1"))))));
        server.createContext("/b1s/v1/BusinessPartners('PBL1')", exchange -> send(exchange, 200, """
            {"CardCode":"PBL1","U_CITY":"4","BPBankAccounts":[
                {"BankCode":"1005","AccountNo":"001","City":"LA PAZ"},
                {"BankCode":"1014","AccountNo":"002","City":"LA PAZ"}
             ]}
            """));
        var result = departmentPage("LP");
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(payment -> {
            assertThat(payment.transferAccount()).isNull();
            assertThat(payment.businessPartner().selectedBankAccountIndex()).isNull();
            assertThat(payment.businessPartner().bankAccounts()).hasSize(2);
        });
    }

    @Test
    void keepsScanningSmallSapPagesWhenInlineCountIsMissing() {
        List<Integer> offsets = new ArrayList<>();
        server.createContext("/b1s/v1/VendorPayments", exchange -> {
            int skip = querySkip(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            offsets.add(skip);
            send(exchange, 200, new ObjectMapper().writeValueAsString(Map.of("value", skip < 2
                ? List.of(paymentRow(skip + 1, "PBL" + (skip + 1))) : List.of())));
        });
        partnerFixture("PBL1", "SANTA CRUZ");
        partnerFixture("PBL2", "LA PAZ");
        var result = departmentPage("LP");
        assertThat(offsets).containsExactly(0, 1, 2);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items()).extracting(SapVendorPaymentDtos.PaymentSummary::docEntry).containsExactly(2L);
    }

    @Test
    void returnsAnEmptyPageAndZeroFilteredTotalWhenNoHeaderCityMatches() {
        server.createContext("/b1s/v1/VendorPayments", exchange -> send(exchange, 200,
            new ObjectMapper().writeValueAsString(Map.of("odata.count", 1, "value", List.of(paymentRow(1, "PBL1"))))));
        partnerFixture("PBL1", "SANTA CRUZ");
        var result = departmentPage("LP");
        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    void stopsWithAnExplicitErrorIfSapIgnoresSkipInsteadOfLoopingOrReturningAPartialTotal() {
        server.createContext("/b1s/v1/VendorPayments", exchange -> send(exchange, 200,
            new ObjectMapper().writeValueAsString(Map.of("odata.count", 2, "value", List.of(paymentRow(1, "PBL1"))))));
        partnerFixture("PBL1", "LA PAZ");
        assertThatThrownBy(() -> departmentPage("LP")).isInstanceOf(SapServiceException.class).hasMessageContaining("repitio pagos");
    }

    @Test
    void resolvesSapBankCodesByQueryingBanksAndCachesCodesAcrossPartners() {
        AtomicInteger bankReads = new AtomicInteger();
        AtomicReference<String> bankQuery = new AtomicReference<>();
        server.createContext("/b1s/v1/VendorPayments", exchange -> send(exchange, 200,
            new ObjectMapper().writeValueAsString(Map.of("odata.count", 2, "value",
                List.of(paymentRow(1, "PBL1"), paymentRow(2, "PBL2"))))));
        bankPartnerFixture("PBL1", "BANECO");
        bankPartnerFixture("PBL2", "BANECO");
        server.createContext("/b1s/v1/Banks", exchange -> {
            bankReads.incrementAndGet();
            bankQuery.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).contains("B1SESSION=session-123");
            send(exchange, 200, "{\"value\":[{\"BankCode\":\"BANECO\",\"BankName\":\"BANCO ECONOMICO S.A.\"}]}");
        });
        var response = departmentPage("LP");
        assertThat(bankReads).hasValue(1);
        assertThat(bankQuery.get()).contains("$filter=BankCode eq 'BANECO'", "$select=BankCode,BankName");
        assertThat(response.items()).hasSize(2).allSatisfy(payment ->
            assertThat(payment.businessPartner().bankAccounts()).singleElement().satisfies(account -> {
                assertThat(account.bankCode()).isEqualTo("BANECO");
                assertThat(account.bankName()).isEqualTo("BANCO ECONOMICO S.A.");
                assertThat(account.bcpBankCode()).isEqualTo("1016");
            }));
    }

    @Test
    void escapesBankCodeAndPreservesUnmappedBankNamesInDetail() {
        detailFixture();
        bankPartnerFixture("PBL1", "BAN'OTHER");
        server.createContext("/b1s/v1/Banks", exchange -> {
            assertThat(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8))
                .contains("$filter=BankCode eq 'BAN''OTHER'");
            send(exchange, 200, "{\"value\":[{\"BankCode\":\"BAN'OTHER\",\"BankName\":\"OTRO BANCO\"}]}");
        });
        var account = client.vendorPayment(session, 1).businessPartner().bankAccounts().getFirst();
        assertThat(account.bankName()).isEqualTo("OTRO BANCO");
        assertThat(account.bcpBankCode()).isNull();
    }

    @Test
    void leavesMissingAndAmbiguousBankResultsUnresolved() {
        detailFixture();
        bankPartnerFixture("PBL1", "BANECO");
        AtomicReference<String> body = new AtomicReference<>("{\"value\":[]}");
        server.createContext("/b1s/v1/Banks", exchange -> send(exchange, 200, body.get()));
        assertThat(client.vendorPayment(session, 1).businessPartner().bankAccounts().getFirst().bcpBankCode()).isNull();
        body.set("{\"value\":[{\"BankCode\":\"BANECO\",\"BankName\":\"BANCO ECONOMICO S.A.\"},"
            + "{\"BankCode\":\"BANECO\",\"BankName\":\"OTRO BANCO\"}]}");
        assertThat(client.vendorPayment(session, 1).businessPartner().bankAccounts().getFirst().bcpBankCode()).isNull();
    }

    @Test
    void propagatesAnExpiredSessionWhileLookingUpBanks() {
        detailFixture();
        bankPartnerFixture("PBL1", "BANECO");
        server.createContext("/b1s/v1/Banks", exchange -> send(exchange, 401, "{}"));
        assertThatThrownBy(() -> client.vendorPayment(session, 1))
            .isInstanceOf(SapServiceException.class).hasMessageContaining("expir");
    }

    @ParameterizedTest
    @CsvSource({"1,SC,701", "2,PA,901", "3,BE,801", "4,LP,201", "5,OR,401", "6,CB,301",
        "7,TJ,601", "8,CH,101", "9,PO,501", "10,LP,201"})
    void mapsPartnerHeaderCitiesAndDocumentsWithoutUsingTheBankAccountCity(int city, String region, int branchOfficeId) {
        detailFixture();
        server.createContext("/b1s/v1/BusinessPartners('PBL1')", exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            assertThat(query).contains("FederalTaxID,U_CITY,U_TIPDOC").doesNotContain("U_ExtCI");
            send(exchange, 200, new ObjectMapper().writeValueAsString(Map.of("CardCode", "PBL1", "FederalTaxID", "123456",
                "U_CITY", city % 2 == 0 ? Integer.toString(city) : city, "U_TIPDOC", "NIT",
                "BPBankAccounts", List.of(Map.of("BankCode", "1005", "AccountNo", "001234", "City", "LA PAZ")))));
        });
        var partner = client.vendorPayment(session, 1).businessPartner();
        assertThat(partner.sapCity()).isEqualTo(Integer.toString(city));
        assertThat(partner.region().code()).isEqualTo(region);
        assertThat(partner.region().cityCode()).isEqualTo(branchOfficeId);
        assertThat(partner.documentExtension()).isEqualTo(region);
        assertThat(partner.documentType()).isEqualTo("T");
        assertThat(partner.documentNumber()).isEqualTo("123456");
    }

    @Test
    void leavesUnknownOrMissingHeaderFieldsEmptyInsteadOfUsingTheBankAccountCity() {
        detailFixture();
        server.createContext("/b1s/v1/BusinessPartners('PBL1')", exchange -> send(exchange, 200, """
            {"CardCode":"PBL1","U_CITY":"99","U_TIPDOC":"UNKNOWN","BPBankAccounts":[
              {"BankCode":"1005","AccountNo":"001","City":"LA PAZ"}
            ]}
            """));
        var partner = client.vendorPayment(session, 1).businessPartner();
        assertThat(partner.region()).isNull();
        assertThat(partner.documentExtension()).isEmpty();
        assertThat(partner.documentType()).isEmpty();
        assertThat(partner.documentNumber()).isNull();
    }

    @Test
    void filtersBanksAcrossAllSapPagesAndCombinesWithTheHeaderDepartmentUsingResolvedCodes() {
        AtomicInteger bankReads = new AtomicInteger();
        List<Integer> offsets = new ArrayList<>();
        server.createContext("/b1s/v1/VendorPayments", exchange -> {
            int skip = querySkip(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            offsets.add(skip);
            send(exchange, 200, new ObjectMapper().writeValueAsString(Map.of("odata.count", 4, "value",
                List.of(paymentRow(skip + 1, "PBL" + (skip + 1)), paymentRow(skip + 2, "PBL" + (skip + 2))))));
        });
        bankHeaderFixture("PBL1", "1", "BANECO");
        bankHeaderFixture("PBL2", "4", "1005");
        bankHeaderFixture("PBL3", "4", "BANECO");
        bankHeaderFixture("PBL4", "4", "1016");
        server.createContext("/b1s/v1/Banks", exchange -> {
            bankReads.incrementAndGet();
            send(exchange, 200, "{\"value\":[{\"BankCode\":\"BANECO\",\"BankName\":\"BANCO ECONOMICO S.A.\"}]}");
        });
        var from = LocalDate.of(2026, 7, 1);
        var to = LocalDate.of(2026, 7, 31);
        var bankOnly = client.vendorPayments(session, from, to, 1, 2, null, "1016");
        assertThat(bankOnly.totalElements()).isEqualTo(3);
        assertThat(bankOnly.totalPages()).isEqualTo(2);
        assertThat(bankOnly.bank()).isEqualTo("1016");
        assertThat(bankOnly.department()).isNull();
        assertThat(bankOnly.items()).extracting(SapVendorPaymentDtos.PaymentSummary::docEntry).containsExactly(4L);
        assertThat(offsets).containsExactly(0, 2);
        assertThat(bankReads).hasValue(1);
        var combined = client.vendorPayments(session, from, to, 0, 10,
            new DiffusionDtos.Region("LP", "La Paz", 201), "1016");
        assertThat(combined.totalElements()).isEqualTo(2);
        assertThat(combined.items()).extracting(SapVendorPaymentDtos.PaymentSummary::docEntry).containsExactly(3L, 4L);
        assertThat(combined.items()).allSatisfy(payment -> assertThat(payment.businessPartner().bankAccounts())
            .allSatisfy(account -> assertThat(account.bcpBankCode()).isEqualTo("1016")));
        var noMatch = client.vendorPayments(session, from, to, 0, 10, null, "1009");
        assertThat(noMatch.totalElements()).isZero();
        assertThat(noMatch.items()).isEmpty();
    }

    @Test
    void scopesBeneficiaryAccountsToTheChosenBankWithoutDuplicatingPaymentsOrChoosingAnArbitraryAccount() {
        server.createContext("/b1s/v1/VendorPayments", exchange -> send(exchange, 200,
            new ObjectMapper().writeValueAsString(Map.of("odata.count", 1, "value", List.of(paymentRow(1, "PBL1"))))));
        AtomicReference<String> defaultAccount = new AtomicReference<>("001");
        server.createContext("/b1s/v1/BusinessPartners('PBL1')", exchange -> send(exchange, 200,
            new ObjectMapper().writeValueAsString(Map.of("CardCode", "PBL1", "U_CITY", "4", "DefaultAccount", defaultAccount.get(),
                "BPBankAccounts", List.of(
                    Map.of("BankCode", "1005", "AccountNo", "001"),
                    Map.of("BankCode", "1016", "AccountNo", "002"),
                    Map.of("BankCode", "1016", "AccountNo", "003"))))));
        var from = LocalDate.of(2026, 7, 1);
        var to = LocalDate.of(2026, 7, 31);
        var scoped = client.vendorPayments(session, from, to, 0, 10, null, "1016");
        assertThat(scoped.totalElements()).isEqualTo(1);
        assertThat(scoped.items()).singleElement().satisfies(payment -> {
            assertThat(payment.transferAccount()).isNull();
            assertThat(payment.businessPartner().selectedBankAccountIndex()).isNull();
            assertThat(payment.businessPartner().bankAccounts()).extracting(SapVendorPaymentDtos.BankAccount::index).containsExactly(1, 2);
            assertThat(payment.businessPartner().documentExtension()).isEqualTo("LP");
        });
        defaultAccount.set("002");
        var withDefault = client.vendorPayments(session, from, to, 0, 10, null, "1016");
        assertThat(withDefault.items().getFirst().businessPartner().selectedBankAccountIndex()).isEqualTo(1);
        assertThat(withDefault.items().getFirst().transferAccount()).isEqualTo("002");
        var unique = client.vendorPayments(session, from, to, 0, 10, null, "1005");
        assertThat(unique.items().getFirst().businessPartner().selectedBankAccountIndex()).isEqualTo(0);
        assertThat(unique.items().getFirst().transferAccount()).isEqualTo("001");
    }

    @ParameterizedTest
    @CsvSource({"11010501", "11010570"})
    void appliesTheExactSourceAccountInSapAndKeepsSourceSeparateFromBeneficiary(String source) {
        server.createContext("/b1s/v1/VendorPayments", exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            assertThat(query).contains("and TransferAccount eq '" + source + "'")
                .doesNotContain("BCP LP", "BCP SC", "2015009988370", "20150838488388");
            var row = new java.util.HashMap<>(paymentRow(1, "PBL1"));
            row.put("TransferAccount", source);
            send(exchange, 200, new ObjectMapper().writeValueAsString(Map.of("odata.count", 1, "value", List.of(row))));
        });
        partnerFixture("PBL1", "LA PAZ");
        var response = client.vendorPayments(session, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
            0, 20, null, "", source);
        assertThat(response.sourceAccount()).isEqualTo(source);
        assertThat(response.items().getFirst().sapTransferAccount()).isEqualTo(source);
        assertThat(response.items().getFirst().transferAccount()).isEqualTo("001234");
    }

    @Test
    void rejectsOtherSourcesOrQueryInjectionBeforeContactingSapAndRejectsDisallowedDetail() {
        for (String source : List.of("", "11010502", "11010501' or 1 eq 1", "2015009988370")) {
            assertThatThrownBy(() -> client.vendorPayments(session, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                0, 20, null, "", source)).hasMessageContaining("cuenta de origen permitida");
        }
        server.createContext("/b1s/v1/VendorPayments(1)", exchange -> send(exchange, 200,
            "{\"DocEntry\":1,\"DocNum\":1001,\"CardCode\":\"PBL1\",\"TransferAccount\":\"11010502\"}"));
        assertThatThrownBy(() -> client.vendorPayment(session, 1)).hasMessageContaining("cuenta de origen permitida");
    }

    @Test
    void failsExplicitlyIfSapReturnsAPaymentOutsideTheRequestedSourceAccount() {
        server.createContext("/b1s/v1/VendorPayments", exchange -> send(exchange, 200,
            new ObjectMapper().writeValueAsString(Map.of("odata.count", 1, "value", List.of(paymentRow(1, "PBL1"))))));
        assertThatThrownBy(() -> client.vendorPayments(session, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
            0, 20, null, "", "11010570")).hasMessageContaining("TransferAccount distinto");
    }

    private void bankHeaderFixture(String cardCode, String city, String bank) {
        server.createContext("/b1s/v1/BusinessPartners('" + cardCode + "')", exchange -> send(exchange, 200,
            new ObjectMapper().writeValueAsString(Map.of("CardCode", cardCode, "U_CITY", city,
                "BPBankAccounts", List.of(Map.of("BankCode", bank, "AccountNo", "001234"))))));
    }

    @Test
    void backendCombinesCityBankAndSentExclusionsBeforePaginationUsingSapBankNames() {
        var mapper = new ObjectMapper();
        server.createContext("/b1s/v1/VendorPayments", exchange -> {
            int skip = querySkip(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            send(exchange, 200, mapper.writeValueAsString(Map.of("odata.count", 6, "value", List.of(
                paymentRow(skip + 1, "PBL" + (skip + 1)), paymentRow(skip + 2, "PBL" + (skip + 2))))));
        });
        for (int id = 1; id <= 6; id++) {
            String code = "PBL" + id;
            String city = id == 1 ? "1" : id == 4 ? "10" : id == 6 ? "99" : "4";
            String bank = id == 5 ? "1005" : "BANECO";
            server.createContext("/b1s/v1/BusinessPartners('" + code + "')", exchange -> send(exchange, 200,
                mapper.writeValueAsString(Map.of("CardCode", code, "U_CITY", city, "BPBankAccounts", List.of(
                    Map.of("BankCode", bank, "AccountNo", "000123", "City", "SANTA CRUZ"))))));
        }
        AtomicInteger bankReads = new AtomicInteger();
        server.createContext("/b1s/v1/Banks", exchange -> {
            bankReads.incrementAndGet();
            send(exchange, 200, "{\"value\":[{\"BankCode\":\"BANECO\",\"BankName\":\"BANCO ECONOMICO S.A.\"}]}");
        });
        for (int page = 0; page < 2; page++) {
            var result = client.vendorPayments(session, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), page, 1,
                new DiffusionDtos.Region(" lp ", "untrusted label", 999), " 1016 ", "11010501", java.util.Set.of(3L));
            assertThat(result.totalElements()).isEqualTo(2);
            assertThat(result.totalPages()).isEqualTo(2);
            assertThat(result.department()).isEqualTo("LP");
            assertThat(result.bank()).isEqualTo("1016");
            assertThat(result.items()).extracting(SapVendorPaymentDtos.PaymentSummary::docEntry).containsExactly(page == 0 ? 2L : 4L);
            assertThat(result.items().getFirst().businessPartner().bankAccounts().getFirst().bcpBankCode()).isEqualTo("1016");
        }
        assertThat(bankReads).hasValue(2);
    }

    @Test
    void rejectsUnknownCityAndBankFiltersAtTheServiceBoundaryBeforeSapRequests() {
        var date = LocalDate.of(2026, 9, 1);
        assertThatThrownBy(() -> client.vendorPayments(session, date, date, 0, 20, null, "INVALID", "11010501"))
            .hasMessageContaining("banco del catalogo");
        assertThatThrownBy(() -> client.vendorPayments(session, date, date, 0, 20,
            new DiffusionDtos.Region("UNKNOWN", "unknown", 999), "", "11010501"))
            .hasMessageContaining("nueve departamentos");
    }

    @Test
    void excludesBlockedDocumentsBeforeCountingAndPagingAcrossAllSapPages() {
        server.createContext("/b1s/v1/VendorPayments", exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            int skip = querySkip(query);
            var rows = skip == 0 ? List.of(paymentRow(1, "PBL1"), paymentRow(2, "PBL2"))
                : List.of(paymentRow(3, "PBL3"), paymentRow(4, "PBL4"));
            send(exchange, 200, new ObjectMapper().writeValueAsString(Map.of("odata.count", 4, "value", rows)));
        });
        partnerFixture("PBL2", "LA PAZ");
        partnerFixture("PBL4", "LA PAZ");
        for (int page = 0; page < 2; page++) {
            var result = client.vendorPayments(session, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                page, 1, null, "", "11010501", java.util.Set.of(1L, 3L));
            assertThat(result.totalElements()).isEqualTo(2);
            assertThat(result.totalPages()).isEqualTo(2);
            assertThat(result.items()).extracting(SapVendorPaymentDtos.PaymentSummary::docEntry)
                .containsExactly(page == 0 ? 2L : 4L);
        }
        var empty = client.vendorPayments(session, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
            0, 20, null, "", "11010501", java.util.Set.of(1L, 2L, 3L, 4L));
        assertThat(empty.items()).isEmpty();
        assertThat(empty.totalElements()).isZero();
    }

    private void detailFixture() {
        server.createContext("/b1s/v1/VendorPayments(1)", exchange -> send(exchange, 200,
            new ObjectMapper().writeValueAsString(paymentRow(1, "PBL1"))));
    }

    private void bankPartnerFixture(String cardCode, String bankCode) {
        server.createContext("/b1s/v1/BusinessPartners('" + cardCode + "')", exchange -> send(exchange, 200,
            new ObjectMapper().writeValueAsString(Map.of("CardCode", cardCode, "U_CITY", "4", "U_TIPDOC", "NIT", "BPBankAccounts",
                List.of(Map.of("BankCode", bankCode, "AccountNo", "001234", "City", "LA PAZ"))))));
    }

    private SapVendorPaymentDtos.PaymentListResponse departmentPage(String code) {
        return client.vendorPayments(session, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
            0, 20, new DiffusionDtos.Region(code, "La Paz", 201));
    }

    private int querySkip(String query) {
        return java.util.Arrays.stream(query.split("&")).filter(value -> value.startsWith("$skip="))
            .map(value -> Integer.parseInt(value.substring(6))).findFirst().orElseThrow();
    }

    private Map<String, Object> paymentRow(long id, String cardCode) {
        return Map.of("DocEntry", id, "DocNum", id + 1000, "CardCode", cardCode,
            "CardName", "Proveedor " + id, "DocCurrency", "BS", "TransferSum", 10, "TransferAccount", "11010501");
    }

    private void partnerFixture(String cardCode, String city) {
        server.createContext("/b1s/v1/BusinessPartners('" + cardCode + "')", exchange -> send(exchange, 200, partnerJson(cardCode, city)));
    }

    private String partnerJson(String cardCode, String city) {
        String cityValue = city.trim().replaceAll("\\s+", " ").equalsIgnoreCase("LA PAZ") ? "4"
            : city.equalsIgnoreCase("SANTA CRUZ") ? "1" : "99";
        return new ObjectMapper().writeValueAsString(Map.of("CardCode", cardCode, "U_CITY", cityValue,
            "BPBankAccounts", List.of(Map.of("BankCode", "1005", "AccountNo", "001234", "City", city))));
    }
}
