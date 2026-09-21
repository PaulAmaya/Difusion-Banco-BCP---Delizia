package com.example.defusion_bcp.service;

import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.dto.StatementDtos;
import com.example.defusion_bcp.repository.BankStatementRequestRepository;
import com.example.defusion_bcp.repository.ProcessAuditLogRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class StatementRequestStoreIntegrationTests {
    @Autowired StatementRequestStore store;
    @Autowired BankStatementRequestRepository repository;
    @Autowired ProcessAuditLogRepository audit;
    @Autowired ObjectMapper mapper;
    @MockitoBean BankCryptoService crypto;

    @Test void storesConfidentialResponseEncryptedAndKeepsHistoryLightweight() {
        Long id = store.reserve("20150735205363", "202506", "actor", "COMPANY_A", UUID.randomUUID().toString(), "encrypted-envelope", "ip");
        var bank = new StatementDtos.BankResponse(ProcessStatus.REJECTED, 200, false, "raw-response", null,
            "encrypted-message", null, "PRIVATE_DECRYPTED_MESSAGE", null);
        when(crypto.encrypt(anyString())).thenReturn("STORED_CIPHERTEXT");
        when(crypto.decrypt("STORED_CIPHERTEXT")).thenReturn(mapper.writeValueAsString(bank));
        var response = store.complete(id, "COMPANY_A", bank, "ip");
        assertThat(repository.findById(id).orElseThrow().getEncryptedResponse()).isEqualTo("STORED_CIPHERTEXT");
        assertThat(response.bankResponse().decryptedMessage()).isEqualTo("PRIVATE_DECRYPTED_MESSAGE");
        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.sentEnvelope()).isEqualTo("encrypted-envelope");
        clearInvocations(crypto);
        assertThat(store.history("COMPANY_A")).singleElement().satisfies(item -> {
            assertThat(item.bankResponse()).isNull();
            assertThat(item.sentEnvelope()).isNull();
        });
        verifyNoInteractions(crypto);
        assertThat(audit.findAll()).allSatisfy(event -> assertThat(event.getMessage()).doesNotContain("PRIVATE_DECRYPTED_MESSAGE"));
        assertThat(store.detail(id, "COMPANY_A").bankResponse().decryptedMessage()).isEqualTo("PRIVATE_DECRYPTED_MESSAGE");
    }
    @Test void financialHistoryAndDetailAreIsolatedBySapCompany() {
        Long id = store.reserve("00123", "202506", "actor", "COMPANY_A", UUID.randomUUID().toString(), "cipher", "ip");
        store.reserve("00456", "202506", "other", "COMPANY_B", UUID.randomUUID().toString(), "cipher", "ip");
        assertThat(store.history("COMPANY_A")).singleElement().satisfies(item -> assertThat(item.requestedBy()).isEqualTo("actor"));
        assertThat(store.history("COMPANY_B")).singleElement().satisfies(item -> assertThat(item.requestedBy()).isEqualTo("other"));
        assertThatThrownBy(() -> store.detail(id, "COMPANY_B")).isInstanceOf(SapServiceException.class);
    }
}
