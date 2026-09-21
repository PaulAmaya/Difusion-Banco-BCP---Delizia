package com.example.defusion_bcp.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class BankCodeResolverTests {
    private final BankCodeResolver resolver = new BankCodeResolver(new ObjectMapper());

    @Test
    void resolvesSapNamesWithLegalSuffixesAndAccents() {
        assertThat(resolver.resolve("BANECO", "  BANCO ECONOMICO S.A.  ").code()).isEqualTo("1016");
        assertThat(resolver.resolve("CREDITO", "Banco de Cr\u00e9dito de Bolivia S.A.").code()).isEqualTo("1005");
        assertThat(resolver.resolve("UNION", "BANCO UNION S.A.M.").code()).isEqualTo("1014");
        assertThat(resolver.resolve("NACIONAL", "Banco Nacional de Bolivia S.A.").code()).isEqualTo("1001");
        assertThat(resolver.resolve("SOL", "Banco Solidario S.A.").code()).isEqualTo("1017");
    }

    @Test
    void keepsKnownBcpCodesAndDoesNotGuessUnknownOrPartialNames() {
        assertThat(resolver.resolve("1005", null).code()).isEqualTo("1005");
        assertThat(resolver.resolve("BANECO", null)).isNull();
        assertThat(resolver.resolve("BANECO", "Banco desconocido")).isNull();
        assertThat(resolver.resolve("UNKNOWN", "Banco Economico Internacional S.A.")).isNull();
        assertThat(resolver.resolve("UNKNOWN", "Economico")).isNull();
    }
}
