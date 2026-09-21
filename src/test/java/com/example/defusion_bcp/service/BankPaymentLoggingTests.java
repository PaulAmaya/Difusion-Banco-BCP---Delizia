package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankPaymentSettings;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.io.*;
import java.time.Duration;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BankPaymentLoggingTests {
    @Test @SuppressWarnings("unchecked") void logsExactNetworkCauseAndMetadataButNeverCredentialsOrPayload() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(BankPaymentClient.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        String username = "test-user", password = "basic-secret", pfxPassword = "pfx-secret";
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        var settings = new BankPaymentSettings(true, BankPaymentClient.SANDBOX_URL, username, password, "TLSv1.3",
            Duration.ofSeconds(2), Duration.ofSeconds(3), "", pfxPassword);
        var client = new BankPaymentClient(settings, new tools.jackson.databind.ObjectMapper(), mock(BankCryptoService.class));
        var http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new IOException("HTTP/1.1 header parser received no bytes " + username + " " + password + " " + pfxPassword + " " + token,
                new EOFException("EOF reached while reading")));
        try {
            var result = client.sendJava(http, "{\"data\":\"sensitive-cipher\",\"signature\":\"private-signature\"}");
            String logs = String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            assertThat(result.httpStatus()).isNull();
            assertThat(logs).contains("BCP_HTTP_START", "configuredTls=TLSv1.3", "BCP_HTTP_FAILED", "httpResponseReceived=false",
                "HTTP/1.1 header parser received no bytes", "java.io.EOFException", "EOF reached while reading");
            assertThat(logs).doesNotContain(username, password, pfxPassword, token, "sensitive-cipher", "private-signature");
            verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
