package com.example.defusion_bcp.service;

import com.example.defusion_bcp.domain.TriggerType;
import com.example.defusion_bcp.dto.PaymentDtos;
import com.example.defusion_bcp.repository.BankStatementRequestRepository;
import com.example.defusion_bcp.repository.PaymentBatchRepository;
import com.example.defusion_bcp.repository.ProcessAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ProcessAuditIntegrationTests {
    @Autowired private StatementRequestStore statementStore;
    @Autowired private PaymentBatchService paymentService;
    @Autowired private BankStatementRequestRepository statementRepository;
    @Autowired private PaymentBatchRepository paymentRepository;
    @Autowired private ProcessAuditLogRepository auditRepository;
    @Autowired private AuditLogService auditService;

    @Test
    void previewsOneHundredPaymentsWithCompleteTraceabilityAndShortMetadataMessages() {
        List<Long> ids = LongStream.rangeClosed(1, 100).boxed().toList();
        String correlation = "00000000-0000-0000-0000-000000000001";
        auditService.recordDiffusionPreview(ids, "LP", "La Paz", "tesoreria.test", correlation, "127.0.0.1");
        auditRepository.flush();
        var events = auditRepository.findAll();
        assertThat(events).hasSize(101).allSatisfy(event -> {
            assertThat(event.getMessage().length()).isLessThan(500);
            assertThat(event.getCorrelationId()).isEqualTo(correlation);
            assertThat(event.getActor()).isEqualTo("tesoreria.test");
            assertThat(event.getOccurredAt()).isNotNull();
        });
        assertThat(events.stream().filter(event -> event.getActionName().equals("DIFFUSION_PAYMENT_PREVIEWED"))
            .map(event -> Long.parseLong(event.getEntityId())).toList()).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void statementRequestCreatesBusinessRecordAndAuditEvent() {
        statementStore.reserve("20150455090318", "202609", "tesoreria.test", "TEST_DB",
            "00000000-0000-0000-0000-000000000099", "encrypted-envelope", "127.0.0.1");

        assertThat(statementRepository.count()).isEqualTo(1);
        assertThat(auditRepository.count()).isEqualTo(1);
        assertThat(auditRepository.findAll().getFirst().getActor()).isEqualTo("tesoreria.test");
    }

    @Test
    void automaticPaymentBatchUsesSystemActorAndStoresRecipients() {
        PaymentDtos.RecipientRequest recipient = new PaymentDtos.RecipientRequest(
            "Proveedor de prueba", "9000001", "20100000000001", "SAP-TEST-1",
            new BigDecimal("1250.50")
        );
        paymentService.create(
            new PaymentDtos.CreateBatchRequest("20100000000999", "BOB", TriggerType.AUTOMATIC, List.of(recipient)),
            "usuario-ignorado", "127.0.0.1"
        );

        assertThat(paymentRepository.findTop50ByOrderByCreatedAtDesc()).singleElement()
            .satisfies(batch -> {
                assertThat(batch.getRequestedBy()).isEqualTo("SYSTEM");
                assertThat(batch.getRecipients()).hasSize(1);
            });
        assertThat(auditRepository.findAll()).singleElement()
            .satisfies(event -> assertThat(event.getActor()).isEqualTo("SYSTEM"));
    }
}
