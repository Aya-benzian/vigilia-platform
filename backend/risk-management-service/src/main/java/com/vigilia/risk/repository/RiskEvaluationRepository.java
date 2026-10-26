package com.vigilia.risk.repository;

import com.vigilia.risk.entity.RiskEvaluationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RiskEvaluationRepository extends JpaRepository<RiskEvaluationEntity, UUID> {

    Optional<RiskEvaluationEntity> findByTransactionId(UUID transactionId);

    boolean existsByTransactionId(UUID transactionId);
}
