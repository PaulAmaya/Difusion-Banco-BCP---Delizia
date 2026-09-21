package com.example.defusion_bcp.service;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.EOFException;
import static org.assertj.core.api.Assertions.*;

class BankNetworkDiagnosticsTests {
    @Test void includesNetworkCauseChainAndStackLocationsWithoutSecretsOrForgedLines() {
        var exception = new IOException("HTTP/1.1 header parser received no bytes secret-value\nFORGED_LOG",
            new EOFException("EOF reached while reading"));
        String log = BankNetworkDiagnostics.describe(exception, "secret-value");
        assertThat(log).contains("cause[0]=java.io.IOException", "cause[1]=java.io.EOFException",
            "HTTP/1.1 header parser received no bytes", "EOF reached while reading", "  at ", "[REDACTED]");
        assertThat(log).doesNotContain("secret-value", "\nFORGED_LOG");
    }

    @Test void redactsQuotedJsonFieldsEmailAndBankAccountNumbers() {
        String safe = BankNetworkDiagnostics.redact("{\"password\":\"unknown-secret\",\"data\":\"cipher-sensitive\",\"signature\":\"signature-sensitive\",\"accountNumber\":\"123456789012\"} test@example.com");
        assertThat(safe).doesNotContain("unknown-secret", "cipher-sensitive", "signature-sensitive", "123456789012", "test@example.com");
        assertThat(safe).contains("REDACTED");
    }

    @Test void handlesMissingMessagesAndLimitsVeryLongExceptionMessages() {
        assertThat(BankNetworkDiagnostics.redact(null)).isEqualTo("(sin mensaje)");
        assertThat(BankNetworkDiagnostics.redact("x".repeat(5000))).hasSizeLessThan(750);
    }
}
