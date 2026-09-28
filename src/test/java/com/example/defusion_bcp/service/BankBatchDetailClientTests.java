package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankPaymentSettings;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BankBatchDetailClientTests {
    private static final String CIPHER = "AAAAAAAAAAAAAAAAAAAAAA==";
    BankCryptoService crypto;
    BankPaymentClient payments;
    BankBatchDetailClient client;

    BankPaymentSettings settings(boolean legacy) {
        return new BankPaymentSettings(true, BankPaymentClient.SANDBOX_URL, "consultor", "basic-secret", "TLSv1.2",
            Duration.ofSeconds(2), Duration.ofSeconds(3), "/run/bcp-auth/API_DESA.pfx", "pfx-secret", legacy);
    }

    @BeforeEach void setup() {
        crypto = mock(BankCryptoService.class);
        payments = mock(BankPaymentClient.class);
        client = new BankBatchDetailClient(settings(false), payments, crypto, new ObjectMapper());
    }

    @Test void decryptsSuccessfulBodyAndPlainOrEncryptedRejection() {
        when(crypto.decrypt(CIPHER)).thenReturn("{\"Code\":\"00\",\"Result\":[]}");
        var success = client.interpret(200, "{\"isOk\":true,\"body\":\"" + CIPHER + "\",\"message\":null}");
        assertThat(success.isOk()).isTrue();
        assertThat(success.decryptedBody()).contains("Result");
        assertThat(success.decryptedMessage()).isNull();
        assertThat(client.interpret(200, "{\"isOk\":false,\"message\":\"Rechazado\",\"body\":null}")
            .decryptedMessage()).isEqualTo("Rechazado");
        assertThat(client.interpret(403, "<html>Forbidden</html>").httpStatus()).isEqualTo(403);
    }

    @Test @SuppressWarnings("unchecked") void postsOnlyToGetBatchDetailWithBasicAuthAndJson() throws Exception {
        var http = mock(HttpClient.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"isOk\":true,\"body\":\"" + CIPHER + "\"}");
        when(response.sslSession()).thenReturn(Optional.empty());
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(crypto.decrypt(CIPHER)).thenReturn("{\"Code\":\"00\",\"Result\":[]}");
        client.send(new BankPaymentClient.PreparedClient(http, null), "{\"companyId\":2295,\"data\":\"cipher\",\"signature\":\"sig\"}");
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo(BankBatchDetailClient.SANDBOX_URL);
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().headers().firstValue("Authorization").orElseThrow()).startsWith("Basic ");
        assertThat(request.getValue().headers().firstValue("Content-Type")).contains("application/json");
    }

    @Test void sandboxCompatibilityAllowlistIncludesOnlyKnownBankEndpoint() {
        var sandbox = settings(true);
        var transport = new BankSandboxCurlTransport(sandbox, BankBatchDetailClient.SANDBOX_URL);
        assertThat(transport.configuration("{}", false)).contains("url = \"" + BankBatchDetailClient.SANDBOX_URL + "\"");
        assertThatThrownBy(() -> new BankSandboxCurlTransport(sandbox, "https://other.example/GetBatchDetail"))
            .isInstanceOf(SapServiceException.class);
    }
}
