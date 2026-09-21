package com.example.defusion_bcp.service;

import com.example.defusion_bcp.dto.DiffusionDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentRegionMatcherTests {
    @ParameterizedTest
    @CsvSource({"CH,Chuquisaca,101", "LP,La Paz,201", "CB,Cochabamba,301", "OR,Oruro,401",
        "PO,Potosi,501", "TJ,Tarija,601", "SC,Santa Cruz,701", "BE,Beni,801", "PA,Pando,901"})
    void mapsAllNineDepartments(String code, String name, int cityCode) {
        var region = new DiffusionDtos.Region(code, name, cityCode);
        assertThat(PaymentRegionMatcher.matchesCity("  " + name.toUpperCase() + "  ", region)).isTrue();
        assertThat(PaymentRegionMatcher.matchesCity(code, region)).isTrue();
        assertThat(PaymentRegionMatcher.matchesCity(Integer.toString(cityCode), region)).isTrue();
        assertThat(PaymentRegionMatcher.matchesCity(null, region)).isFalse();
        assertThat(PaymentRegionMatcher.matchesCity("Ciudad desconocida", region)).isFalse();
    }

    @Test
    void normalizesAccentsCaseAndRepeatedSpacesUsingTheSharedCatalog() throws IOException {
        try (var stream = getClass().getResourceAsStream("/bank/payment-catalogs.json")) {
            var catalog = new ObjectMapper().readValue(stream, DiffusionDtos.Catalogs.class);
            assertThat(catalog.regions()).hasSize(9);
            assertThat(PaymentRegionMatcher.regionForCity("potos\u00ed", catalog.regions()).code()).isEqualTo("PO");
            assertThat(PaymentRegionMatcher.regionForCity("  la   paz ", catalog.regions()).cityCode()).isEqualTo(201);
            assertThat(PaymentRegionMatcher.regionForCity("SN", catalog.regions())).isNull();
        }
    }
}
