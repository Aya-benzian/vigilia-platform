package com.vigilia.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.entity.OutboxEventEntity;
import com.vigilia.transaction.entity.TransactionEntity;
import com.vigilia.transaction.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransactionOutboxServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private ObjectMapper objectMapper;
    private TransactionOutboxService outboxService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        outboxService = new TransactionOutboxService(outboxEventRepository, objectMapper);
    }

    @Test
    @DisplayName("publishTransactionCompleted pour APPROVED enregistre un événement TRANSACTION_COMPLETED")
    void publishTransactionCompleted_Approved() {
        UUID txnId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        TransactionEntity txn = new TransactionEntity("PURCHASE", customerId, new BigDecimal("100.00"), "EUR", "APPROVED");
        txn.setId(txnId);

        outboxService.publishTransactionCompleted(txn, RiskStatus.APPROVED, "trace-123");

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEventEntity saved = captor.getValue();
        assertThat(saved.getAggregateType()).isEqualTo("TRANSACTION");
        assertThat(saved.getAggregateId()).isEqualTo(txnId.toString());
        assertThat(saved.getEventType()).isEqualTo("TRANSACTION_COMPLETED");
        assertThat(saved.getTraceId()).isEqualTo("trace-123");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getPayload()).contains("TRANSACTION_COMPLETED", txnId.toString(), "100.00");
    }

    @Test
    @DisplayName("publishTransactionCompleted pour REJECTED enregistre un événement TRANSACTION_REJECTED")
    void publishTransactionCompleted_Rejected() {
        UUID txnId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        TransactionEntity txn = new TransactionEntity("TRANSFER", customerId, new BigDecimal("50.00"), "EUR", "REJECTED");
        txn.setId(txnId);

        outboxService.publishTransactionCompleted(txn, RiskStatus.REJECTED, "trace-456");

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEventEntity saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("TRANSACTION_REJECTED");
        assertThat(saved.getTraceId()).isEqualTo("trace-456");
        assertThat(saved.getPayload()).contains("TRANSACTION_REJECTED");
    }

    @Test
    @DisplayName("publishTransactionResolved enregistre un événement TRANSACTION_RESOLVED avec détails analyste")
    void publishTransactionResolved_Success() {
        UUID txnId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        TransactionEntity txn = new TransactionEntity("PURCHASE", customerId, new BigDecimal("75.00"), "EUR", "APPROVED");
        txn.setId(txnId);

        outboxService.publishTransactionResolved(txn, RiskStatus.APPROVED, "Manual validation OK", "analyst-007", null);

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEventEntity saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("TRANSACTION_RESOLVED");
        assertThat(saved.getPayload()).contains("TRANSACTION_RESOLVED", "Manual validation OK", "analyst-007");
    }
}
