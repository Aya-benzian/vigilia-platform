package com.vigilia.risk.repository;

import com.vigilia.risk.entity.RiskEvaluationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RiskEvaluationRepository extends JpaRepository<RiskEvaluationEntity, UUID> {

    Optional<RiskEvaluationEntity> findByTransactionId(UUID transactionId);

    boolean existsByTransactionId(UUID transactionId);

    // Count by decision
    long countByDecision(String decision);

    // Count by bucket (fraud_probability ranges)
    @Query("SELECT COUNT(r) FROM RiskEvaluationEntity r WHERE r.fraudProbability <= 0.25")
    long countBucket0();

    @Query("SELECT COUNT(r) FROM RiskEvaluationEntity r WHERE r.fraudProbability > 0.25 AND r.fraudProbability <= 0.50")
    long countBucket1();

    @Query("SELECT COUNT(r) FROM RiskEvaluationEntity r WHERE r.fraudProbability > 0.50 AND r.fraudProbability <= 0.75")
    long countBucket2();

    @Query("SELECT COUNT(r) FROM RiskEvaluationEntity r WHERE r.fraudProbability > 0.75")
    long countBucket3();

    // Count by decision_reason
    long countByDecisionReason(String decisionReason);

    // Average inference latency
    @Query("SELECT AVG(r.inferenceMs) FROM RiskEvaluationEntity r WHERE r.inferenceMs IS NOT NULL")
    Double avgInferenceMs();

    // Latest model version
    @Query("SELECT r.modelVersion FROM RiskEvaluationEntity r WHERE r.modelVersion IS NOT NULL ORDER BY r.createdAt DESC")
    List<String> findLatestModelVersions(Pageable pageable);
}
