package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankDocumentReversalPolicy;
import com.example.defusion_bcp.dto.BankPaymentDtos;
import com.example.defusion_bcp.repository.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankDocumentReversalPolicyTests {
    @Test void requiresExplicitEnablementAndExactSandboxUrl() {
        assertThat(new BankDocumentReversalPolicy(false, BankPaymentClient.SANDBOX_URL).allowed()).isFalse();
        assertThat(new BankDocumentReversalPolicy(true, BankPaymentClient.SANDBOX_URL).allowed()).isTrue();
        assertThat(new BankDocumentReversalPolicy(true, "https://production.example/api/ProcessMultiple").allowed()).isFalse();
        assertThat(new BankDocumentReversalPolicy(true, "").allowed()).isFalse();
    }
    @Test void disabledOrProductionPolicyRejectsReleaseBeforeTouchingDocuments() {
        var submissions = mock(BankPaymentSubmissionRepository.class);
        var documents = mock(BankPaymentDocumentRepository.class);
        var audit = mock(AuditLogService.class);
        var crypto = mock(BankCryptoService.class);
        for (var policy : new BankDocumentReversalPolicy[]{
            new BankDocumentReversalPolicy(false, BankPaymentClient.SANDBOX_URL),
            new BankDocumentReversalPolicy(true, "https://production.example/api/ProcessMultiple")}) {
            var store = new BankPaymentStore(submissions, documents, audit, crypto, new ObjectMapper(), policy);
            assertThatThrownBy(() -> store.release(1L, "DB", "actor", new BankPaymentDtos.ReleaseRequest(true, "test"), "ip"))
                .isInstanceOf(SapServiceException.class).hasMessageContaining("deshabilitada");
        }
        verifyNoInteractions(submissions, documents, audit, crypto);
    }
}
