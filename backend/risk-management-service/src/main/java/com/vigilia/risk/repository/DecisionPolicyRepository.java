package com.vigilia.risk.repository;

import com.vigilia.risk.entity.DecisionPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DecisionPolicyRepository extends JpaRepository<DecisionPolicyEntity, UUID> {

    /** Finds the single active policy for a given transaction type. */
    Optional<DecisionPolicyEntity> findByTransactionTypeAndIsActiveTrue(String transactionType);

    /** Finds all active policies across all transaction types. */
    List<DecisionPolicyEntity> findByIsActiveTrue();
}
