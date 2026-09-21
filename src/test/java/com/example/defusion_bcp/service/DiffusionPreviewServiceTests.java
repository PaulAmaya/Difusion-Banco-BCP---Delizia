package com.example.defusion_bcp.service;

import com.example.defusion_bcp.dto.DiffusionDtos;
import com.example.defusion_bcp.dto.SapVendorPaymentDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiffusionPreviewServiceTests {
    private SapClient client;
    private DiffusionPreviewService service;

    @BeforeEach
    void setUp() {
        client = mock(SapClient.class);
        service = new DiffusionPreviewService(client, new ObjectMapper(), "", "", "");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsOneBatchWithBcpAndAchFromTheSameCityAndSumsExactAmounts() {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1005", "LA PAZ", "440.10", "BS", "tNO"));
        when(client.vendorPayment(null, 2)).thenReturn(payment(2, "1014", "La Paz", "365.20", "BS", "tNO"));
        var result = service.preview(null, new DiffusionDtos.PreviewRequest(List.of(
            selection(1, "1005", "LP"), selection(2, "1014", "LP"))));
        assertThat(result.region().cityCode()).isEqualTo(201);
        assertThat(result.payload().get("amount")).isEqualTo(new BigDecimal("805.30"));
        assertThat(result.payload().get("companyId")).isEqualTo(2295);
        assertThat(result.payload().get("sourceAccount")).isEqualTo("2015009988370");
        assertThat(result.payload().get("password")).isEqualTo("");
        assertThat(result.docEntries()).containsExactly(1L, 2L);
        Map<String, Object> spreadsheet = (Map<String, Object>) result.payload().get("spreadsheet");
        var providers = (List<Map<String, Object>>) spreadsheet.get("formProvidersPayments");
        var ach = (List<Map<String, Object>>) spreadsheet.get("formAchPayments");
        assertThat(providers).hasSize(1);
        assertThat(providers.getFirst()).containsEntry("paymentType", "PROV")
            .containsEntry("accountNumber", "00123456").containsEntry("line", 1)
            .containsEntry("secondDetails", "2022");
        assertThat(ach.getFirst()).containsEntry("paymentType", "ACH")
            .containsEntry("bankId", "1014").containsEntry("branchOfficeId", 201)
            .containsEntry("titularName", "Titular SAP").containsEntry("documentType", "T");
        verify(client).vendorPayment(null, 1);
        verify(client).vendorPayment(null, 2);
    }

    @ParameterizedTest
    @CsvSource({
        "'BCPMN/DEBITO Pago de facturas','Pago de facturas'",
        "'bcpMN/debito    Pago a proveedor','Pago a proveedor'",
        "'Compra BCPMN/DEBITO mensual','Compra mensual'",
        "'Pago normal','Pago normal'",
        "'BCPMN/DEBITO ',''"
    })
    void removesTheDebitPrefixFromEveryBankGloss(String source, String expected) {
        assertThat(DiffusionPreviewService.bankGloss(source)).isEqualTo(expected);
        assertThat(DiffusionPreviewService.bankGloss(null)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void removesThePrefixFromProviderGlossAndAchDetailInTheGeneratedPayload() {
        when(client.vendorPayment(null, 1)).thenReturn(paymentWithRemarks(1, "1005", "BCPMN/DEBITO Pago proveedor"));
        when(client.vendorPayment(null, 2)).thenReturn(paymentWithRemarks(2, "1014", "BCPMN/DEBITO Pago ACH"));
        var result = service.preview(null, new DiffusionDtos.PreviewRequest(List.of(
            selection(1, "1005", "LP"), selection(2, "1014", "LP"))));
        var spreadsheet = (Map<String, Object>) result.payload().get("spreadsheet");
        var providers = (List<Map<String, Object>>) spreadsheet.get("formProvidersPayments");
        var ach = (List<Map<String, Object>>) spreadsheet.get("formAchPayments");
        assertThat(providers.getFirst()).containsEntry("glossPayment", "Pago proveedor");
        assertThat(ach.getFirst()).containsEntry("firstDetail", "Pago ACH");
        assertThat(result.payload().toString()).doesNotContainIgnoringCase("BCPMN/DEBITO");
    }

    @Test
    void rejectsDifferentCitiesInTheSameBatch() {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1005", "LA PAZ", "10", "BS", "tNO"));
        when(client.vendorPayment(null, 2)).thenReturn(payment(2, "1014", "SANTA CRUZ", "20", "BS", "tNO"));
        assertThatThrownBy(() -> service.preview(null, new DiffusionDtos.PreviewRequest(List.of(
            selection(1, "1005", "LP"), selection(2, "1014", "SC")))))
            .isInstanceOf(SapServiceException.class).hasMessageContaining("misma region");
    }

    @Test
    void fingerprintIsStableButChangesWhenSapAmountChangesAndIncompleteHeadersCannotBeSent() {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1005", "LA PAZ", "10", "BS", "tNO"));
        var first = previewOne(1, "1005", "LP");
        assertThat(first.fingerprint()).hasSize(64).isEqualTo(previewOne(1, "1005", "LP").fingerprint());
        first.payload().put("cismartApprovers", List.of(Map.of("idc", "99999999-Q-LP", "type", 1)));
        assertThatThrownBy(() -> service.validateForSending(first)).hasMessageContaining("Cabecera incompleta");
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1005", "LA PAZ", "11", "BS", "tNO"));
        assertThat(previewOne(1, "1005", "LP").fingerprint()).isNotEqualTo(first.fingerprint());
    }

    @Test
    void rejectsAnUnknownHeaderCityInsteadOfUsingBankAccountCityOrDocumentExtension() {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1005", "DESCONOCIDA", "10", "BS", "tNO"));
        assertThatThrownBy(() -> previewOne(1, "1005", "LP")).hasMessageContaining("BusinessPartners.U_CITY");
    }

    @Test
    void preventsChangingTheSapRegionInTheBrowser() {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1014", "SANTA CRUZ", "10", "BS", "tNO"));
        assertThatThrownBy(() -> previewOne(1, "1014", "LP")).hasMessageContaining("no coincide");
    }

    @Test
    void blocksBanksNotInTheSuppliedCatalog() {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "UNKNOWN", "LA PAZ", "10", "BS", "tNO"));
        assertThatThrownBy(() -> previewOne(1, "UNKNOWN", "LP")).hasMessageContaining("no tiene equivalencia");
    }

    @Test
    void rejectsCancelledForeignCurrencyAndNonPositiveTransfers() {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1005", "LA PAZ", "10", "BS", "tYES"));
        assertThatThrownBy(() -> previewOne(1, "1005", "LP")).hasMessageContaining("cancelado");
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1005", "LA PAZ", "10", "USD", "tNO"));
        assertThatThrownBy(() -> previewOne(1, "1005", "LP")).hasMessageContaining("otras monedas");
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1005", "LA PAZ", "0", "BS", "tNO"));
        assertThatThrownBy(() -> previewOne(1, "1005", "LP")).hasMessageContaining("importe de transferencia positivo");
    }

    @Test
    void rejectsDuplicatePaymentsBeforeReadingSap() {
        assertThatThrownBy(() -> service.preview(null, new DiffusionDtos.PreviewRequest(List.of(
            selection(1, "1005", "LP"), selection(1, "1005", "LP")))))
            .hasMessageContaining("pagos diferentes");
        verifyNoInteractions(client);
    }

    @Test
    void rejectsAnAccountThatChangedSincePreparation() {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1014", "LA PAZ", "10", "BS", "tNO"));
        assertThatThrownBy(() -> previewOne(1, "1005", "LP")).hasMessageContaining("cambio");
    }

    @Test
    void warnsAboutUnmappedIdentityAndLeavesTheFieldsEmpty() {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1005", "LA PAZ", "10", "BS", "tNO"));
        var result = service.preview(null, new DiffusionDtos.PreviewRequest(List.of(
            new DiffusionDtos.Selection(1, 0, "1005", "00123456", "LP", "", "", "", ""))));
        assertThat(result.warnings()).anyMatch(value -> value.contains("identificacion incompleta"));
        assertThat(result.warnings()).anyMatch(value -> value.contains("No se ha cifrado ni enviado"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesResolvedNumericCodesForAchAndProviderClassificationButValidatesTheSapCode() {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "BANECO", "LA PAZ", "10", "BS", "tNO", "BANCO ECONOMICO S.A."));
        when(client.vendorPayment(null, 2)).thenReturn(payment(2, "CREDITO", "LA PAZ", "20", "BS", "tNO", "BANCO DE CREDITO DE BOLIVIA S.A."));
        var result = service.preview(null, new DiffusionDtos.PreviewRequest(List.of(
            selection(1, "BANECO", "LP"), selection(2, "CREDITO", "LP"))));
        var spreadsheet = (Map<String, Object>) result.payload().get("spreadsheet");
        var ach = (List<Map<String, Object>>) spreadsheet.get("formAchPayments");
        var providers = (List<Map<String, Object>>) spreadsheet.get("formProvidersPayments");
        assertThat(ach).singleElement().satisfies(line -> assertThat(line).containsEntry("bankId", "1016").containsEntry("paymentType", "ACH"));
        assertThat(providers).singleElement().satisfies(line -> assertThat(line).containsEntry("paymentType", "PROV").doesNotContainKey("bankId"));
        assertThatThrownBy(() -> previewOne(1, "1016", "LP")).hasMessageContaining("cambio");
    }

    @ParameterizedTest
    @CsvSource({"1,SC,701", "2,PA,901", "3,BE,801", "4,LP,201", "5,OR,401", "6,CB,301",
        "7,TJ,601", "8,CH,101", "9,PO,501", "10,LP,201"})
    @SuppressWarnings("unchecked")
    void setsBranchOfficeIdAndIdentityFromThePartnerHeader(String city, String region, int branchOfficeId) {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1014", "LA PAZ", "10", "BS", "tNO", null, city, "NIT"));
        var result = service.preview(null, new DiffusionDtos.PreviewRequest(List.of(
            new DiffusionDtos.Selection(1, 0, "1014", "00123456", region, "", "", "", ""))));
        var spreadsheet = (Map<String, Object>) result.payload().get("spreadsheet");
        var ach = (List<Map<String, Object>>) spreadsheet.get("formAchPayments");
        assertThat(ach).singleElement().satisfies(line -> assertThat(line).containsEntry("branchOfficeId", branchOfficeId)
            .containsEntry("documentType", "T").containsEntry("documentExtension", region).containsEntry("documentNumber", "176950021"));
    }

    @Test
    void rejectsChangedSapIdentityInsteadOfSilentlyUsingBrowserValues() {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1014", "LA PAZ", "10", "BS", "tNO", null, "4", "NIT"));
        assertThatThrownBy(() -> service.preview(null, new DiffusionDtos.PreviewRequest(List.of(
            new DiffusionDtos.Selection(1, 0, "1014", "00123456", "LP", "OTHER", "T", "LP", "")))))
            .hasMessageContaining("numero de documento");
        assertThatThrownBy(() -> service.preview(null, new DiffusionDtos.PreviewRequest(List.of(
            new DiffusionDtos.Selection(1, 0, "1014", "00123456", "LP", "", "Q", "LP", "")))))
            .hasMessageContaining("tipo de documento");
        assertThatThrownBy(() -> service.preview(null, new DiffusionDtos.PreviewRequest(List.of(
            new DiffusionDtos.Selection(1, 0, "1014", "00123456", "LP", "", "T", "SC", "")))))
            .hasMessageContaining("extension");
    }

    @ParameterizedTest
    @CsvSource({"11010501,2015009988370", "11010570,20150838488388"})
    void setsTheBankHeaderSourceAccountFromTheActualSapTransferAccount(String source, String bankAccount) {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1014", "LA PAZ", "10", "BS", "tNO", null, "4", "NIT", source));
        var result = service.preview(null, new DiffusionDtos.PreviewRequest(List.of(selection(1, "1014", "LP")), source));
        assertThat(result.payload().get("sourceAccount")).isEqualTo(bankAccount);
    }

    @Test
    void rejectsMixedChangedOrDisallowedSourceAccounts() {
        when(client.vendorPayment(null, 1)).thenReturn(payment(1, "1005", "LA PAZ", "10", "BS", "tNO"));
        when(client.vendorPayment(null, 2)).thenReturn(payment(2, "1014", "LA PAZ", "20", "BS", "tNO", null, "4", "NIT", "11010570"));
        assertThatThrownBy(() -> service.preview(null, new DiffusionDtos.PreviewRequest(List.of(
            selection(1, "1005", "LP"), selection(2, "1014", "LP")), "11010501")))
            .hasMessageContaining("No mezcle cuentas de origen");
        assertThatThrownBy(() -> service.preview(null, new DiffusionDtos.PreviewRequest(List.of(selection(2, "1014", "LP")), "11010501")))
            .hasMessageContaining("TransferAccount");
        assertThatThrownBy(() -> service.preview(null, new DiffusionDtos.PreviewRequest(List.of(selection(1, "1005", "LP")), "11010502")))
            .hasMessageContaining("cuenta de origen permitida");
    }

    private DiffusionDtos.PreviewResponse previewOne(long id, String bank, String region) {
        return service.preview(null, new DiffusionDtos.PreviewRequest(List.of(selection(id, bank, region))));
    }

    private DiffusionDtos.Selection selection(long id, String bank, String region) {
        return new DiffusionDtos.Selection(id, 0, bank, "00123456", region, "176950021", "T", region, "");
    }

    private SapVendorPaymentDtos.PaymentDetail payment(long id, String bank, String city, String amount,
                                                        String currency, String cancelled) {
        return payment(id, bank, city, amount, currency, cancelled, null);
    }

    private SapVendorPaymentDtos.PaymentDetail payment(long id, String bank, String city, String amount,
                                                        String currency, String cancelled, String bankName) {
        String sapCity = switch (city.toUpperCase()) {
            case "LA PAZ" -> "4";
            case "SANTA CRUZ" -> "1";
            case "COCHABAMBA" -> "6";
            default -> "99";
        };
        return payment(id, bank, city, amount, currency, cancelled, bankName, sapCity, "");
    }

    private SapVendorPaymentDtos.PaymentDetail payment(long id, String bank, String city, String amount,
                                                       String currency, String cancelled, String bankName, String sapCity, String sapType) {
        return payment(id, bank, city, amount, currency, cancelled, bankName, sapCity, sapType, "11010501");
    }

    private SapVendorPaymentDtos.PaymentDetail payment(long id, String bank, String city, String amount,
                                                       String currency, String cancelled, String bankName, String sapCity, String sapType,
                                                       String sourceAccount) {
        var account = new SapVendorPaymentDtos.BankAccount(0, bank, "00123456", "Titular SAP", "LP", city, bankName, null);
        var region = PaymentRegionMatcher.regionForSapCity(sapCity, service.catalogs());
        var partner = new SapVendorPaymentDtos.BusinessPartnerMatch(true, "PBL1", "Proveedor SAP",
            "/BusinessPartners('PBL1')", "proveedor@example.com", "176950021", 0, List.of(account),
            sapCity, region, sapType, SapDocumentMapper.documentType(sapType, service.catalogs()), region == null ? "" : region.code());
        var invoice = new SapVendorPaymentDtos.PaymentInvoice(0, 100, new BigDecimal(amount),
            "it_PurchaseInvoice", 1, "2026-07-01", "2022", "176950021", "Proveedor SAP", "00123456", new BigDecimal(amount));
        return new SapVendorPaymentDtos.PaymentDetail(id, id + 1000, "rSupplier", "2026-07-01", null, null,
            "PBL1", "Proveedor SAP", "BOB", currency, new BigDecimal(amount), null, "00123456", null,
            "REF-1", null, "Pago de facturas", cancelled, "pasWithout", 3, "CAL", List.of(invoice), partner, sourceAccount);
    }

    private SapVendorPaymentDtos.PaymentDetail paymentWithRemarks(long id, String bank, String remarks) {
        var account = new SapVendorPaymentDtos.BankAccount(0, bank, "00123456", "Titular SAP", "LP", "LA PAZ", null, null);
        var region = PaymentRegionMatcher.regionForSapCity("4", service.catalogs());
        var partner = new SapVendorPaymentDtos.BusinessPartnerMatch(true, "PBL1", "Proveedor SAP",
            "/BusinessPartners('PBL1')", "proveedor@example.com", "176950021", 0, List.of(account),
            "4", region, "NIT", "T", "LP");
        var invoice = new SapVendorPaymentDtos.PaymentInvoice(0, 100, BigDecimal.TEN,
            "it_PurchaseInvoice", 1, "2026-07-01", "2022", "176950021", "Proveedor SAP", "00123456", BigDecimal.TEN);
        return new SapVendorPaymentDtos.PaymentDetail(id, id + 1000, "rSupplier", "2026-07-01", null, null,
            "PBL1", "Proveedor SAP", "BOB", "BS", BigDecimal.TEN, null, "00123456", null,
            "REF-1", null, remarks, "tNO", "pasWithout", 3, "CAL", List.of(invoice), partner, "11010501");
    }
}
