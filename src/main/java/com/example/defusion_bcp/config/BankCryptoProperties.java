package com.example.defusion_bcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.bcp.crypto")
public class BankCryptoProperties {
    private int companyId = 2295;
    private String businessCertificatePath = "";
    private String signingCertificatePath = "";
    private String signingCertificatePassword = "";

    public int getCompanyId() {
        return companyId;
    }

    public void setCompanyId(int companyId) {
        this.companyId = companyId;
    }

    public String getBusinessCertificatePath() {
        return businessCertificatePath;
    }

    public void setBusinessCertificatePath(String businessCertificatePath) {
        this.businessCertificatePath = businessCertificatePath;
    }

    public String getSigningCertificatePath() {
        return signingCertificatePath;
    }

    public void setSigningCertificatePath(String signingCertificatePath) {
        this.signingCertificatePath = signingCertificatePath;
    }

    public String getSigningCertificatePassword() {
        return signingCertificatePassword;
    }

    public void setSigningCertificatePassword(String signingCertificatePassword) {
        this.signingCertificatePassword = signingCertificatePassword;
    }
}
