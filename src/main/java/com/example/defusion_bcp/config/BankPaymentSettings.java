package com.example.defusion_bcp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public record BankPaymentSettings(
    @Value("${BCP_PAYMENTS_ENABLED:false}") boolean enabled,
    @Value("${BCP_MULTIPLE_URL:https://www99.bancred.com.bo/ApiCwV2/api/APIAuth/ProcessMultiple}") String url,
    @Value("${BCP_BASIC_USERNAME:}") String username,
    @Value("${BCP_BASIC_PASSWORD:}") String password,
    @Value("${BCP_TLS_PROTOCOLS:TLSv1.3}") String tlsProtocols,
    @Value("${BCP_CONNECT_TIMEOUT:10s}") Duration connectTimeout,
    @Value("${BCP_READ_TIMEOUT:60s}") Duration readTimeout,
    @Value("${BCP_AUTH_CERTIFICATE_PATH:}") String authCertificatePath,
    @Value("${BCP_AUTH_CERTIFICATE_PASSWORD:}") String authCertificatePassword,
    @Value("${BCP_SANDBOX_LEGACY_RSA_ENABLED:false}") boolean sandboxLegacyRsaEnabled
) {
    public static final String PRODUCTION_URL = "https://credinetweb.bcp.com.bo/ApiCwV2/api/APIAuth/ProcessMultiple";
    public boolean production() { return PRODUCTION_URL.equals(url); }
    public String environment() { return production() ? "PRODUCTION" : "SANDBOX"; }
    public String extractsUrl() {
        return production() ? "https://credinetweb.bcp.com.bo/ApiCwV2/api/APIAuth/GetExtracts"
            : "https://www99.bancred.com.bo/ApiCwV2/api/APIAuth/GetExtracts";
    }
    @org.springframework.beans.factory.annotation.Autowired
    public BankPaymentSettings {}

    public BankPaymentSettings(boolean enabled, String url, String username, String password, String tlsProtocols,
                               Duration connectTimeout, Duration readTimeout, String certificate, String certificatePassword) {
        this(enabled, url, username, password, tlsProtocols, connectTimeout, readTimeout, certificate, certificatePassword, false);
    }
    // Never include credentials in diagnostic output.
    @Override public String toString() { return "BankPaymentSettings[credentials=REDACTED]"; }
}
