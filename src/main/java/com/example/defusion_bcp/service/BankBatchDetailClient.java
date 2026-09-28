package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankPaymentSettings;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class BankBatchDetailClient {
    public static final String SANDBOX_URL = "https://www99.bancred.com.bo/ApiCwV2/api/APIAuth/GetBatchDetail";
    private static final Logger log = LoggerFactory.getLogger(BankBatchDetailClient.class);
    private final BankPaymentSettings settings;
    private final BankPaymentClient paymentClient;
    private final BankCryptoService crypto;
    private final ObjectMapper mapper;

    public record BankResponse(Integer httpStatus, Boolean isOk, String decryptedBody,
                               String decryptedMessage, String error) {}

    public BankBatchDetailClient(BankPaymentSettings settings, BankPaymentClient paymentClient,
                                 BankCryptoService crypto, ObjectMapper mapper) {
        this.settings = settings; this.paymentClient = paymentClient; this.crypto = crypto; this.mapper = mapper;
    }

    public BankPaymentClient.PreparedClient prepare() {
        var prepared = paymentClient.createClient();
        if (prepared.sandboxTransport() != null) {
            return new BankPaymentClient.PreparedClient(null,
                new BankSandboxCurlTransport(settings, SANDBOX_URL));
        }
        return prepared;
    }

    public BankResponse send(BankPaymentClient.PreparedClient client, String envelope) {
        long started = System.nanoTime();
        String authorization = Base64.getEncoder().encodeToString(
            (settings.username() + ":" + settings.password()).getBytes(StandardCharsets.UTF_8));
        log.info("BCP_BATCH_DETAIL_HTTP_START method=POST endpoint={} bodyBytes={} basicAuth=REDACTED retries=0",
            settings.batchDetailUrl(), envelope.getBytes(StandardCharsets.UTF_8).length);
        try {
            int status;
            String raw;
            if (client.sandboxTransport() != null) {
                var response = client.sandboxTransport().send(envelope);
                status = response.status(); raw = response.body();
            } else {
                var request = HttpRequest.newBuilder(URI.create(settings.batchDetailUrl())).timeout(settings.readTimeout())
                    .header("Authorization", "Basic " + authorization)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8)).build();
                var response = client.javaClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                status = response.statusCode(); raw = response.body();
                log.info("BCP_BATCH_DETAIL_TLS protocol={}",
                    response.sslSession().map(javax.net.ssl.SSLSession::getProtocol).orElse("unavailable"));
            }
            log.info("BCP_BATCH_DETAIL_HTTP_RESPONSE status={} elapsedMs={} responseBytes={}", status,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                raw == null ? 0 : raw.getBytes(StandardCharsets.UTF_8).length);
            return interpret(status, raw);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new BankResponse(null, null, null, null, "Consulta interrumpida; no se recibio respuesta bancaria.");
        } catch (Exception exception) {
            log.error("BCP_BATCH_DETAIL_HTTP_FAILED httpResponseReceived=false errorDetails=\n{}",
                BankNetworkDiagnostics.describe(exception, authorization, envelope, settings.username(),
                    settings.password(), settings.authCertificatePassword()));
            return new BankResponse(null, null, null, null,
                "No se recibio una respuesta HTTP completa de BCP. Revise los logs del backend.");
        }
    }

    BankResponse interpret(int status, String raw) {
        if (raw == null || raw.length() > 1_000_000)
            return new BankResponse(status, null, null, null, "Respuesta bancaria no valida.");
        if (status < 200 || status >= 300)
            return new BankResponse(status, null, null, null, "El banco respondio HTTP " + status + ".");
        try {
            var response = mapper.readTree(raw);
            var flag = response.get("isOk");
            if (flag == null || !flag.isBoolean())
                return new BankResponse(status, null, null, null, "La respuesta no contiene isOk valido.");
            boolean isOk = flag.booleanValue();
            var encrypted = response.get(isOk ? "body" : "message");
            if (encrypted == null || !encrypted.isString() || encrypted.asString().isBlank())
                return new BankResponse(status, isOk, null, null,
                    "El banco no devolvio " + (isOk ? "body" : "message") + " para desencriptar.");
            String value = encrypted.asString();
            if (!isOk) {
                try {
                    String decrypted = crypto.decrypt(value);
                    if (decrypted != null && !decrypted.isBlank()) value = decrypted;
                }
                catch (CryptoOperationException ignored) { /* Some bank rejections are plain text. */ }
                return new BankResponse(status, false, null, value, null);
            }
            return new BankResponse(status, true, crypto.decrypt(value), null, null);
        } catch (RuntimeException exception) {
            log.error("BCP_BATCH_DETAIL_DECRYPT_OR_PARSE_FAILED exceptionType={} responseOmitted=true",
                exception.getClass().getName());
            return new BankResponse(status, null, null, null, "No se pudo interpretar o desencriptar la respuesta BCP.");
        }
    }
}
