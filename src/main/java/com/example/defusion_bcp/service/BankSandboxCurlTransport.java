package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankPaymentSettings;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** An isolated OpenSSL transport. It never changes the JVM's TLS security policy. */
final class BankSandboxCurlTransport {
    static final String CIPHER = "TLS_RSA_WITH_AES_256_GCM_SHA384";
    static final String STATUS_MARKER = "\nBCP_CURL_HTTP_STATUS:";
    private static final int RESPONSE_LIMIT = 1_000_512;
    private static final Pattern TRAILER = Pattern.compile("\\nBCP_CURL_HTTP_STATUS:(\\d{3})\\nBCP_CURL_VERIFY:(\\d+)\\n");
    private static final Pattern NEGOTIATION = Pattern.compile("(?m)^\\* SSL connection using (TLSv1\\.2) / (AES256-GCM-SHA384)(?: /.*)?$");
    private final BankPaymentSettings settings;
    private final ProcessStarter starter;
    private final String endpoint;

    interface ProcessStarter { Process start(List<String> command) throws IOException; }
    record Response(int status, String body, String protocol, String cipher,
                    boolean certificateRequested, boolean certificateSent) {}

    BankSandboxCurlTransport(BankPaymentSettings settings) { this(settings, BankPaymentClient.SANDBOX_URL); }
    BankSandboxCurlTransport(BankPaymentSettings settings, String endpoint) {
        this(settings, endpoint, BankSandboxCurlTransport::startProcess);
    }
    BankSandboxCurlTransport(BankPaymentSettings settings, ProcessStarter starter) {
        this(settings, BankPaymentClient.SANDBOX_URL, starter);
    }
    BankSandboxCurlTransport(BankPaymentSettings settings, String endpoint, ProcessStarter starter) {
        this.settings = settings;
        this.starter = starter;
        this.endpoint = endpoint;
        if (!settings.enabled() || !settings.sandboxLegacyRsaEnabled()
            || !BankPaymentClient.SANDBOX_URL.equals(settings.url())
            || !"TLSv1.2".equalsIgnoreCase(settings.tlsProtocols().trim())
            || settings.authCertificatePath().isBlank()
            || !Set.of(BankPaymentClient.SANDBOX_URL, BankStatementClient.SANDBOX_URL).contains(endpoint)) {
            throw new SapServiceException(HttpStatus.BAD_REQUEST, "BANK_LEGACY_SCOPE_INVALID",
                "La compatibilidad RSA requiere el sandbox BCP, TLSv1.2 y el certificado de autenticacion PFX");
        }
    }

    void validateRuntime() {
        try {
            var output = execute(List.of("curl", "--disable", "--version"), "", Duration.ofSeconds(5), 8192);
            if (output.exit() != 0 || !output.stdout().contains("OpenSSL/")) throw new IOException("OpenSSL runtime unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw runtimeError();
        } catch (IOException exception) { throw runtimeError(); }
    }

    private SapServiceException runtimeError() {
        return new SapServiceException(HttpStatus.BAD_REQUEST, "BANK_OPENSSL_UNAVAILABLE",
            "La compatibilidad sandbox requiere curl con OpenSSL. Reconstruya el backend Docker");
    }

    Response send(String encryptedEnvelope) throws IOException, InterruptedException {
        var output = execute(command(), configuration(encryptedEnvelope, false),
            settings.readTimeout().plusSeconds(5), RESPONSE_LIMIT);
        return parse(output.exit(), output.stdout(), output.stderr());
    }

    // Diagnostic HEAD only: no payment body and no Basic Auth credentials are sent.
    Response probe() throws IOException, InterruptedException {
        var output = execute(command(), configuration("", true), settings.readTimeout().plusSeconds(5), RESPONSE_LIMIT);
        return parse(output.exit(), output.stdout(), output.stderr());
    }

    static List<String> command() { return List.of("curl", "--disable", "--config", "-"); }

    String configuration(String envelope, boolean probe) {
        var config = new StringBuilder("silent\nshow-error\nverbose\nhttp1.1\nipv4\ngloboff\n")
            .append("proto = \"=https\"\nnoproxy = \"*\"\nretry = 0\nmax-redirs = 0\n")
            .append("tlsv1.2\ntls-max = \"1.2\"\nciphers = \"AES256-GCM-SHA384\"\n")
            .append("cert-type = \"P12\"\n");
        option(config, "url", endpoint);
        option(config, "cert", settings.authCertificatePath() + ":" + settings.authCertificatePassword());
        option(config, "connect-timeout", seconds(settings.connectTimeout()));
        option(config, "max-time", seconds(settings.readTimeout()));
        option(config, "header", "Accept: application/json");
        if (probe) config.append("head\n");
        else {
            option(config, "request", "POST");
            config.append("basic\n");
            option(config, "user", settings.username() + ":" + settings.password());
            option(config, "header", "Content-Type: application/json");
            option(config, "header", "Expect:");
            option(config, "data-binary", envelope);
        }
        option(config, "write-out", STATUS_MARKER + "%{http_code}\nBCP_CURL_VERIFY:%{ssl_verify_result}\n");
        return config.toString();
    }

    private static String seconds(Duration value) { return Double.toString(value.toMillis() / 1000.0); }
    private static void option(StringBuilder config, String name, String value) {
        config.append(name).append(" = ").append(quote(value)).append('\n');
    }
    static String quote(String value) {
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL is not allowed in curl configuration");
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
            .replace("\u000b", "\\v") + "\"";
    }

    static Response parse(int exit, String stdout, String stderr) throws IOException {
        // stderr contains sensitive request headers. Never log or propagate its contents.
        if (exit != 0) throw new IOException("BCP OpenSSL transport failed: curlExit=" + exit + " ("
            + switch (exit) {
                case 6 -> "DNS resolution";
                case 7 -> "TCP connection";
                case 28 -> "timeout; reconcile before retrying";
                case 35 -> "TLS negotiation";
                case 58 -> "client certificate or PFX password";
                case 60 -> "server certificate verification";
                case 52 -> "connection closed without HTTP response";
                case 56 -> "connection interrupted while reading";
                default -> "network or incomplete response";
            } + ")");
        int marker = stdout.lastIndexOf(STATUS_MARKER);
        if (marker < 0) throw new IOException("BCP OpenSSL transport returned no HTTP metadata");
        var trailer = TRAILER.matcher(stdout.substring(marker));
        if (!trailer.matches() || !"0".equals(trailer.group(2))) throw new IOException("BCP HTTP metadata or server verification invalid");
        int status = Integer.parseInt(trailer.group(1));
        if (status < 100 || status > 599) throw new IOException("BCP did not return an HTTP status");
        var negotiation = NEGOTIATION.matcher(stderr.replace("\r", ""));
        boolean negotiated = negotiation.find();
        return new Response(status, stdout.substring(0, marker), negotiated ? "TLSv1.2" : "unavailable",
            negotiated ? CIPHER : "unavailable",
            stderr.contains("TLS handshake, Request CERT (13)"),
            Pattern.compile("(?m)^\\* TLSv1\\.2 \\(OUT\\), TLS handshake, Certificate \\(11\\):").matcher(stderr).find());
    }

    private static Process startProcess(List<String> command) throws IOException {
        var builder = new ProcessBuilder(command);
        // Use the OS trust store, no inherited key logging or CA/proxy overrides.
        for (String key : List.of("SSLKEYLOGFILE", "CURL_CA_BUNDLE", "SSL_CERT_FILE", "SSL_CERT_DIR")) builder.environment().remove(key);
        return builder.start();
    }

    private record Output(int exit, String stdout, String stderr) {}
    private Output execute(List<String> command, String config, Duration deadline, int limit) throws IOException, InterruptedException {
        Process process = starter.start(command);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stdout = executor.submit(() -> readBounded(process.getInputStream(), limit, process));
            var stderr = executor.submit(() -> readBounded(process.getErrorStream(), 65_536, process));
            var input = executor.submit(() -> {
                try (var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) { writer.write(config); }
                return null;
            });
            try {
                if (!process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("BCP OpenSSL process timeout; reconcile before retrying");
                }
                input.get(2, TimeUnit.SECONDS);
                return new Output(process.exitValue(), stdout.get(2, TimeUnit.SECONDS), stderr.get(2, TimeUnit.SECONDS));
            } catch (ExecutionException | TimeoutException exception) {
                process.destroyForcibly();
                throw new IOException("BCP OpenSSL process I/O failed", exception.getCause() instanceof IOException io ? io : null);
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String readBounded(InputStream input, int limit, Process process) throws IOException {
        try (input; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) {
                    process.destroyForcibly();
                    throw new IOException("BCP transport output exceeded its safe size limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
