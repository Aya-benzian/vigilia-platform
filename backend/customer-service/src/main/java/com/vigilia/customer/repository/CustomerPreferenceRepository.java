package com.vigilia.customer.repository;

import com.vigilia.customer.entity.CustomerPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerPreferenceRepository extends JpaRepository<CustomerPreferenceEntity, UUID> {

    Optional<CustomerPreferenceEntity> findByCustomerId(UUID customerId);
}
