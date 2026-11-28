package com.vigilia.transaction.repository;

import com.vigilia.transaction.entity.LinkedFundingCardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LinkedFundingCardRepository extends JpaRepository<LinkedFundingCardEntity, UUID> {

    List<LinkedFundingCardEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    Optional<LinkedFundingCardEntity> findByIdAndCustomerId(UUID id, UUID customerId);

    Optional<LinkedFundingCardEntity> findFirstByCustomerIdAndCardLast4OrderByCreatedAtDesc(UUID customerId, String cardLast4);

    boolean existsByCustomerId(UUID customerId);
}

