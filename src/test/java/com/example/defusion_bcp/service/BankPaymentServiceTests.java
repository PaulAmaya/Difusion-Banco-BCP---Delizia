package com.example.defusion_bcp.service;

import com.example.defusion_bcp.dto.*;
import com.example.defusion_bcp.domain.ProcessStatus;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class BankPaymentServiceTests {
    private DiffusionPreviewService preview;
    private BankPaymentClient bank;
    private BankPaymentStore store;
    private BankCryptoService crypto;
    private BankPaymentService service;
    private final SapSession session = new SapSession("test", "cookie", Instant.now().plusSeconds(60), "TEST_DB", "test");
    private final String requestId = "10000000-0000-0000-0000-000000000001";
    private final String fingerprint = "a".repeat(64);
    private DiffusionDtos.SendRequest request;
    private DiffusionDtos.PreviewResponse snapshot;
    @BeforeEach void setup() {
        preview = mock(DiffusionPreviewService.class); bank = mock(BankPaymentClient.class);
        store = mock(BankPaymentStore.class); crypto = mock(BankCryptoService.class);
        service = new BankPaymentService(preview, bank, store, crypto, new ObjectMapper());
        request = new DiffusionDtos.SendRequest(new DiffusionDtos.PreviewRequest(List.of(
            new DiffusionDtos.Selection(1L, 0, "1005", "00123", "LP", "123", "Q", "LP", ""))), fingerprint, requestId);
        snapshot = new DiffusionDtos.PreviewResponse("preview", new DiffusionDtos.Region("LP", "La Paz", 201), List.of(1L),
            Map.of("companyId", 2295, "amount", new BigDecimal("10.00")), List.of(), fingerprint,
            List.of(new DiffusionDtos.DocumentSnapshot(1, 1001, "PBL1", "Test", new BigDecimal("10.00"))));
        when(preview.preview(session, request.selection())).thenReturn(snapshot);
        when(crypto.encrypt(anyString())).thenReturn("cipher-data");
        when(crypto.sign("cipher-data")).thenReturn("signature-data");
        when(store.reserve(eq(requestId), eq(session), anyString(), anyString(), eq(snapshot), anyString(), anyString())).thenReturn(1L);
    }
    @Test void reservesBeforeExactlyOneEncryptedPostWithExactEnvelope() {
        var result = new BankPaymentClient.Result(ProcessStatus.SENT, "T1", "00", "ok", "encrypted-response", 200);
        when(bank.send(any(), anyString())).thenReturn(result);
        service.send(session, request, "actor", "ip");
        var envelope = ArgumentCaptor.forClass(String.class);
        var order = inOrder(store, bank);
        order.verify(store).find(requestId, "TEST_DB");
        order.verify(bank).createClient();
        order.verify(store).reserve(eq(requestId), eq(session), eq("actor"), eq("11010501"), eq(snapshot), envelope.capture(), eq("ip"));
        order.verify(bank).send(any(), eq(envelope.getValue()));
        order.verify(store).complete(1L, "TEST_DB", result, "ip");
        var json = new ObjectMapper().readTree(envelope.getValue());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get("companyId").intValue()).isEqualTo(2295);
        assertThat(json.get("data").stringValue()).isEqualTo("cipher-data");
        assertThat(json.get("signature").stringValue()).isEqualTo("signature-data");
    }
    @Test void idempotentRequestNeverPostsAgainEvenWhenProcessing() {
        var existing = new BankPaymentDtos.SubmissionResponse(1L, requestId, "actor", "11010501", "LP",
            BigDecimal.TEN, ProcessStatus.PROCESSING, null, null, "pending", null, null, snapshot.documents());
        when(store.find(requestId, "TEST_DB")).thenReturn(Optional.of(existing));
        assertThat(service.send(session, request, "actor", "ip")).isSameAs(existing);
        verifyNoInteractions(preview, bank, crypto);
    }
    @Test void changedPreviewCannotBeSent() {
        var changed = new DiffusionDtos.SendRequest(request.selection(), "b".repeat(64), requestId);
        assertThatThrownBy(() -> service.send(session, changed, "actor", "ip")).hasMessageContaining("cambiaron");
        verifyNoInteractions(bank, crypto);
        verify(store, never()).reserve(any(), any(), any(), any(), any(), any(), any());
    }
    @Test void concurrentDocumentReservationFailurePreventsAnyBankPost() {
        when(store.reserve(eq(requestId), eq(session), anyString(), anyString(), any(), anyString(), anyString()))
            .thenThrow(new DataIntegrityViolationException("duplicate-document"));
        assertThatThrownBy(() -> service.send(session, request, "actor", "ip")).hasMessageContaining("reservado");
        verify(bank, never()).send(any(), any());
    }
    @Test void cryptoFailureNeverReservesOrPosts() {
        when(crypto.sign(anyString())).thenThrow(new CryptoOperationException("invalid certificate"));
        assertThatThrownBy(() -> service.send(session, request, "actor", "ip")).isInstanceOf(CryptoOperationException.class);
        verify(store, never()).reserve(any(), any(), any(), any(), any(), any(), any());
        verify(bank, never()).send(any(), any());
    }
}
