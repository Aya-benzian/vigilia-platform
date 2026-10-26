package com.vigilia.transaction.repository;

import com.vigilia.transaction.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByTransactionId(UUID transactionId);

    Optional<PaymentEntity> findByPaymentSessionId(String paymentSessionId);

    List<PaymentEntity> findByMerchantId(UUID merchantId);
}
