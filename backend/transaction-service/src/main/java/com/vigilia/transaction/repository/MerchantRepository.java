package com.vigilia.transaction.repository;

import com.vigilia.transaction.entity.MerchantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantRepository extends JpaRepository<MerchantEntity, UUID> {

    Optional<MerchantEntity> findByMerchantCode(String merchantCode);

    Optional<MerchantEntity> findByNameIgnoreCase(String name);
}
