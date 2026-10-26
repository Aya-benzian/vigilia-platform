package com.vigilia.customer.repository;

import com.vigilia.customer.entity.BeneficiaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BeneficiaryRepository extends JpaRepository<BeneficiaryEntity, UUID> {

    List<BeneficiaryEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    Optional<BeneficiaryEntity> findByIdAndCustomerId(UUID id, UUID customerId);

    boolean existsByCustomerIdAndRecipientAccountNumber(UUID customerId, String recipientAccountNumber);

    void deleteByIdAndCustomerId(UUID id, UUID customerId);
}
