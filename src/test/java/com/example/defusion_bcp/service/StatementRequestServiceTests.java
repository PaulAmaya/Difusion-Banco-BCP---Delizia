package com.example.defusion_bcp.service;

import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.dto.StatementDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatementRequestServiceTests {
    @Test void encryptsEditedSevenFieldJsonSignsCiphertextAndSavesBeforeSinglePost() {
        var store = mock(StatementRequestStore.class);
        var bank = mock(BankStatementClient.class);
        var crypto = mock(BankCryptoService.class);
        var mapper = new ObjectMapper();
        var service = new StatementRequestService(store, bank, crypto, mapper,
            mock(BankPaymentClient.class), mock(DiffusionPreviewService.class), 2295, "test-password", "9999999");
        var payload = new StatementDtos.BankPayload(2295, "edited-password", "9999999", "Q", "LP", "20150735205363", "202507");
        when(crypto.encrypt(anyString())).thenReturn("encrypted-data");
        when(crypto.sign("encrypted-data")).thenReturn("signed-data");
        when(store.reserve(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(7L);
        var bankResult = new StatementDtos.BankResponse(ProcessStatus.REJECTED, 200, false, "raw", null, "cipher", null, "message", null);
        when(bank.send(any(), anyString())).thenReturn(bankResult);
        service.create(new StatementDtos.CreateRequest(payload), "actor", "TEST_DB", "ip");
        var plain = ArgumentCaptor.forClass(String.class);
        verify(crypto).encrypt(plain.capture());
        var parsed = mapper.readTree(plain.getValue());
        assertThat(parsed.size()).isEqualTo(7);
        assertThat(parsed.path("password").asString()).isEqualTo("edited-password");
        assertThat(parsed.path("accountNumber").asString()).isEqualTo("20150735205363");
        assertThat(parsed.path("period").asString()).isEqualTo("202507");
        var envelope = ArgumentCaptor.forClass(String.class);
        var order = inOrder(store, bank);
        order.verify(bank).prepare();
        order.verify(store).reserve(eq("20150735205363"), eq("202507"), eq("actor"), eq("TEST_DB"), anyString(), envelope.capture(), eq("ip"));
        order.verify(bank).send(any(), eq(envelope.getValue()));
        order.verify(store).complete(7L, "TEST_DB", bankResult, "ip");
        var outer = mapper.readTree(envelope.getValue());
        assertThat(outer.size()).isEqualTo(3);
        assertThat(outer.path("companyId").asInt()).isEqualTo(2295);
        assertThat(outer.path("data").asString()).isEqualTo("encrypted-data");
        assertThat(outer.path("signature").asString()).isEqualTo("signed-data");
        verify(bank, times(1)).send(any(), anyString());
    }
    @Test void encryptionFailureNeverRecordsOrPostsRequest() {
        var store = mock(StatementRequestStore.class);
        var bank = mock(BankStatementClient.class);
        var crypto = mock(BankCryptoService.class);
        when(crypto.encrypt(anyString())).thenThrow(new CryptoOperationException("test"));
        var service = new StatementRequestService(store, bank, crypto, new ObjectMapper(),
            mock(BankPaymentClient.class), mock(DiffusionPreviewService.class), 2295, "test", "9999999");
        var request = new StatementDtos.CreateRequest(new StatementDtos.BankPayload(2295, "test", "9999999", "Q", "LP", "00123", "202506"));
        assertThatThrownBy(() -> service.create(request, "actor", "TEST_DB", "ip")).isInstanceOf(CryptoOperationException.class);
        verifyNoInteractions(store);
        verify(bank, never()).send(any(), anyString());
    }
}
