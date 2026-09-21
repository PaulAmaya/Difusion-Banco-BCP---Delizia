package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankPaymentSettings;
import java.time.Duration;

/** Connection diagnostic only. Never sends a payment or Basic Auth credentials. */
public final class BankSandboxTlsProbe {
    private BankSandboxTlsProbe() {}
    public static void main(String[] args) throws Exception {
        if (!Boolean.parseBoolean(System.getenv("BCP_SANDBOX_LEGACY_RSA_ENABLED"))) {
            throw new IllegalStateException("Sandbox legacy compatibility is not enabled");
        }
        var settings = new BankPaymentSettings(true, BankPaymentClient.SANDBOX_URL, "", "", "TLSv1.2",
            Duration.ofSeconds(10), Duration.ofSeconds(20), System.getenv("BCP_AUTH_CERTIFICATE_PATH"),
            System.getenv("BCP_AUTH_CERTIFICATE_PASSWORD"), true);
        var transport = new BankSandboxCurlTransport(settings);
        transport.validateRuntime();
        var response = transport.probe();
        System.out.printf("BCP_TLS_PROBE method=HEAD paymentSent=false basicAuthSent=false httpStatus=%d tls=%s cipher=%s serverCertificateVerified=true certificateRequested=%s certificateHandshakeObserved=%s%n",
            response.status(), response.protocol(), response.cipher(), response.certificateRequested(), response.certificateSent());
    }
}
