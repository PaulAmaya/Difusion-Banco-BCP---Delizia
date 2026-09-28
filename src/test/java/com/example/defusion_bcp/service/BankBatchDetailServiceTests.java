package com.example.defusion_bcp.service;

import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.dto.BankPaymentDtos;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BankBatchDetailServiceTests {
    BankPaymentStore store;
    BankBatchDetailClient bank;
    BankCryptoService crypto;
    AuditLogService audit;
    ObjectMapper mapper;
    BankBatchDetailService service;

    @BeforeEach void setup() {
        store = mock(BankPaymentStore.class); bank = mock(BankBatchDetailClient.class);
        crypto = mock(BankCryptoService.class); audit = mock(AuditLogService.class);
        mapper = new ObjectMapper();
        service = new BankBatchDetailService(store, bank, crypto, audit, mapper,
            2295, "consultor-secret", "255921", "Q", "LP", "");
    }

    BankPaymentDtos.SubmissionResponse submission(ProcessStatus status, String id, String body) {
        return new BankPaymentDtos.SubmissionResponse(7L, "request-id", "sap-user", "11010501", "LP",
            new BigDecimal("1200.00"), status, id, 200, "Preparado", LocalDateTime.now(),
            LocalDateTime.now(), List.of(), body, null, null, null, null, false);
    }

    @Test void listIncludesOnlyCode00AndMatchingNumericIdFromDecryptedBody() {
        var valid = submission(ProcessStatus.SENT, "6545485", "{\"Code\":\"00\",\"TransactionId\":\"6545485\"}");
        var wrong = submission(ProcessStatus.SENT, "6545485", "{\"Code\":\"99\",\"TransactionId\":\"6545485\"}");
        var mismatch = submission(ProcessStatus.SENT, "6545485", "{\"Code\":\"00\",\"TransactionId\":\"999\"}");
        var nonnumeric = submission(ProcessStatus.SENT, "T1", "{\"Code\":\"00\",\"TransactionId\":\"T1\"}");
        when(store.successfulCandidates("COMPANY_A", 0)).thenReturn(new PageImpl<>(
            List.of(valid, wrong, mismatch, nonnumeric), PageRequest.of(0, 20), 4));
        var result = service.history("COMPANY_A", 0);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().transactionId()).isEqualTo("6545485");
    }

    @Test void encryptsExactConsultationJsonAndSendsOnlyEncryptedEnvelope() {
        var valid = submission(ProcessStatus.SENT, "6545485", "{\"Code\":\"00\",\"TransactionId\":\"6545485\"}");
        when(store.findById(7L, "COMPANY_A")).thenReturn(Optional.of(valid));
        when(bank.prepare()).thenReturn(new BankPaymentClient.PreparedClient(mock(HttpClient.class), null));
        when(crypto.encrypt(anyString())).thenReturn("cipher");
        when(crypto.sign("cipher")).thenReturn("signature");
        when(bank.send(any(), anyString())).thenReturn(new BankBatchDetailClient.BankResponse(
            200, true, "{\"Code\":\"00\",\"Message\":\"Consulta de lote\",\"Result\":[]}", null, null));

        var result = service.query(7L, "COMPANY_A", "sap-user", "127.0.0.1");
        var plaintext = ArgumentCaptor.forClass(String.class);
        verify(crypto).encrypt(plaintext.capture());
        var payload = mapper.readTree(plaintext.getValue());
        assertThat(payload.path("companyId").asInt()).isEqualTo(2295);
        assertThat(payload.path("password").asString()).isEqualTo("consultor-secret");
        assertThat(payload.path("documentNumber").asString()).isEqualTo("255921");
        assertThat(payload.path("documentComplement").asString()).isEmpty();
        assertThat(payload.path("transactionsId").get(0).asLong()).isEqualTo(6545485L);
        var envelope = ArgumentCaptor.forClass(String.class);
        verify(bank).send(any(), envelope.capture());
        var sent = mapper.readTree(envelope.getValue());
        assertThat(sent.path("companyId").asInt()).isEqualTo(2295);
        assertThat(sent.path("data").asString()).isEqualTo("cipher");
        assertThat(sent.path("signature").asString()).isEqualTo("signature");
        assertThat(envelope.getValue()).doesNotContain("consultor-secret", "255921", "6545485");
        assertThat(result.status()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(result.body().path("Result").isArray()).isTrue();
        verify(store).findById(7L, "COMPANY_A");
    }

    @Test void rejectsUnconfirmedSubmissionBeforeAnyBankCall() {
        when(store.findById(7L, "COMPANY_A")).thenReturn(Optional.of(
            submission(ProcessStatus.REJECTED, null, null)));
        assertThatThrownBy(() -> service.query(7L, "COMPANY_A", "sap-user", "127.0.0.1"))
            .isInstanceOf(SapServiceException.class);
        verifyNoInteractions(bank, crypto);
    }
}
