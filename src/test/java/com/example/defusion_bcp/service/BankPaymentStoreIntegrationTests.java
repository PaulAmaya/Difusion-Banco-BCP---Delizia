package com.example.defusion_bcp.service;

import com.example.defusion_bcp.dto.DiffusionDtos;
import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "BCP_ALLOW_DOCUMENT_REVERSAL=true")
class BankPaymentStoreIntegrationTests {
    @Autowired private BankPaymentStore store;
    @Autowired private BankPaymentDocumentRepository documents;
    @Autowired private BankPaymentSubmissionRepository submissions;
    @Autowired private ProcessAuditLogRepository audit;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private BankCryptoService crypto;
    private final SapSession session = new SapSession("test", "cookie", Instant.now().plusSeconds(60), "TEST_DB", "test");
    private final DiffusionDtos.PreviewResponse preview = new DiffusionDtos.PreviewResponse("preview",
        new DiffusionDtos.Region("LP", "La Paz", 201), List.of(1L, 2L), Map.of("amount", new BigDecimal("30.00")),
        List.of(), "a".repeat(64), List.of(
            new DiffusionDtos.DocumentSnapshot(1, 1001, "PBL1", "Test1", BigDecimal.TEN),
            new DiffusionDtos.DocumentSnapshot(2, 1002, "PBL2", "Test2", new BigDecimal("20.00"))));
    @BeforeEach void setup() {
        cleanup();
        when(crypto.encrypt(anyString())).thenReturn("ciphertext-only");
        when(crypto.decrypt("ciphertext-only")).thenReturn("bank-test-message");
    }
    @AfterEach void cleanup() { documents.deleteAll(); submissions.deleteAll(); audit.deleteAll(); }
    private Long reserve() {
        return store.reserve(UUID.randomUUID().toString(), session, "test-actor", "11010501", preview,
            "{\"companyId\":2295,\"data\":\"cipher-data\",\"signature\":\"signature\"}", "127.0.0.1");
    }
    @Test void returnsFullDecryptedBankBodyWithoutStoringItAsPlaintext() {
        String plaintext = "{\"Code\":\"00\",\"TransactionId\":\"T1\",\"Message\":\"Aceptado\",\"Details\":[1,2]}";
        String envelope = "{\"isOk\":true,\"message\":null,\"body\":\"bank-cipher\"}";
        when(crypto.encrypt(envelope)).thenReturn("stored-envelope");
        when(crypto.decrypt("stored-envelope")).thenReturn(envelope);
        when(crypto.decrypt("bank-cipher")).thenReturn(plaintext);
        Long id = reserve();
        var response = store.complete(id, "TEST_DB", new BankPaymentClient.Result(ProcessStatus.SENT, "T1", "00", "ok", envelope, 200), "ip");
        assertThat(response.decryptedBody()).isEqualTo(plaintext);
        assertThat(response.bankResponse().received()).isTrue();
        assertThat(response.bankResponse().rawResponse()).isEqualTo(envelope);
        assertThat(response.bankResponse().encryptedBody()).isEqualTo("bank-cipher");
        assertThat(response.bankResponse().error()).isNull();
        assertThat(store.history("TEST_DB").getFirst().decryptedBody()).isEqualTo(plaintext);
        assertThat(jdbc.queryForObject("select encrypted_response from bank_payment_submissions where id=?", String.class, id))
            .isEqualTo("stored-envelope").doesNotContain("Details");
    }
    @Test void releaseRequiresConfirmationIsCompanyScopedAndPreservesBankStatusHistoryAndAudit() {
        Long id = reserve();
        store.complete(id, "TEST_DB", new BankPaymentClient.Result(ProcessStatus.SENT, "T1", "00", "ok", "", 200), "ip");
        var confirmation = new com.example.defusion_bcp.dto.BankPaymentDtos.ReleaseRequest(true, "Repetir prueba sandbox");
        assertThatThrownBy(() -> store.release(id, "OTHER_DB", "actor", confirmation, "ip")).isInstanceOf(SapServiceException.class);
        assertThatThrownBy(() -> store.release(id, "TEST_DB", "actor",
            new com.example.defusion_bcp.dto.BankPaymentDtos.ReleaseRequest(false, "test"), "ip")).isInstanceOf(SapServiceException.class);
        assertThat(store.blockedIds("TEST_DB")).hasSize(2);
        var result = store.release(id, "TEST_DB", "reversing-user", confirmation, "ip");
        assertThat(result.status()).isEqualTo(ProcessStatus.SENT);
        assertThat(result.bankTransactionId()).isEqualTo("T1");
        assertThat(result.releasedAt()).isNotNull();
        assertThat(result.releasedBy()).isEqualTo("reversing-user");
        assertThat(store.blockedIds("TEST_DB")).isEmpty();
        assertThat(documents.count()).isEqualTo(2);
        assertThat(store.history("TEST_DB")).hasSize(1);
        long count = audit.count();
        store.release(id, "TEST_DB", "another-user", confirmation, "ip");
        assertThat(audit.count()).isEqualTo(count);
        reserve();
        assertThat(store.blockedIds("TEST_DB")).hasSize(2);
        // Repeating release on the old batch must not unblock a new send of the same documents.
        store.release(id, "TEST_DB", "another-user", confirmation, "ip");
        assertThat(store.blockedIds("TEST_DB")).hasSize(2);
    }
    @Test void cannotReleaseWhileSendingButAllowsUnknownWithoutReconciliation() {
        Long id = reserve();
        var confirmation = new com.example.defusion_bcp.dto.BankPaymentDtos.ReleaseRequest(true, "test");
        assertThatThrownBy(() -> store.release(id, "TEST_DB", "actor", confirmation, "ip")).isInstanceOf(SapServiceException.class);
        store.complete(id, "TEST_DB", new BankPaymentClient.Result(ProcessStatus.UNKNOWN, null, null, "timeout", "", null), "ip");
        assertThat(store.history("TEST_DB").getFirst().canRevertDocuments()).isTrue();
        var response = store.release(id, "TEST_DB", "actor", confirmation, "ip");
        assertThat(response.status()).isEqualTo(ProcessStatus.UNKNOWN);
        assertThat(response.canRevertDocuments()).isFalse();
        assertThat(store.blockedIds("TEST_DB")).isEmpty();
    }
    @Test void uncertainDocumentsCanBeReleasedWithoutBankReviewAndPreserveBankResultAndAudit() {
        Long id = reserve();
        store.complete(id, "TEST_DB", new BankPaymentClient.Result(ProcessStatus.UNKNOWN, null, null, "connection failed", "", null), "ip");
        var response = store.release(id, "TEST_DB", "reviewer",
            new com.example.defusion_bcp.dto.BankPaymentDtos.ReleaseRequest(true, "Repetir prueba sandbox"), "ip");
        assertThat(response.status()).isEqualTo(ProcessStatus.UNKNOWN);
        assertThat(response.releasedBy()).isEqualTo("reviewer");
        assertThat(response.releasedAt()).isNotNull();
        assertThat(store.blockedIds("TEST_DB")).isEmpty();
        assertThat(response.bankResponse().received()).isFalse();
        assertThat(response.bankResponse().error()).contains("No existe un body");
        assertThat(documents.count()).isEqualTo(2);
        assertThat(audit.findAll()).anySatisfy(event -> assertThat(event.getMessage()).contains("sin exigir conciliacion"));
    }
    @Test void decryptionFailureKeepsOriginalResponseAndEncryptedBodyVisible() {
        String raw = "{\"isOk\":true,\"body\":\"invalid-cipher\"}";
        when(crypto.encrypt(raw)).thenReturn("stored");
        when(crypto.decrypt("stored")).thenReturn(raw);
        when(crypto.decrypt("invalid-cipher")).thenThrow(new CryptoOperationException("wrong certificate"));
        Long id = reserve();
        var response = store.complete(id, "TEST_DB", new BankPaymentClient.Result(ProcessStatus.UNKNOWN, null, null, "unknown", raw, 200), "ip");
        assertThat(response.decryptedBody()).isNull();
        assertThat(response.bankResponse().rawResponse()).isEqualTo(raw);
        assertThat(response.bankResponse().encryptedBody()).isEqualTo("invalid-cipher");
        assertThat(response.bankResponse().error()).contains("no se pudo desencriptar");
    }
    @Test void persistsAcceptedDocumentsActorTimesAndEncryptedHistoryAndKeepsDocumentsBlocked() {
        Long id = reserve();
        assertThat(store.blockedIds("TEST_DB")).containsExactlyInAnyOrder(1L, 2L);
        var result = store.complete(id, "TEST_DB", new BankPaymentClient.Result(ProcessStatus.SENT, "T1", "00",
            "secret-bank-message", "secret-bank-response", 200), "127.0.0.1");
        assertThat(result.requestedBy()).isEqualTo("test-actor");
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.completedAt()).isNotNull();
        assertThat(result.bankTransactionId()).isEqualTo("T1");
        assertThat(result.documents()).hasSize(2);
        assertThat(store.blockedIds("TEST_DB")).hasSize(2);
        assertThat(store.blockedIds("OTHER_DB")).isEmpty();
        assertThat(store.history("OTHER_DB")).isEmpty();
        assertThat(store.history("TEST_DB")).singleElement().extracting("status").isEqualTo(ProcessStatus.SENT);
        assertThat(jdbc.queryForObject("select encrypted_response from bank_payment_submissions where id=?", String.class, id))
            .isEqualTo("ciphertext-only").doesNotContain("secret-bank-response");
        assertThat(audit.findAll()).hasSize(6).allSatisfy(event -> {
            assertThat(event.getMessage()).doesNotContain("secret-bank", "cipher-data", "signature");
            assertThat(event.getActor()).isEqualTo("test-actor");
        });
    }
    @Test void rejectedAttemptReleasesButUnknownDoesNotReleaseDocuments() {
        Long rejected = reserve();
        store.complete(rejected, "TEST_DB", new BankPaymentClient.Result(ProcessStatus.REJECTED, null, null,
            "rejected", "response", 200), "ip");
        assertThat(store.blockedIds("TEST_DB")).isEmpty();
        Long uncertain = reserve();
        store.complete(uncertain, "TEST_DB", new BankPaymentClient.Result(ProcessStatus.UNKNOWN, null, null,
            "timeout", "", null), "ip");
        assertThat(store.blockedIds("TEST_DB")).containsExactlyInAnyOrder(1L, 2L);
        assertThatThrownBy(this::reserve).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.history("TEST_DB")).hasSize(2);
    }
    @Test void concurrentUsersCanReserveOnlyOneBatchAndRollbackAllDocumentsOfLosingAttempt() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            Callable<Boolean> attempt = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { reserve(); return true; }
                catch (DataIntegrityViolationException exception) { return false; }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
            assertThat(submissions.count()).isEqualTo(1);
            assertThat(documents.count()).isEqualTo(2);
            assertThat(audit.count()).isEqualTo(3);
        } finally { executor.shutdownNow(); }
    }
}
