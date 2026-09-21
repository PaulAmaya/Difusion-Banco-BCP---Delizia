package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankPaymentSettings;
import com.example.defusion_bcp.domain.ProcessStatus;
import java.net.http.*;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankStatementClientTests {
    BankCryptoService crypto;
    BankStatementClient bank;
    BankPaymentClient payments;
    BankPaymentSettings settings(boolean legacy) {
        return new BankPaymentSettings(true, BankPaymentClient.SANDBOX_URL, "test-user", "test-basic-password", "TLSv1.2",
            Duration.ofSeconds(2), Duration.ofSeconds(3), "/run/bcp-auth/API_DESA.pfx", "test-pfx-password", legacy);
    }
    @BeforeEach void setup() {
        crypto = mock(BankCryptoService.class); payments = mock(BankPaymentClient.class);
        bank = new BankStatementClient(settings(false), payments, new ObjectMapper(), crypto);
    }
    @Test void http200WithFalseDecryptsOnlyMessageAndKeepsBodyNull() {
        when(crypto.decrypt("encrypted-message")).thenReturn("Periodo no habilitado");
        var response = bank.interpret(200, "{\"isOk\":false,\"message\":\"encrypted-message\",\"body\":null}");
        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.isOk()).isFalse();
        assertThat(response.status()).isEqualTo(ProcessStatus.REJECTED);
        assertThat(response.decryptedMessage()).isEqualTo("Periodo no habilitado");
        assertThat(response.decryptedBody()).isNull();
        verify(crypto, times(1)).decrypt("encrypted-message");
        verifyNoMoreInteractions(crypto);
    }
    @Test void http200WithTrueDecryptsBodyWithoutRequiringPaymentTransactionFields() {
        when(crypto.decrypt("encrypted-body")).thenReturn("{\"movements\":[]}");
        var response = bank.interpret(200, "{\"isOk\":true,\"message\":null,\"body\":\"encrypted-body\"}");
        assertThat(response.status()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.decryptedBody()).isEqualTo("{\"movements\":[]}");
        assertThat(response.decryptedMessage()).isNull();
    }
    @Test void badHttpFlagsAndMissingEncryptedValuesRemainVisibleAsFailures() {
        for (String raw : new String[]{"{}", "<html>error</html>", "{\"isOk\":\"true\"}",
            "{\"isOk\":true,\"body\":null}", "{\"isOk\":false,\"message\":null}"}) {
            assertThat(bank.interpret(200, raw).status()).isEqualTo(ProcessStatus.FAILED);
        }
        var forbidden = bank.interpret(403, "<html>Forbidden</html>");
        assertThat(forbidden.httpStatus()).isEqualTo(403);
        assertThat(forbidden.rawResponse()).contains("Forbidden");
        verifyNoInteractions(crypto);
    }
    @Test void preservesRawResponseWhenDecryptionFails() {
        when(crypto.decrypt("bad-cipher")).thenThrow(new CryptoOperationException("test"));
        String raw = "{\"isOk\":false,\"message\":\"bad-cipher\",\"body\":null}";
        var response = bank.interpret(200, raw);
        assertThat(response.rawResponse()).isEqualTo(raw);
        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.isOk()).isFalse();
        assertThat(response.error()).contains("desencriptar");
    }
    @Test @SuppressWarnings("unchecked") void postsExactEnvelopeToGetExtractsOnceWithBasicAuthAndJson() throws Exception {
        var http = mock(HttpClient.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"isOk\":false,\"message\":\"cipher-error\",\"body\":null}");
        when(response.sslSession()).thenReturn(Optional.empty());
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(crypto.decrypt("cipher-error")).thenReturn("Rechazado");
        var result = bank.send(new BankPaymentClient.PreparedClient(http, null), "{\"companyId\":2295,\"data\":\"cipher\",\"signature\":\"signed\"}");
        assertThat(result.httpStatus()).isEqualTo(200);
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(captor.getValue().method()).isEqualTo("POST");
        assertThat(captor.getValue().uri().toString()).isEqualTo(BankStatementClient.SANDBOX_URL);
        assertThat(captor.getValue().headers().firstValue("Content-Type")).contains("application/json");
        assertThat(captor.getValue().headers().firstValue("Authorization").orElseThrow()).startsWith("Basic ");
    }
    @Test void nativeTransportAllowsGetExtractsOnlyFromExplicitAllowlist() {
        var nativeSettings = settings(true);
        when(payments.createClient()).thenReturn(new BankPaymentClient.PreparedClient(null, new BankSandboxCurlTransport(nativeSettings)));
        var client = new BankStatementClient(nativeSettings, payments, new ObjectMapper(), crypto).prepare();
        assertThat(client.sandboxTransport().configuration("{}", false)).contains("url = \"" + BankStatementClient.SANDBOX_URL + "\"")
            .doesNotContain("url = \"" + BankPaymentClient.SANDBOX_URL + "\"");
        assertThatThrownBy(() -> new BankSandboxCurlTransport(nativeSettings, "https://www99.bancred.com.bo/unsafe"))
            .isInstanceOf(SapServiceException.class);
        assertThatThrownBy(() -> new BankSandboxCurlTransport(nativeSettings, "https://other.example/"))
            .isInstanceOf(SapServiceException.class);
    }
    @Test @SuppressWarnings("unchecked") void timeoutDoesNotInventHttp200OrRetry() throws Exception {
        var http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("simulated"));
        var result = bank.send(new BankPaymentClient.PreparedClient(http, null), "{}");
        assertThat(result.httpStatus()).isNull();
        assertThat(result.status()).isEqualTo(ProcessStatus.FAILED);
        assertThat(result.rawResponse()).isEmpty();
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
