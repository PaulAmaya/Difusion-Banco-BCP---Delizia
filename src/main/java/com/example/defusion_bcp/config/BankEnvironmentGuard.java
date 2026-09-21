package com.example.defusion_bcp.config;

import com.example.defusion_bcp.service.BankPaymentClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BankEnvironmentGuard {
    public BankEnvironmentGuard(BankPaymentSettings settings,
        @Value("${BCP_ENVIRONMENT:SANDBOX}") String environment,
        @Value("${BCP_EXTRACTS_URL:}") String extractsUrl,
        @Value("${BCP_ALLOW_DOCUMENT_REVERSAL:false}") boolean reversal,
        @Value("${SAP_TLS_REJECT_UNAUTHORIZED:true}") boolean sapCertificateValidation) {
        if (!environment.equalsIgnoreCase(settings.environment())
            || (!settings.production() && !settings.url().equals(BankPaymentClient.SANDBOX_URL))) {
            throw new IllegalStateException("BCP_ENVIRONMENT y BCP_MULTIPLE_URL no corresponden al mismo ambiente permitido");
        }
        if (!extractsUrl.isBlank() && !extractsUrl.equals(settings.extractsUrl())) {
            throw new IllegalStateException("BCP_EXTRACTS_URL no corresponde al ambiente BCP configurado");
        }
        if (settings.production() && (reversal || settings.sandboxLegacyRsaEnabled() || !sapCertificateValidation)) {
            throw new IllegalStateException("Produccion no permite reversion, compatibilidad TLS sandbox ni desactivar validacion SAP");
        }
    }
}
