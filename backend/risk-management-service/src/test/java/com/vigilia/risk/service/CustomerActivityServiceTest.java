package com.vigilia.risk.service;

import com.vigilia.risk.entity.CustomerActivityEntity;
import com.vigilia.risk.repository.CustomerActivityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerActivityService unit tests")
class CustomerActivityServiceTest {

    @Mock
    private CustomerActivityRepository activityRepository;

    @InjectMocks
    private CustomerActivityService service;

    @Test
    @DisplayName("countTransactionsInLastHour returns repository count")
    void countTransactionsInLastHour_returnsCount() {
        UUID customerId = UUID.randomUUID();
        when(activityRepository.countByCustomerIdSince(eq(customerId), any(Instant.class))).thenReturn(7L);

        long count = service.countTransactionsInLastHour(customerId);

        assertThat(count).isEqualTo(7L);
    }

    @Test
    @DisplayName("recordActivity saves a new entity")
    void recordActivity_savesEntity() {
        UUID customerId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        when(activityRepository.existsByTransactionId(transactionId)).thenReturn(false);
        when(activityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordActivity(customerId, transactionId, "PURCHASE",
                new BigDecimal("100.00"), null, "shopping_net",
                40.71, -74.01, "SETTLED", Instant.now());

        verify(activityRepository).save(any(CustomerActivityEntity.class));
    }

    @Test
    @DisplayName("recordActivity is idempotent: skips duplicate transaction")
    void recordActivity_idempotent_skipsExisting() {
        UUID customerId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        when(activityRepository.existsByTransactionId(transactionId)).thenReturn(true);

        service.recordActivity(customerId, transactionId, "PURCHASE",
                new BigDecimal("100.00"), null, null, null, null, "SETTLED", Instant.now());

        verify(activityRepository, never()).save(any());
    }

    // Utility for eq() on UUID
    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
