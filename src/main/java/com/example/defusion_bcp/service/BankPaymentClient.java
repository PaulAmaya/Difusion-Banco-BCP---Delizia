package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankPaymentSettings;
import com.example.defusion_bcp.domain.ProcessStatus;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import javax.net.ssl.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@Service
public class BankPaymentClient {
    private static final Logger log = LoggerFactory.getLogger(BankPaymentClient.class);
    private final Map<HttpClient, BankTlsKeyManager.Trace> certificateTraces = Collections.synchronizedMap(new WeakHashMap<>());
    public static final String SANDBOX_URL = "https://www99.bancred.com.bo/ApiCwV2/api/APIAuth/ProcessMultiple";
    private final BankPaymentSettings settings;
    private final ObjectMapper mapper;
    private final BankCryptoService crypto;

    public BankPaymentClient(BankPaymentSettings settings, ObjectMapper mapper, BankCryptoService crypto) {
        this.settings = settings;
        this.mapper = mapper;
        this.crypto = crypto;
    }

    public record Availability(boolean enabled, boolean ready, String environment, String message) {}
    public record Result(ProcessStatus status, String transactionId, String code,
                         String message, String rawResponse, Integer httpStatus) {}
    public record PreparedClient(HttpClient javaClient, BankSandboxCurlTransport sandboxTransport) implements AutoCloseable {
        @Override public void close() { if (javaClient != null) javaClient.close(); }
    }

    @jakarta.annotation.PostConstruct
    void logConfiguration() {
        log.info("BCP_CONFIG host={} enabled={} sandboxUrlMatches={} tls={} http=HTTP_1_1 connectTimeout={} readTimeout={} basicAuthConfigured={} clientPfxConfigured={} java={}",
            settings.production() ? "credinetweb.bcp.com.bo" : "www99.bancred.com.bo", settings.enabled(), SANDBOX_URL.equals(settings.url()), BankNetworkDiagnostics.redact(settings.tlsProtocols()),
            settings.connectTimeout(), settings.readTimeout(), !settings.username().isBlank() && !settings.password().isBlank(),
            !settings.authCertificatePath().isBlank(), System.getProperty("java.version"));
        try {
            log.info("BCP_PFX_CONFIG path={} readable={}", BankNetworkDiagnostics.redact(settings.authCertificatePath()),
                !settings.authCertificatePath().isBlank() && Files.isRegularFile(Path.of(settings.authCertificatePath()))
                    && Files.isReadable(Path.of(settings.authCertificatePath())));
        } catch (InvalidPathException exception) {
            log.error("BCP_PFX_CONFIG readable=false code=BANK_AUTH_CERTIFICATE_MISSING");
        }
    }

    public Availability availability() {
        boolean ready = basicConfigurationReady();
        if (ready) {
            try { validateTlsConfiguration(); }
            catch (SapServiceException exception) { return new Availability(true, false, settings.environment(), exception.getMessage()); }
        }
        return new Availability(settings.enabled(), ready, settings.environment(), ready ? "Conexion BCP habilitada: " + settings.environment()
            : "Configure BCP_PAYMENTS_ENABLED, BCP_BASIC_USERNAME y BCP_BASIC_PASSWORD en .env");
    }

    public void validateConfiguration() {
        if (!basicConfigurationReady()) throw new SapServiceException(HttpStatus.BAD_REQUEST,
            "BANK_NOT_CONFIGURED", availability().message());
        validateTlsConfiguration();
    }

    private boolean basicConfigurationReady() {
        return settings.enabled() && (SANDBOX_URL.equals(settings.url()) || settings.production()) && !settings.username().isBlank()
            && !settings.password().isBlank() && !settings.username().contains(":");
    }

    // Build the TLS client before reserving documents. No insecure trust manager is used.
    public PreparedClient createClient() {
        try {
            validateConfiguration();
            String[] protocols = validateTlsConfiguration();
            var trace = new BankTlsKeyManager.Trace();
            KeyManager[] keyManagers = null;
            String certificatePath = settings.authCertificatePath().trim();
            if (!certificatePath.isEmpty()) {
                KeyStore store = KeyStore.getInstance("PKCS12");
                char[] password = settings.authCertificatePassword().toCharArray();
                try {
                    try (var input = Files.newInputStream(Path.of(certificatePath))) { store.load(input, password); }
                    boolean privateKeyPresent = false;
                    for (var aliases = store.aliases(); aliases.hasMoreElements();) {
                        String alias = aliases.nextElement();
                        if (store.getKey(alias, password) instanceof PrivateKey && store.getCertificateChain(alias) != null) {
                            privateKeyPresent = true;
                            var leaf = store.getCertificateChain(alias)[0];
                            log.info("BCP_PFX_LOADED privateKey=true chainLength={} certificateSha256={}",
                                store.getCertificateChain(alias).length,
                                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded())));
                            break;
                        }
                    }
                    if (!privateKeyPresent) throw configurationError("BANK_AUTH_CERTIFICATE_NO_KEY",
                        "El certificado de autenticacion no contiene una clave privada. Use el .pfx/.p12 de autenticacion del banco");
                    var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    factory.init(store, password);
                    keyManagers = factory.getKeyManagers();
                    for (int index = 0; index < keyManagers.length; index++) {
                        if (keyManagers[index] instanceof X509ExtendedKeyManager manager) {
                            keyManagers[index] = new BankTlsKeyManager(manager, MDC.get("bcpRequestId"), trace);
                        }
                    }
                } catch (SapServiceException exception) {
                    throw exception;
                } catch (Exception exception) {
                    log.error("BCP_PFX_FAILED reason=PKCS12_LOAD_OR_KEY errorDetails=\n{}", networkDetails(exception));
                    throw configurationError("BANK_AUTH_CERTIFICATE_INVALID",
                        "No se pudo abrir el certificado de autenticacion. Verifique que sea .pfx/.p12 y BCP_AUTH_CERTIFICATE_PASSWORD sea su clave, no la de Basic Auth");
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
            else log.warn("BCP_PFX_NOT_CONFIGURED clientCertificateAvailable=false");
            if (settings.sandboxLegacyRsaEnabled()) {
                var transport = new BankSandboxCurlTransport(settings);
                transport.validateRuntime();
                log.warn("BCP_TLS_READY transport=ISOLATED_OPENSSL sandboxOnly=true protocols=TLSv1.2 cipher={} hostnameValidation=HTTPS serverTrust=OS_DEFAULT forwardSecrecy=false redirects=NEVER retries=0 jvmSecurityPolicyUnchanged=true",
                    BankSandboxCurlTransport.CIPHER);
                return new PreparedClient(null, transport);
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, null, new SecureRandom());
            SSLParameters parameters = new SSLParameters();
            parameters.setProtocols(protocols);
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            var client = HttpClient.newBuilder().sslContext(context).sslParameters(parameters)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
            certificateTraces.put(client, trace);
            log.info("BCP_TLS_READY protocols={} hostnameValidation=HTTPS serverTrust=DEFAULT redirects=NEVER clientPfxLoaded={}",
                Arrays.toString(protocols), keyManagers != null);
            return new PreparedClient(client, null);
        } catch (SapServiceException exception) {
            log.error("BCP_CONFIGURATION_FAILED code={} exceptionType={}", exception.getCode(), exception.getClass().getName());
            throw exception;
        } catch (Exception exception) {
            log.error("BCP_TLS_INIT_FAILED errorDetails=\n{}", networkDetails(exception));
            throw configurationError("BANK_TLS_CONFIGURATION",
                "No se pudo inicializar el cliente TLS del banco. Revise la configuracion TLS del runtime Java");
        }
    }

    private String[] validateTlsConfiguration() {
        if (settings.production() && (settings.authCertificatePath().isBlank() || settings.authCertificatePassword().isBlank())) {
            throw configurationError("BANK_PRODUCTION_CERTIFICATE_REQUIRED", "Produccion requiere el certificado de autenticacion y su contrasena");
        }
        if (settings.connectTimeout() == null || settings.connectTimeout().isNegative() || settings.connectTimeout().isZero()
            || settings.readTimeout() == null || settings.readTimeout().isNegative() || settings.readTimeout().isZero()) {
            throw configurationError("BANK_TIMEOUT_INVALID", "BCP_CONNECT_TIMEOUT y BCP_READ_TIMEOUT deben ser positivos, por ejemplo 10s y 60s");
        }
        String raw = settings.tlsProtocols() == null ? "" : settings.tlsProtocols();
        String[] protocols = Arrays.stream(raw.split(",", -1)).map(String::trim).map(value -> {
            if (value.equalsIgnoreCase("TLSv1.2")) return "TLSv1.2";
            if (value.equalsIgnoreCase("TLSv1.3")) return "TLSv1.3";
            throw configurationError("BANK_TLS_PROTOCOL_INVALID", "BCP_TLS_PROTOCOLS debe ser TLSv1.3, TLSv1.2 o TLSv1.2,TLSv1.3. El portal usa TLSv1.3 por defecto");
        }).distinct().toArray(String[]::new);
        if (settings.sandboxLegacyRsaEnabled()
            && (!SANDBOX_URL.equals(settings.url()) || protocols.length != 1 || !"TLSv1.2".equals(protocols[0])
                || settings.authCertificatePath().isBlank())) {
            throw configurationError("BANK_LEGACY_SCOPE_INVALID",
                "BCP_SANDBOX_LEGACY_RSA_ENABLED solo permite el sandbox BCP con BCP_TLS_PROTOCOLS=TLSv1.2 y certificado PFX");
        }
        if (!settings.authCertificatePath().isBlank()) {
            try {
                Path certificate = Path.of(settings.authCertificatePath().trim());
                if (!Files.isRegularFile(certificate) || !Files.isReadable(certificate)) {
                    throw configurationError("BANK_AUTH_CERTIFICATE_MISSING",
                        "BCP_AUTH_CERTIFICATE_PATH no apunta a un archivo accesible del backend. En Docker use /run/bcp-auth/nombre.pfx; deje la variable vacia si Postman no usa certificado de cliente");
                }
            } catch (InvalidPathException exception) {
                throw configurationError("BANK_AUTH_CERTIFICATE_MISSING", "BCP_AUTH_CERTIFICATE_PATH no es una ruta valida del backend");
            }
        }
        return protocols;
    }

    private SapServiceException configurationError(String code, String message) {
        return new SapServiceException(HttpStatus.BAD_REQUEST, code, message);
    }

    public Result send(PreparedClient client, String encryptedEnvelope) {
        if (client.sandboxTransport() == null) return sendJava(client.javaClient(), encryptedEnvelope);
        long started = System.nanoTime();
        log.info("BCP_HTTP_START transport=ISOLATED_OPENSSL method=POST endpoint={} contentType=application/json basicAuth=REDACTED bodyBytes={} configuredTls=TLSv1.2 retries=0",
            SANDBOX_URL, encryptedEnvelope.getBytes(StandardCharsets.UTF_8).length);
        try {
            var response = client.sandboxTransport().send(encryptedEnvelope);
            log.info("BCP_HTTP_RESPONSE transport=ISOLATED_OPENSSL status={} elapsedMs={} responseBytes={} tlsNegotiated={} cipher={} serverCertificateVerified=true clientCertificateRequested={} clientCertificateHandshakeObserved={}",
                response.status(), elapsedMillis(started), response.body().getBytes(StandardCharsets.UTF_8).length,
                response.protocol(), response.cipher(), response.certificateRequested(), response.certificateSent());
            var result = interpret(response.status(), response.body());
            log.info("BCP_HTTP_RESULT status={} bankCode={} transactionIdPresent={}", result.status(),
                BankNetworkDiagnostics.redact(result.code()), result.transactionId() != null);
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.error("BCP_HTTP_INTERRUPTED transport=ISOLATED_OPENSSL elapsedMs={} httpResponseReceived=false", elapsedMillis(started));
            return unknown(null, "", "Envio interrumpido. Conciliar con el banco; no reenviar.");
        } catch (Exception exception) {
            log.error("BCP_HTTP_FAILED transport=ISOLATED_OPENSSL elapsedMs={} httpResponseReceived=false errorDetails=\n{}",
                elapsedMillis(started), networkDetails(exception, encryptedEnvelope));
            return unknown(null, "", "No se recibio una respuesta HTTP completa de BCP. "
                + BankNetworkDiagnostics.redact(exception.getMessage()) + ". Conciliar antes de reenviar.");
        }
    }

    Result sendJava(HttpClient client, String encryptedEnvelope) {
        String authorization = Base64.getEncoder().encodeToString(
            (settings.username() + ":" + settings.password()).getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(settings.url())).timeout(settings.readTimeout())
            .header("Authorization", "Basic " + authorization).header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encryptedEnvelope, StandardCharsets.UTF_8)).build();
        long started = System.nanoTime();
        log.info("BCP_HTTP_START method=POST endpoint={} contentType=application/json basicAuth=REDACTED bodyBytes={} configuredTls={} automaticApplicationRetry=false",
            settings.url(), encryptedEnvelope.getBytes(StandardCharsets.UTF_8).length, BankNetworkDiagnostics.redact(settings.tlsProtocols()));
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var ssl = response.sslSession();
            log.info("BCP_HTTP_RESPONSE status={} elapsedMs={} responseBytes={} tlsNegotiated={} cipher={} clientCertificatePresented={}",
                response.statusCode(), elapsedMillis(started), response.body() == null ? 0 : response.body().getBytes(StandardCharsets.UTF_8).length,
                ssl.map(SSLSession::getProtocol).orElse("unavailable"), ssl.map(SSLSession::getCipherSuite).orElse("unavailable"),
                ssl.map(s -> s.getLocalCertificates() != null).orElse(false));
            var result = interpret(response.statusCode(), response.body());
            log.info("BCP_HTTP_RESULT status={} bankCode={} transactionIdPresent={}", result.status(),
                BankNetworkDiagnostics.redact(result.code()), result.transactionId() != null);
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.error("BCP_HTTP_INTERRUPTED elapsedMs={} httpResponseReceived=false", elapsedMillis(started));
            return unknown(null, "", "Envio interrumpido. Conciliar con el banco; no reenviar.");
        } catch (Exception exception) {
            var trace = certificateTraces.get(client);
            log.error("BCP_HTTP_FAILED elapsedMs={} httpResponseReceived=false configuredTls={} clientCertificateSelectionRequested={} clientCertificateSelected={} errorDetails=\n{}",
                elapsedMillis(started), BankNetworkDiagnostics.redact(settings.tlsProtocols()),
                trace == null ? "unavailable" : trace.requested.get() > 0,
                trace == null ? "unavailable" : trace.selected.get() > 0,
                networkDetails(exception, authorization, encryptedEnvelope));
            return unknown(null, "", connectionFailure(exception));
        } finally {
            certificateTraces.remove(client);
        }
    }

    private long elapsedMillis(long started) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private String networkDetails(Throwable exception, String... additionalSecrets) {
        var secrets = new ArrayList<String>(Arrays.asList(additionalSecrets));
        secrets.add(settings.username()); secrets.add(settings.password()); secrets.add(settings.authCertificatePassword());
        return BankNetworkDiagnostics.describe(exception, secrets.toArray(String[]::new));
    }

    private String connectionFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.EOFException) return "BCP cerro la conexion sin enviar cabeceras HTTP."
                + " No hay codigo HTTP ni body para desencriptar. Revise la autenticacion TLS del host con el banco; no reenviar automaticamente.";
            if (cause instanceof SSLException) return "Fallo la conexion TLS con BCP antes de recibir una respuesta HTTP."
                + " Revise protocolos, acceso y certificado de autenticacion con el banco. No existe un body para desencriptar; conciliar antes de reenviar.";
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException)
                return "La conexion con BCP excedio el tiempo de espera sin respuesta HTTP. No existe un body; conciliar antes de reenviar.";
            if (cause instanceof java.net.UnknownHostException)
                return "No se pudo resolver el servidor BCP. No se recibio respuesta HTTP; revisar DNS y conciliar antes de reenviar.";
        }
        return "No se recibio respuesta HTTP del banco. Revise la conexion y concilie antes de reenviar; no existe un body para desencriptar.";
    }

    Result interpret(int httpStatus, String raw) {
        if (raw == null || raw.length() > 1_000_000) return unknown(httpStatus, "", "Respuesta bancaria no valida; requiere conciliacion.");
        if (httpStatus < 200 || httpStatus >= 300) {
            return unknown(httpStatus, raw, "HTTP " + httpStatus + " del banco. Revisar acceso y conciliar; no reenviar.");
        }
        try {
            JsonNode envelope = mapper.readTree(raw);
            JsonNode isOk = envelope.get("isOk");
            if (isOk == null || !isOk.isBoolean()) return unknown(httpStatus, raw, "Respuesta sin isOk valido; requiere conciliacion.");
            log.info("BCP_RESPONSE_ENVELOPE isOk={} encryptedBodyPresent={} encryptedMessagePresent={}",
                isOk.booleanValue(), !text(envelope, "body").isBlank(), !text(envelope, "message").isBlank());
            if (!isOk.booleanValue()) {
                String message = decryptOrPlain(text(envelope, "message"));
                try {
                    String code = text(mapper.readTree(message), "Code");
                    if (Set.of("15", "16").contains(code)) {
                        return unknown(httpStatus, raw, "El banco informa un error de conexion. Conciliar antes de reenviar.");
                    }
                } catch (Exception ignored) { /* Bank errors may be plain text rather than JSON. */ }
                return new Result(ProcessStatus.REJECTED, null, null,
                    message.isBlank() ? "El banco rechazo la solicitud" : bounded(message), raw, httpStatus);
            }
            JsonNode body = mapper.readTree(crypto.decrypt(text(envelope, "body")));
            String code = text(body, "Code");
            String transactionId = text(body, "TransactionId");
            String message = text(body, "Message");
            if ("00".equals(code) && !transactionId.isBlank() && transactionId.length() <= 100) {
                return new Result(ProcessStatus.SENT, transactionId, code,
                    message.isBlank() ? "Lote preparado en el banco, pendiente de autorizacion" : bounded(message), raw, httpStatus);
            }
            // Ambiguous business failures can follow partial processing. Keep documents blocked.
            return unknown(httpStatus, raw, message.isBlank() ? "Resultado no confirmado; requiere conciliacion." : message);
        } catch (Exception exception) {
            log.error("BCP_RESPONSE_DECRYPT_OR_PARSE_FAILED exceptionType={} causeType={} httpStatus={} responseBodyOmitted=true",
                exception.getClass().getName(), exception.getCause() == null ? "none" : exception.getCause().getClass().getName(), httpStatus);
            return unknown(httpStatus, raw, "No se pudo interpretar o descifrar la respuesta. Conciliar; no reenviar.");
        }
    }

    private String decryptOrPlain(String value) {
        if (value.isBlank()) return "";
        try { return crypto.decrypt(value); }
        catch (CryptoOperationException exception) { return value; }
    }
    private String text(JsonNode value, String field) {
        var item = value == null ? null : value.get(field);
        return item != null && item.isString() ? item.stringValue().trim() : "";
    }
    private Result unknown(Integer http, String raw, String message) {
        return new Result(ProcessStatus.UNKNOWN, null, null, bounded(message), raw, http);
    }
    private String bounded(String message) { return message.length() <= 2000 ? message : message.substring(0, 2000); }
}
