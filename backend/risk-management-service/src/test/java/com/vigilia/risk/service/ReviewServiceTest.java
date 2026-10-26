package com.vigilia.risk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.risk.dto.AnalystDecisionRequest;
import com.vigilia.risk.dto.RiskStatus;
import com.vigilia.risk.entity.ReviewCaseEntity;
import com.vigilia.risk.entity.ReviewDecisionEntity;
import com.vigilia.risk.entity.ReviewNoteEntity;
import com.vigilia.risk.exception.ResourceNotFoundException;
import com.vigilia.risk.repository.OutboxEventRepository;
import com.vigilia.risk.repository.ReviewCaseRepository;
import com.vigilia.risk.repository.ReviewDecisionRepository;
import com.vigilia.risk.repository.ReviewNoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewService unit tests")
class ReviewServiceTest {

    @Mock private ReviewCaseRepository caseRepository;
    @Mock private ReviewDecisionRepository decisionRepository;
    @Mock private ReviewNoteRepository noteRepository;
    @Mock private OutboxEventRepository outboxRepository;

    private ReviewService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        service = new ReviewService(caseRepository, decisionRepository, noteRepository,
                outboxRepository, objectMapper);
    }

    private ReviewCaseEntity openCase(UUID caseId) {
        ReviewCaseEntity rc = new ReviewCaseEntity();
        rc.setId(caseId);
        rc.setTransactionId(UUID.randomUUID());
        rc.setStatus("OPEN");
        rc.setPriority("MEDIUM");
        rc.setSlaDueAt(Instant.now().plusSeconds(14400));
        rc.setOpenedAt(Instant.now());
        rc.setVersion(0L);
        return rc;
    }

    @Test
    @DisplayName("getCase: throws ResourceNotFoundException when case not found")
    void getCase_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(caseRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCase(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("claimCase: assigns analyst and sets CLAIMED status")
    void claimCase_assignsAnalyst() {
        UUID caseId = UUID.randomUUID();
        UUID analystId = UUID.randomUUID();
        ReviewCaseEntity rc = openCase(caseId);

        when(caseRepository.findByIdForUpdate(caseId)).thenReturn(Optional.of(rc));
        when(caseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReviewCaseEntity result = service.claimCase(caseId, analystId);

        assertThat(result.getStatus()).isEqualTo("CLAIMED");
        assertThat(result.getAssignedAnalystId()).isEqualTo(analystId);
        assertThat(result.getClaimedAt()).isNotNull();
    }

    @Test
    @DisplayName("claimCase: throws IllegalStateException on resolved case")
    void claimCase_resolvedCase_throws() {
        UUID caseId = UUID.randomUUID();
        ReviewCaseEntity rc = openCase(caseId);
        rc.setStatus("RESOLVED");

        when(caseRepository.findByIdForUpdate(caseId)).thenReturn(Optional.of(rc));

        assertThatThrownBy(() -> service.claimCase(caseId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already resolved");
    }

    @Test
    @DisplayName("submitDecision: APPROVED decision resolves case and publishes outbox event")
    void submitDecision_approved_resolvesCase() {
        UUID caseId = UUID.randomUUID();
        ReviewCaseEntity rc = openCase(caseId);

        AnalystDecisionRequest decisionReq = new AnalystDecisionRequest(
                UUID.randomUUID().toString(), RiskStatus.APPROVED, "Verified legitimate transaction");

        when(caseRepository.findByIdForUpdate(caseId)).thenReturn(Optional.of(rc));
        when(caseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReviewDecisionEntity result = service.submitDecision(caseId, decisionReq);

        assertThat(result.getDecision()).isEqualTo("APPROVED");
        assertThat(rc.getStatus()).isEqualTo("RESOLVED");
        assertThat(rc.getResolvedAt()).isNotNull();
        verify(outboxRepository).save(any());
    }

    @Test
    @DisplayName("submitDecision: PENDING_REVIEW decision throws IllegalArgumentException")
    void submitDecision_pendingReview_throws() {
        UUID caseId = UUID.randomUUID();
        ReviewCaseEntity rc = openCase(caseId);

        AnalystDecisionRequest decisionReq = new AnalystDecisionRequest(
                UUID.randomUUID().toString(), RiskStatus.PENDING_REVIEW, "Cannot decide");

        when(caseRepository.findByIdForUpdate(caseId)).thenReturn(Optional.of(rc));

        assertThatThrownBy(() -> service.submitDecision(caseId, decisionReq))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PENDING_REVIEW");
    }

    @Test
    @DisplayName("submitDecision: REJECTED decision resolves case")
    void submitDecision_rejected_resolvesCase() {
        UUID caseId = UUID.randomUUID();
        ReviewCaseEntity rc = openCase(caseId);

        AnalystDecisionRequest decisionReq = new AnalystDecisionRequest(
                UUID.randomUUID().toString(), RiskStatus.REJECTED, "Confirmed fraud pattern");

        when(caseRepository.findByIdForUpdate(caseId)).thenReturn(Optional.of(rc));
        when(caseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReviewDecisionEntity result = service.submitDecision(caseId, decisionReq);

        assertThat(result.getDecision()).isEqualTo("REJECTED");
        assertThat(rc.getStatus()).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("addNote: persists note on open case")
    void addNote_persistsNote() {
        UUID caseId = UUID.randomUUID();
        UUID analystId = UUID.randomUUID();
        ReviewCaseEntity rc = openCase(caseId);

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(rc));
        when(noteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReviewNoteEntity result = service.addNote(caseId, analystId, "Suspicious transaction pattern detected");

        assertThat(result.getNote()).isEqualTo("Suspicious transaction pattern detected");
        assertThat(result.getAnalystId()).isEqualTo(analystId);
        verify(noteRepository).save(any());
    }

    @Test
    @DisplayName("addNote: throws IllegalStateException on resolved case")
    void addNote_resolvedCase_throws() {
        UUID caseId = UUID.randomUUID();
        ReviewCaseEntity rc = openCase(caseId);
        rc.setStatus("RESOLVED");

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(rc));

        assertThatThrownBy(() -> service.addNote(caseId, UUID.randomUUID(), "some note"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolved");
    }

    @Test
    @DisplayName("listOpenCases: strictly queries repository with status 'OPEN'")
    void listOpenCases_onlyQueriesOpenStatusCases() {
        UUID caseId = UUID.randomUUID();
        ReviewCaseEntity open = openCase(caseId);
        Page<ReviewCaseEntity> page = new PageImpl<>(List.of(open));

        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findByStatus("OPEN", pageable)).thenReturn(page);

        Page<ReviewCaseEntity> result = service.listOpenCases(pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo("OPEN");
        // Verify filtering strictly targets "OPEN" status cases
        verify(caseRepository).findByStatus("OPEN", pageable);
        verify(caseRepository, never()).findByStatus(eq("CLAIMED"), any());
        verify(caseRepository, never()).findByStatus(eq("RESOLVED"), any());
    }

    @Test
    @DisplayName("E6: Outbox serialization failure in submitDecision throws IllegalStateException and does not persist outbox")
    void submitDecision_outboxSerializationFailure_throwsIllegalStateException() throws Exception {
        UUID caseId = UUID.randomUUID();
        ReviewCaseEntity rc = openCase(caseId);

        AnalystDecisionRequest decisionReq = new AnalystDecisionRequest(
                UUID.randomUUID().toString(), RiskStatus.APPROVED, "Verified legitimate transaction");

        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("Serialization error") {});

        ReviewService serviceWithFailingMapper = new ReviewService(
                caseRepository, decisionRepository, noteRepository, outboxRepository, failingMapper);

        when(caseRepository.findByIdForUpdate(caseId)).thenReturn(Optional.of(rc));
        when(caseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> serviceWithFailingMapper.submitDecision(caseId, decisionReq))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot serialize outbox event");

        verify(outboxRepository, never()).save(any());
    }
}
