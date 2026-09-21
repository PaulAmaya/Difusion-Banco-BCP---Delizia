package com.example.defusion_bcp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class SapDocumentMapperTests {
    private final BankCodeResolver resolver = new BankCodeResolver(new ObjectMapper());

    @ParameterizedTest
    @CsvSource({"OTRO,O", "PASAPORTE,P", "CI,Q", "C.I.,Q", "RUC,R", "NIT,T", "ID FISCAL,U", "CODIGO GENERICO BANCO,W"})
    void mapsAllSapDocumentTypesAndAcceptsBankCodes(String sap, String code) {
        assertThat(SapDocumentMapper.documentType("  " + sap.toLowerCase() + "  ", resolver.catalogs())).isEqualTo(code);
        assertThat(SapDocumentMapper.documentType(code, resolver.catalogs())).isEqualTo(code);
    }

    @Test
    void doesNotInventDocumentTypesOrUse999ForAnUnknownSapCity() {
        assertThat(SapDocumentMapper.documentType(null, resolver.catalogs())).isEmpty();
        assertThat(SapDocumentMapper.documentType("UNKNOWN", resolver.catalogs())).isEmpty();
        assertThat(PaymentRegionMatcher.regionForSapCity("99", resolver.catalogs())).isNull();
        assertThat(PaymentRegionMatcher.regionForSapCity(null, resolver.catalogs())).isNull();
        assertThat(resolver.catalogs().notApplicableCityCode()).isEqualTo(999);
    }
}
