package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankEnvironmentGuard;
import com.example.defusion_bcp.config.BankPaymentSettings;
import com.example.defusion_bcp.dto.StatementDtos;
import java.time.Duration;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankProductionConfigurationTests {
    BankPaymentSettings settings(boolean legacy) {
        return new BankPaymentSettings(true, BankPaymentSettings.PRODUCTION_URL, "demo", "secret", "TLSv1.3",
            Duration.ofSeconds(2), Duration.ofSeconds(3), "", "", legacy);
    }
    @Test void productionGuardRejectsMixedEnvironmentsAndSandboxWorkarounds() {
        assertThatCode(() -> new BankEnvironmentGuard(settings(false), "PRODUCTION", settings(false).extractsUrl(), false, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new BankEnvironmentGuard(settings(false), "SANDBOX", "", false, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BankEnvironmentGuard(settings(true), "PRODUCTION", "", false, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BankEnvironmentGuard(settings(false), "PRODUCTION", "", true, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BankEnvironmentGuard(settings(false), "PRODUCTION", "", false, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BankEnvironmentGuard(settings(false), "PRODUCTION", BankStatementClient.SANDBOX_URL, false, true)).isInstanceOf(IllegalStateException.class);
    }
    @Test void productionRequiresClientAuthenticationCertificateBeforeAnyPost() {
        var client = new BankPaymentClient(settings(false), new ObjectMapper(), mock(BankCryptoService.class));
        assertThat(client.availability().ready()).isFalse();
        assertThat(client.availability().environment()).isEqualTo("PRODUCTION");
        assertThatThrownBy(client::createClient).isInstanceOf(SapServiceException.class).hasMessageContaining("certificado");
    }
    @Test @SuppressWarnings("unchecked") void multiplePaymentsUsesExactProductionEndpointWithoutRetry() throws Exception {
        var http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("simulated"));
        var client = new BankPaymentClient(settings(false), new ObjectMapper(), mock(BankCryptoService.class));
        client.sendJava(http, "{}");
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo(BankPaymentSettings.PRODUCTION_URL);
    }
    @Test @SuppressWarnings("unchecked") void extractsUsesMatchingProductionEndpointWithoutRetry() throws Exception {
        var http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("simulated"));
        var client = new BankStatementClient(settings(false), mock(BankPaymentClient.class), new ObjectMapper(), mock(BankCryptoService.class));
        client.send(new BankPaymentClient.PreparedClient(http, null), "{}");
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo(settings(false).extractsUrl());
    }
    @Test void removedTestAccountIsRejectedBeforeNetworkOrPersistence() {
        var bank = mock(BankStatementClient.class);
        var store = mock(StatementRequestStore.class);
        var diffusion = new DiffusionPreviewService(mock(SapClient.class), new ObjectMapper(), "demo", "doc", "");
        var service = new StatementRequestService(store, bank, mock(BankCryptoService.class), new ObjectMapper(),
            mock(BankPaymentClient.class), diffusion, 999, "server-secret", "00123");
        assertThatThrownBy(() -> service.query(new StatementDtos.QueryRequest("20150735205363", "202609"), "user", "db", "ip"))
            .isInstanceOf(SapServiceException.class).hasMessageContaining("BCP LP");
        verifyNoInteractions(bank, store);
        assertThat(service.configuration().accounts()).hasSize(2);
        assertThat(service.configuration().accountNumber()).isEqualTo("2015009988370");
    }
    @Test void queryBuildsTheSevenFieldPayloadUsingOnlyServerCredentials() {
        var diffusion = new DiffusionPreviewService(mock(SapClient.class), new ObjectMapper(), "secret", "doc", "");
        var service = spy(new StatementRequestService(mock(StatementRequestStore.class), mock(BankStatementClient.class),
            mock(BankCryptoService.class), new ObjectMapper(), mock(BankPaymentClient.class), diffusion, 999, "server-secret", "00123"));
        doReturn(null).when(service).create(any(), anyString(), anyString(), anyString());
        service.query(new StatementDtos.QueryRequest("20150838488388", "202507"), "user", "db", "ip");
        var request = ArgumentCaptor.forClass(StatementDtos.CreateRequest.class);
        verify(service).create(request.capture(), eq("user"), eq("db"), eq("ip"));
        assertThat(request.getValue().payload().password()).isEqualTo("server-secret");
        assertThat(request.getValue().payload().companyId()).isEqualTo(999);
        assertThat(request.getValue().payload().documentNumber()).isEqualTo("00123");
        assertThat(request.getValue().payload().accountNumber()).isEqualTo("20150838488388");
        assertThat(request.getValue().payload().period()).isEqualTo("202507");
    }
}
