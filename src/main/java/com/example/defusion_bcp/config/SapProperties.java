package com.example.defusion_bcp.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.sap")
public class SapProperties {
    @NotBlank
    private String baseUrl;

    @NotBlank
    private String companyDb;

    private boolean tlsRejectUnauthorized = true;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCompanyDb() {
        return companyDb;
    }

    public void setCompanyDb(String companyDb) {
        this.companyDb = companyDb;
    }

    public boolean isTlsRejectUnauthorized() {
        return tlsRejectUnauthorized;
    }

    public void setTlsRejectUnauthorized(boolean tlsRejectUnauthorized) {
        this.tlsRejectUnauthorized = tlsRejectUnauthorized;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
