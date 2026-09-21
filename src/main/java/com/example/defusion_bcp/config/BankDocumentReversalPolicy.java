package com.example.defusion_bcp.config;

import com.example.defusion_bcp.service.BankPaymentClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record BankDocumentReversalPolicy(
    @Value("${BCP_ALLOW_DOCUMENT_REVERSAL:false}") boolean enabled,
    @Value("${BCP_MULTIPLE_URL:https://www99.bancred.com.bo/ApiCwV2/api/APIAuth/ProcessMultiple}") String bankUrl
) {
    public boolean allowed() { return enabled && BankPaymentClient.SANDBOX_URL.equals(bankUrl); }
}
