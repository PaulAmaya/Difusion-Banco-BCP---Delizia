package com.example.defusion_bcp.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTests {
    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void keepsOnlyLastFourCharactersVisible() {
        assertThat(masker.mask("20150455090318")).isEqualTo("**********0318");
    }

    @Test
    void handlesShortAndEmptyValues() {
        assertThat(masker.mask("123")).isEqualTo("***");
        assertThat(masker.mask(" ")).isEmpty();
        assertThat(masker.mask(null)).isEmpty();
    }
}
