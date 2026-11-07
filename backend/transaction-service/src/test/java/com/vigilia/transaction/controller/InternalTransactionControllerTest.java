package com.vigilia.transaction.controller;

import com.vigilia.transaction.dto.ApiResponse;
import com.vigilia.transaction.dto.ReviewDecisionEventPayload;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TransactionResponse;
import com.vigilia.transaction.dto.TransactionType;
import com.vigilia.transaction.entity.ProcessedEventEntity;
import com.vigilia.transaction.entity.TransactionEntity;
import com.vigilia.transaction.repository.ProcessedEventRepository;
import com.vigilia.transaction.repository.TransactionRepository;
import com.vigilia.transaction.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalTransactionControllerTest {

    @Mock
    private TransactionService transactionService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private InternalTransactionController controller;

    private UUID eventId;
    private UUID transactionId;

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID();
        transactionId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Idempotence : un événement déjà traité ne réapplique pas la décision financière")
    void ingestReviewDecision_DuplicateEvent_Ignored() {
        ReviewDecisionEventPayload payload = new ReviewDecisionEventPayload(
                eventId, transactionId, RiskStatus.APPROVED, "Manual OK", "analyst-1"
        );

        when(processedEventRepository.existsById(eventId)).thenReturn(true);
        TransactionResponse existingResponse = new TransactionResponse(
                transactionId, UUID.randomUUID(), TransactionType.PURCHASE, new BigDecimal("50.00"), "EUR", RiskStatus.APPROVED, null, null, Instant.now()
        );
        when(transactionService.getTransactionById(transactionId)).thenReturn(existingResponse);

        ResponseEntity<ApiResponse<TransactionResponse>> response = controller.ingestReviewDecision(payload);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().getData().getStatus()).isEqualTo(RiskStatus.APPROVED);
        verify(transactionService, never()).resolveAnalystDecision(any(), any(), any(), any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Succès : décision de revue appliquée et événement enregistré dans processed_events")
    void ingestReviewDecision_Success() {
        ReviewDecisionEventPayload payload = new ReviewDecisionEventPayload(
                eventId, transactionId, RiskStatus.APPROVED, "Manual OK", "analyst-1"
        );

        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        TransactionEntity txn = new TransactionEntity("PURCHASE", UUID.randomUUID(), new BigDecimal("50.00"), "EUR", "PENDING_REVIEW");
        txn.setId(transactionId);
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(txn));

        TransactionResponse resolvedResponse = new TransactionResponse(
                transactionId, UUID.randomUUID(), TransactionType.PURCHASE, new BigDecimal("50.00"), "EUR", RiskStatus.APPROVED, null, null, Instant.now()
        );
        when(transactionService.resolveAnalystDecision(transactionId, RiskStatus.APPROVED, "Manual OK", "analyst-1"))
                .thenReturn(resolvedResponse);

        ResponseEntity<ApiResponse<TransactionResponse>> response = controller.ingestReviewDecision(payload);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().getData().getStatus()).isEqualTo(RiskStatus.APPROVED);
        verify(transactionService).resolveAnalystDecision(transactionId, RiskStatus.APPROVED, "Manual OK", "analyst-1");
        verify(processedEventRepository).save(any(ProcessedEventEntity.class));
    }

    @Test
    @DisplayName("Transaction déjà finalisée : idempotent, enregistre l'événement sans réapplication")
    void ingestReviewDecision_AlreadyFinalized() {
        ReviewDecisionEventPayload payload = new ReviewDecisionEventPayload(
                eventId, transactionId, RiskStatus.APPROVED, "Manual OK", "analyst-1"
        );

        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        TransactionEntity txn = new TransactionEntity("PURCHASE", UUID.randomUUID(), new BigDecimal("50.00"), "EUR", "APPROVED");
        txn.setId(transactionId);
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(txn));

        TransactionResponse existingResponse = new TransactionResponse(
                transactionId, UUID.randomUUID(), TransactionType.PURCHASE, new BigDecimal("50.00"), "EUR", RiskStatus.APPROVED, null, null, Instant.now()
        );
        when(transactionService.getTransactionById(transactionId)).thenReturn(existingResponse);

        ResponseEntity<ApiResponse<TransactionResponse>> response = controller.ingestReviewDecision(payload);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(transactionService, never()).resolveAnalystDecision(any(), any(), any(), any());
        verify(processedEventRepository).save(any(ProcessedEventEntity.class));
    }
}
