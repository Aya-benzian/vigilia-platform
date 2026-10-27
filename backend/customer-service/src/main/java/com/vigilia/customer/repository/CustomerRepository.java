package com.vigilia.customer.repository;

import com.vigilia.customer.entity.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {

    Optional<CustomerEntity> findByUserId(UUID userId);

    Optional<CustomerEntity> findByAccountNumber(String accountNumber);

    boolean existsByUserId(UUID userId);

    boolean existsByAccountNumber(String accountNumber);
}
