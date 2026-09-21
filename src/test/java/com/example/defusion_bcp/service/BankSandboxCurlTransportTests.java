package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankPaymentSettings;
import com.example.defusion_bcp.domain.ProcessStatus;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankSandboxCurlTransportTests {
    private BankPaymentSettings settings(String url, String tls, boolean enabled) {
        return new BankPaymentSettings(true, url, "test-user", "test-basic-secret", tls,
            Duration.ofSeconds(2), Duration.ofSeconds(3), "/run/bcp-auth/API_DESA.pfx", "test-pfx-secret", enabled);
    }
    private BankPaymentSettings settings() { return settings(BankPaymentClient.SANDBOX_URL, "TLSv1.2", true); }
    private Process process(String out, String err, int exit, ByteArrayOutputStream input) throws Exception {
        var process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8)));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(err.getBytes(StandardCharsets.UTF_8)));
        when(process.getOutputStream()).thenReturn(input);
        when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(process.exitValue()).thenReturn(exit);
        return process;
    }
    private String trailer(int code) { return "\nBCP_CURL_HTTP_STATUS:" + code + "\nBCP_CURL_VERIFY:0\n"; }
    private String tls() { return "* SSL connection using TLSv1.2 / AES256-GCM-SHA384 / UNDEF / UNDEF\n"; }

    @Test void compatibilityCannotBeUsedForProductionTls13OrWithoutOptIn() {
        assertThatThrownBy(() -> new BankSandboxCurlTransport(settings("https://production.example/", "TLSv1.2", true)))
            .isInstanceOf(SapServiceException.class);
        assertThatThrownBy(() -> new BankSandboxCurlTransport(settings(BankPaymentClient.SANDBOX_URL, "TLSv1.3", true)))
            .isInstanceOf(SapServiceException.class);
        assertThatThrownBy(() -> new BankSandboxCurlTransport(settings(BankPaymentClient.SANDBOX_URL, "TLSv1.2", false)))
            .isInstanceOf(SapServiceException.class);
    }
    @Test void secretsOnlyUseStdinAndConfigCannotInjectOptions() {
        var transport = new BankSandboxCurlTransport(settings());
        assertThat(BankSandboxCurlTransport.command()).containsExactly("curl", "--disable", "--config", "-");
        String config = transport.configuration("{\"data\":\"cipher\",\"signature\":\"signed\"}", false);
        assertThat(config).contains("tlsv1.2\n", "tls-max = \"1.2\"", "ciphers = \"AES256-GCM-SHA384\"",
            "retry = 0", "max-redirs = 0", "cert-type = \"P12\"", "data-binary = \"{\\\"data\\\":\\\"cipher\\\"");
        assertThat(config).doesNotContain("insecure", "location", "trace", "keylog");
        assertThat(BankSandboxCurlTransport.quote("x\"\ninsecure\nurl=evil\\y")).isEqualTo("\"x\\\"\\ninsecure\\nurl=evil\\\\y\"");
        assertThatThrownBy(() -> BankSandboxCurlTransport.quote("x\0y")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void probeNeverSendsPaymentOrBasicAuth() {
        String config = new BankSandboxCurlTransport(settings()).configuration("", true);
        assertThat(config).contains("head\n", "cert-type = \"P12\"");
        assertThat(config).doesNotContain("data-binary", "request = \"POST\"", "test-basic-secret", "test-user", "\nbasic\n");
    }
    @Test void receivesHttp200AndReusesExistingBodyDecryption() throws Exception {
        var input = new ByteArrayOutputStream();
        var calls = new AtomicInteger();
        var process = process("{\"isOk\":true,\"body\":\"cipher-body\"}" + trailer(200), tls(), 0, input);
        var transport = new BankSandboxCurlTransport(settings(), command -> { calls.incrementAndGet(); return process; });
        var crypto = mock(BankCryptoService.class);
        when(crypto.decrypt("cipher-body")).thenReturn("{\"Code\":\"00\",\"TransactionId\":\"T1\",\"Message\":\"Preparado\"}");
        var client = new BankPaymentClient(settings(), new ObjectMapper(), crypto);
        var result = client.send(new BankPaymentClient.PreparedClient(null, transport), "{\"companyId\":2295,\"data\":\"cipher\",\"signature\":\"signed\"}");
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.status()).isEqualTo(ProcessStatus.SENT);
        assertThat(result.transactionId()).isEqualTo("T1");
        assertThat(input.toString(StandardCharsets.UTF_8)).contains("data-binary", "companyId", "Content-Type: application/json");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void decryptsMessageOnBusinessRejectionEvenWithHttp200() throws Exception {
        var process = process("{\"isOk\":false,\"message\":\"cipher-error\",\"body\":null}" + trailer(200), tls(), 0, new ByteArrayOutputStream());
        var transport = new BankSandboxCurlTransport(settings(), command -> process);
        var crypto = mock(BankCryptoService.class);
        when(crypto.decrypt("cipher-error")).thenReturn("Cuenta no habilitada");
        var result = new BankPaymentClient(settings(), new ObjectMapper(), crypto)
            .send(new BankPaymentClient.PreparedClient(null, transport), "{}");
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.status()).isEqualTo(ProcessStatus.REJECTED);
        assertThat(result.message()).isEqualTo("Cuenta no habilitada");
    }
    @Test void incompleteResponsesStayUnknownWithoutRetryOrStderrDisclosure() throws Exception {
        var calls = new AtomicInteger();
        var process = process(trailer(000), "> Authorization: Basic sensitive-basic\ncurl: test-pfx-secret\n", 35, new ByteArrayOutputStream());
        var transport = new BankSandboxCurlTransport(settings(), command -> { calls.incrementAndGet(); return process; });
        var result = new BankPaymentClient(settings(), new ObjectMapper(), mock(BankCryptoService.class))
            .send(new BankPaymentClient.PreparedClient(null, transport), "{}");
        assertThat(result.status()).isEqualTo(ProcessStatus.UNKNOWN);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.message()).contains("curlExit=35", "TLS negotiation")
            .doesNotContain("sensitive-basic", "test-pfx-secret");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void responseMetadataIsValidatedAndBodyIsPreservedExactly() throws Exception {
        String body = "{\"isOk\":false,\"message\":\"BCP_CURL_HTTP_STATUS:000\"}\n";
        var response = BankSandboxCurlTransport.parse(0, body + trailer(403), tls());
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body()).isEqualTo(body);
        assertThat(response.protocol()).isEqualTo("TLSv1.2");
        assertThat(response.cipher()).isEqualTo(BankSandboxCurlTransport.CIPHER);
        assertThatThrownBy(() -> BankSandboxCurlTransport.parse(0, body + "\nBCP_CURL_HTTP_STATUS:200\nBCP_CURL_VERIFY:20\n", tls()))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> BankSandboxCurlTransport.parse(0, body + trailer(0), tls())).isInstanceOf(IOException.class);
    }
    @Test void deadlineDestroysProcessAndDoesNotRetry() throws Exception {
        var process = process("", "", 0, new ByteArrayOutputStream());
        when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);
        when(process.isAlive()).thenReturn(true);
        var calls = new AtomicInteger();
        var transport = new BankSandboxCurlTransport(settings(), command -> { calls.incrementAndGet(); return process; });
        assertThatThrownBy(() -> transport.send("{}")).isInstanceOf(IOException.class).hasMessageContaining("timeout");
        verify(process, atLeastOnce()).destroyForcibly();
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void oversizedResponsesAreStopped() throws Exception {
        var process = process("x".repeat(1_000_513), "", 0, new ByteArrayOutputStream());
        var transport = new BankSandboxCurlTransport(settings(), command -> process);
        assertThatThrownBy(() -> transport.send("{}")).isInstanceOf(IOException.class);
        verify(process, atLeastOnce()).destroyForcibly();
    }
    @Test void runtimeMustUseOpenSslNotSchannel() throws Exception {
        var process = process("curl 8.0 Schannel", "", 0, new ByteArrayOutputStream());
        assertThatThrownBy(() -> new BankSandboxCurlTransport(settings(), command -> process).validateRuntime())
            .isInstanceOf(SapServiceException.class).hasMessageContaining("OpenSSL");
    }
}
