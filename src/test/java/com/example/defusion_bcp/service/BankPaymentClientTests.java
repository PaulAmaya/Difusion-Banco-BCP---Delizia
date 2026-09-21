package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankPaymentSettings;
import com.example.defusion_bcp.domain.ProcessStatus;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.time.Duration;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.KeyStore;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class BankPaymentClientTests {
    @TempDir Path certificates;
    private BankCryptoService crypto;
    private BankPaymentClient client;
    private BankPaymentSettings settings(boolean enabled, String url) {
        return new BankPaymentSettings(enabled, url, "test-user", "test-basic-password", "TLSv1.2",
            Duration.ofSeconds(2), Duration.ofSeconds(3), "", "");
    }
    @BeforeEach void setup() {
        crypto = mock(BankCryptoService.class);
        client = new BankPaymentClient(settings(true, BankPaymentClient.SANDBOX_URL), new ObjectMapper(), crypto);
    }
    @Test void decryptsSuccessfulBodyAndRequiresCode00AndTransactionId() {
        when(crypto.decrypt("encrypted-body")).thenReturn("{\"Code\":\"00\",\"TransactionId\":\"295760\",\"Message\":\"Registro cargado\"}");
        var result = client.interpret(200, "{\"isOk\":true,\"body\":\"encrypted-body\"}");
        assertThat(result.status()).isEqualTo(ProcessStatus.SENT);
        assertThat(result.transactionId()).isEqualTo("295760");
        when(crypto.decrypt("encrypted-body")).thenReturn("{\"Code\":\"00\"}");
        assertThat(client.interpret(200, result.rawResponse()).status()).isEqualTo(ProcessStatus.UNKNOWN);
        when(crypto.decrypt("encrypted-body")).thenReturn("{\"Code\":\"03\",\"Message\":\"Error\"}");
        assertThat(client.interpret(200, result.rawResponse()).status()).isEqualTo(ProcessStatus.UNKNOWN);
    }
    @Test void decryptsRejectionMessage() {
        when(crypto.decrypt("encrypted-error")).thenReturn("Credenciales del preparador incorrectas");
        var result = client.interpret(200, "{\"isOk\":false,\"message\":\"encrypted-error\",\"body\":null}");
        assertThat(result.status()).isEqualTo(ProcessStatus.REJECTED);
        assertThat(result.message()).contains("preparador incorrectas");
    }
    @Test @SuppressWarnings("unchecked") void reportsTlsFailureWithoutHidingItBehindGenericUnknownMessage() throws Exception {
        var http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new javax.net.ssl.SSLHandshakeException("Remote host terminated handshake"));
        var result = client.sendJava(http, "{}");
        assertThat(result.status()).isEqualTo(ProcessStatus.UNKNOWN);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.rawResponse()).isEmpty();
        assertThat(result.message()).contains("TLS", "No existe un body");
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
    @Test void htmlMissingFlagsAndUndecryptableBodiesRemainUnknown() {
        for (String raw : new String[]{"<html>403 Forbidden</html>", "{}", "{\"isOk\":\"true\"}", "{\"isOk\":true,\"body\":null}"}) {
            assertThat(client.interpret(200, raw).status()).isEqualTo(ProcessStatus.UNKNOWN);
        }
        assertThat(client.interpret(403, "<html>Forbidden</html>").status()).isEqualTo(ProcessStatus.UNKNOWN);
    }
    @Test void bankConnectionFailureCodesStayBlockedEvenIfOuterFlagIsFalse() {
        when(crypto.decrypt("error")).thenReturn("{\"Code\":\"15\",\"Message\":\"No response\"}");
        assertThat(client.interpret(200, "{\"isOk\":false,\"message\":\"error\"}").status()).isEqualTo(ProcessStatus.UNKNOWN);
    }
    @Test void tls12AndRedirectPolicyAreScopedToBankClientAndProductionIsBlocked() {
        var http = client.createClient().javaClient();
        assertThat(http.sslParameters().getProtocols()).containsExactly("TLSv1.2");
        assertThat(http.sslParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        assertThat(http.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(http.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(new BankPaymentClient(settings(false, BankPaymentClient.SANDBOX_URL), new ObjectMapper(), crypto).availability().ready()).isFalse();
        var production = new BankPaymentClient(settings(true, "https://credinetweb.bcp.com.bo/ApiCwV2/api/APIAuth/ProcessMultiple"), new ObjectMapper(), crypto);
        assertThatThrownBy(production::createClient).isInstanceOf(SapServiceException.class);
        assertThat(settings(true, "test").toString()).doesNotContain("test-basic-password", "test-user");
    }
    @Test @SuppressWarnings("unchecked") void postsOnceWithBasicAuthAndJsonAndDoesNotRetryTimeout() throws Exception {
        var http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("simulated"));
        var result = client.sendJava(http, "{\"companyId\":2295,\"data\":\"cipher\",\"signature\":\"signed\"}");
        assertThat(result.status()).isEqualTo(ProcessStatus.UNKNOWN);
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        var request = captor.getValue();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo(BankPaymentClient.SANDBOX_URL);
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(request.headers().firstValue("Authorization").orElseThrow()).isEqualTo("Basic "
            + java.util.Base64.getEncoder().encodeToString("test-user:test-basic-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(request.timeout()).contains(Duration.ofSeconds(3));
    }

    private BankPaymentClient configured(String protocols, String certificate, String password, Duration timeout) {
        return new BankPaymentClient(new BankPaymentSettings(true, BankPaymentClient.SANDBOX_URL, "test-user", "test-basic-password",
            protocols, timeout, Duration.ofSeconds(3), certificate, password), new ObjectMapper(), crypto);
    }

    @Test void tls13OnlyDoesNotAddTls12FallbackAndKeepsServerValidation() {
        try (var http = configured("TLSv1.3", "", "", Duration.ofSeconds(2)).createClient().javaClient()) {
            assertThat(http.sslParameters().getProtocols()).containsExactly("TLSv1.3");
            assertThat(http.sslParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
            assertThat(http.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        }
    }

    @Test @SuppressWarnings("unchecked") void identifiesConnectionClosedBeforeHttpResponseWithoutRetrying() throws Exception {
        var http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new java.io.IOException("HTTP header parser received no bytes", new java.io.EOFException("EOF")));
        var result = client.sendJava(http, "{}");
        assertThat(result.status()).isEqualTo(ProcessStatus.UNKNOWN);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.rawResponse()).isEmpty();
        assertThat(result.message()).contains("cerro la conexion", "No hay codigo HTTP ni body");
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void acceptsWhitespaceAndCaseInConfiguredTlsProtocols() {
        try (var http = configured(" tlsv1.2 , TLSv1.3 , TLSv1.2 ", "", "", Duration.ofSeconds(2)).createClient().javaClient()) {
            assertThat(http.sslParameters().getProtocols()).containsExactly("TLSv1.2", "TLSv1.3");
        }
    }
    @Test void reportsInvalidProtocolTimeoutAndMissingCertificateSpecificallyWithoutNetworkRequests() {
        var invalidProtocol = configured("TLSv1.0", "", "", Duration.ofSeconds(2));
        assertThat(invalidProtocol.availability().ready()).isFalse();
        assertThatThrownBy(invalidProtocol::createClient).hasMessageContaining("BCP_TLS_PROTOCOLS");
        assertThatThrownBy(() -> configured("TLSv1.2", "", "", Duration.ZERO).createClient()).hasMessageContaining("BCP_CONNECT_TIMEOUT");
        var missing = configured("TLSv1.2", certificates.resolve("missing.pfx").toString(), "not-a-basic-password", Duration.ofSeconds(2));
        assertThat(missing.availability().ready()).isFalse();
        assertThatThrownBy(missing::createClient).hasMessageContaining("BCP_AUTH_CERTIFICATE_PATH")
            .hasMessageNotContaining("not-a-basic-password");
    }
    @Test void distinguishesInvalidPfxAndPasswordFromTlsProtocolProblems() throws Exception {
        Path file = certificates.resolve("not-a-pfx.pfx");
        Files.writeString(file, "invalid-pfx-test-fixture");
        assertThatThrownBy(() -> configured("TLSv1.2", file.toString(), "private-test-password", Duration.ofSeconds(2)).createClient())
            .hasMessageContaining("BCP_AUTH_CERTIFICATE_PASSWORD").hasMessageNotContaining("private-test-password");
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, "test-pfx-password".toCharArray());
        try (var output = Files.newOutputStream(file)) { store.store(output, "test-pfx-password".toCharArray()); }
        assertThatThrownBy(() -> configured("TLSv1.2", file.toString(), "wrong-password", Duration.ofSeconds(2)).createClient())
            .hasMessageContaining("BCP_AUTH_CERTIFICATE_PASSWORD");
        assertThatThrownBy(() -> configured("TLSv1.2", file.toString(), "test-pfx-password", Duration.ofSeconds(2)).createClient())
            .hasMessageContaining("no contiene una clave privada");
    }
}
