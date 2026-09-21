package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankPaymentSettings;
import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.dto.StatementDtos;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Service
public class BankStatementClient {
    public static final String SANDBOX_URL = "https://www99.bancred.com.bo/ApiCwV2/api/APIAuth/GetExtracts";
    private static final Logger log = LoggerFactory.getLogger(BankStatementClient.class);
    private final BankPaymentSettings settings;
    private final BankPaymentClient paymentClient;
    private final ObjectMapper mapper;
    private final BankCryptoService crypto;
    @org.springframework.beans.factory.annotation.Value("${BCP_EXTRACTS_URL:}")
    private String configuredUrl = "";

    String endpoint() {
        if (!configuredUrl.isBlank() && !configuredUrl.equals(settings.extractsUrl())) {
            throw new SapServiceException(org.springframework.http.HttpStatus.BAD_REQUEST, "BANK_EXTRACTS_ENDPOINT_INVALID",
                "BCP_EXTRACTS_URL debe corresponder al mismo ambiente que BCP_MULTIPLE_URL");
        }
        return settings.extractsUrl();
    }

    public BankStatementClient(BankPaymentSettings settings, BankPaymentClient paymentClient,
                               ObjectMapper mapper, BankCryptoService crypto) {
        this.settings = settings; this.paymentClient = paymentClient; this.mapper = mapper; this.crypto = crypto;
    }

    public BankPaymentClient.PreparedClient prepare() {
        endpoint();
        var prepared = paymentClient.createClient();
        if (prepared.sandboxTransport() != null) {
            return new BankPaymentClient.PreparedClient(null, new BankSandboxCurlTransport(settings, SANDBOX_URL));
        }
        return prepared;
    }

    public StatementDtos.BankResponse send(BankPaymentClient.PreparedClient client, String envelope) {
        long started = System.nanoTime();
        String authorization = Base64.getEncoder().encodeToString(
            (settings.username() + ":" + settings.password()).getBytes(StandardCharsets.UTF_8));
        log.info("BCP_EXTRACTS_HTTP_START method=POST endpoint={} bodyBytes={} basicAuth=REDACTED configuredTls={} retries=0",
            endpoint(), envelope.getBytes(StandardCharsets.UTF_8).length, settings.tlsProtocols());
        try {
            int status;
            String raw;
            String tls;
            String cipher;
            if (client.sandboxTransport() != null) {
                var response = client.sandboxTransport().send(envelope);
                status = response.status(); raw = response.body(); tls = response.protocol(); cipher = response.cipher();
            } else {
                var request = HttpRequest.newBuilder(URI.create(endpoint())).timeout(settings.readTimeout())
                    .header("Authorization", "Basic " + authorization).header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8)).build();
                var response = client.javaClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                status = response.statusCode(); raw = response.body();
                tls = response.sslSession().map(javax.net.ssl.SSLSession::getProtocol).orElse("unavailable");
                cipher = response.sslSession().map(javax.net.ssl.SSLSession::getCipherSuite).orElse("unavailable");
            }
            log.info("BCP_EXTRACTS_HTTP_RESPONSE status={} elapsedMs={} tlsNegotiated={} cipher={} responseBytes={}",
                status, elapsed(started), tls, cipher, raw == null ? 0 : raw.getBytes(StandardCharsets.UTF_8).length);
            return interpret(status, raw);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.error("BCP_EXTRACTS_HTTP_INTERRUPTED elapsedMs={}", elapsed(started));
            return failed(null, "", null, null, null, "Consulta interrumpida; no se recibio respuesta bancaria.");
        } catch (Exception exception) {
            log.error("BCP_EXTRACTS_HTTP_FAILED elapsedMs={} httpResponseReceived=false errorDetails=\n{}", elapsed(started),
                BankNetworkDiagnostics.describe(exception, settings.username(), settings.password(),
                    settings.authCertificatePassword(), authorization, envelope));
            return failed(null, "", null, null, null,
                "No se recibio una respuesta HTTP completa del banco. Revise los logs con el codigo de seguimiento.");
        }
    }

    StatementDtos.BankResponse interpret(int http, String raw) {
        if (raw == null || raw.length() > 1_000_000) return failed(http, "", null, null, null, "Respuesta bancaria no valida.");
        if (http < 200 || http >= 300) return failed(http, raw, null, null, null, "El banco respondio HTTP " + http + ".");
        Boolean isOk = null;
        String body = null, message = null;
        try {
            JsonNode response = mapper.readTree(raw);
            var flag = response.get("isOk");
            if (flag == null || !flag.isBoolean()) return failed(http, raw, null, null, null, "La respuesta no contiene isOk valido.");
            isOk = flag.booleanValue();
            body = text(response, "body"); message = text(response, "message");
            String encrypted = isOk ? body : message;
            if (encrypted == null || encrypted.isBlank()) return failed(http, raw, isOk, body, message,
                "El banco no devolvio " + (isOk ? "body" : "message") + " para desencriptar.");
            String decrypted = crypto.decrypt(encrypted);
            log.info("BCP_EXTRACTS_DECRYPTED isOk={} field={} plaintextOmitted=true", isOk, isOk ? "body" : "message");
            return new StatementDtos.BankResponse(isOk ? ProcessStatus.COMPLETED : ProcessStatus.REJECTED,
                http, isOk, raw, body, message, isOk ? decrypted : null, isOk ? null : decrypted, null);
        } catch (RuntimeException exception) {
            log.error("BCP_EXTRACTS_DECRYPT_OR_PARSE_FAILED httpStatus={} exceptionType={} responseOmitted=true",
                http, exception.getClass().getName());
            return failed(http, raw, isOk, body, message, "No se pudo interpretar o desencriptar la respuesta BCP.");
        }
    }
    private String text(JsonNode node, String key) {
        var value = node.get(key);
        return value != null && value.isString() ? value.asString() : null;
    }
    private StatementDtos.BankResponse failed(Integer http, String raw, Boolean isOk, String body, String message, String error) {
        return new StatementDtos.BankResponse(ProcessStatus.FAILED, http, isOk, raw, body, message, null, null, error);
    }
    private long elapsed(long started) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
}
