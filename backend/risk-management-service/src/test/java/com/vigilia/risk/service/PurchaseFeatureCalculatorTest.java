package com.vigilia.risk.service;

import com.vigilia.risk.dto.RiskEvaluationRequest;
import com.vigilia.risk.dto.TransactionType;
import com.vigilia.risk.entity.CustomerActivityEntity;
import com.vigilia.risk.repository.CustomerActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseFeatureCalculator unit tests (M2 purchase-v2 audit resolutions)")
class PurchaseFeatureCalculatorTest {

    @Mock
    private CustomerActivityRepository activityRepository;

    private PurchaseFeatureCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new PurchaseFeatureCalculator(activityRepository);
    }

    private RiskEvaluationRequest buildRequest(String merchantId, double custLat, double custLon, double merchLat, double merchLon) {
        RiskEvaluationRequest req = new RiskEvaluationRequest();
        req.setTransactionId(UUID.randomUUID());
        req.setCustomerId(UUID.randomUUID());
        req.setTransactionType(TransactionType.PURCHASE);
        req.setAmount(new BigDecimal("150.00"));
        req.setCurrency("EUR");
        req.setTimestamp(Instant.parse("2026-06-15T14:30:00Z"));
        req.setMerchantId(merchantId);
        req.setMerchantCategory("retail");
        req.setCustomerLatitude(custLat);
        req.setCustomerLongitude(custLon);
        req.setMerchantLatitude(merchLat);
        req.setMerchantLongitude(merchLon);
        return req;
    }

    private CustomerActivityEntity createPastPurchase(UUID customerId, String merchantId, String category, double lat, double lon, Instant occurredAt) {
        CustomerActivityEntity e = new CustomerActivityEntity();
        e.setId(UUID.randomUUID());
        e.setCustomerId(customerId);
        e.setTransactionId(UUID.randomUUID());
        e.setTransactionType("PURCHASE");
        e.setAmount(new BigDecimal("80.00"));
        e.setCounterpartyId(merchantId);
        e.setCategory(category);
        e.setLat(BigDecimal.valueOf(lat));
        e.setLon(BigDecimal.valueOf(lon));
        e.setFinalStatus("APPROVED");
        e.setOccurredAt(occurredAt);
        return e;
    }

    @Test
    @DisplayName("Audit B1 & B2: 3 past purchases at 'M-123' produces merchant_visit_count = 3 and is_new_merchant = 0")
    void computesMerchantVisitCountAndIsNewMerchant_forRepeatMerchant() {
        String merchantId = "M-123";
        RiskEvaluationRequest req = buildRequest(merchantId, 48.8566, 2.3522, 48.8600, 2.3500);

        Instant txTime = req.getTimestamp();
        CustomerActivityEntity p1 = createPastPurchase(req.getCustomerId(), merchantId, "retail", 48.8600, 2.3500, txTime.minusSeconds(10000));
        CustomerActivityEntity p2 = createPastPurchase(req.getCustomerId(), merchantId, "retail", 48.8600, 2.3500, txTime.minusSeconds(5000));
        CustomerActivityEntity p3 = createPastPurchase(req.getCustomerId(), merchantId, "groceries", 48.8600, 2.3500, txTime.minusSeconds(1000));

        when(activityRepository.findPurchaseHistoryBefore(eq(req.getCustomerId()), eq(txTime)))
                .thenReturn(List.of(p1, p2, p3));

        Map<String, Object> features = calculator.compute(req);

        assertThat(features.get("merchant_visit_count")).isEqualTo(3);
        assertThat(features.get("is_new_merchant")).isEqualTo(0);
    }

    @Test
    @DisplayName("Audit B1 & B2: First purchase at a new merchant produces merchant_visit_count = 0 and is_new_merchant = 1")
    void computesMerchantVisitCountAndIsNewMerchant_forNewMerchant() {
        RiskEvaluationRequest req = buildRequest("M-NEW", 48.8566, 2.3522, 48.8600, 2.3500);

        Instant txTime = req.getTimestamp();
        CustomerActivityEntity p1 = createPastPurchase(req.getCustomerId(), "M-OLD-1", "retail", 48.8600, 2.3500, txTime.minusSeconds(5000));
        CustomerActivityEntity p2 = createPastPurchase(req.getCustomerId(), "M-OLD-2", "retail", 48.8600, 2.3500, txTime.minusSeconds(2000));

        when(activityRepository.findPurchaseHistoryBefore(eq(req.getCustomerId()), eq(txTime)))
                .thenReturn(List.of(p1, p2));

        Map<String, Object> features = calculator.compute(req);

        assertThat(features.get("merchant_visit_count")).isEqualTo(0);
        assertThat(features.get("is_new_merchant")).isEqualTo(1);
    }

    @Test
    @DisplayName("Audit B3 & B4: Computes avg_distance_home_km from history and distance_zscore > 0 for distant purchase")
    void computesAvgDistanceAndDistanceZScore_fromHistory() {
        // Customer home in Paris: (48.8566, 2.3522)
        // Current purchase in Lyon: (45.7640, 4.8357) ~ 390 km away
        RiskEvaluationRequest req = buildRequest("M-LYON", 48.8566, 2.3522, 45.7640, 4.8357);
        Instant txTime = req.getTimestamp();

        // Past purchases in Paris neighborhood:
        // p1: 48.8600, 2.3500 (~0.4 km)
        // p2: 48.8700, 2.3400 (~1.7 km)
        // p3: 48.8500, 2.3600 (~0.9 km)
        CustomerActivityEntity p1 = createPastPurchase(req.getCustomerId(), "M-1", "retail", 48.8600, 2.3500, txTime.minusSeconds(10000));
        CustomerActivityEntity p2 = createPastPurchase(req.getCustomerId(), "M-2", "groceries", 48.8700, 2.3400, txTime.minusSeconds(5000));
        CustomerActivityEntity p3 = createPastPurchase(req.getCustomerId(), "M-3", "retail", 48.8500, 2.3600, txTime.minusSeconds(1000));

        when(activityRepository.findPurchaseHistoryBefore(eq(req.getCustomerId()), eq(txTime)))
                .thenReturn(List.of(p1, p2, p3));

        Map<String, Object> features = calculator.compute(req);

        double avgDist = (Double) features.get("avg_distance_home_km");
        double zscore = (Double) features.get("distance_zscore");
        double currentDist = (Double) features.get("distance_home_merchant_km");

        // Current distance to Lyon is ~390 km
        assertThat(currentDist).isGreaterThan(380.0);
        // Past avg distance should be around 1 km (Paris neighborhood), NOT 390 km!
        assertThat(avgDist).isLessThan(5.0);
        assertThat(avgDist).isGreaterThan(0.2);
        // Z-score must be strictly positive and large (> 10) representing the geographic rupture
        assertThat(zscore).isGreaterThan(10.0);
    }

    @Test
    @DisplayName("Audit B: Cold-start returns defaults (visit=0, new=1, avg_dist=0.0, zscore=0.0)")
    void computesColdStartDefaults_whenNoHistory() {
        RiskEvaluationRequest req = buildRequest("M-1", 48.8566, 2.3522, 48.8600, 2.3500);

        when(activityRepository.findPurchaseHistoryBefore(eq(req.getCustomerId()), any()))
                .thenReturn(List.of());

        Map<String, Object> features = calculator.compute(req);

        assertThat(features.get("merchant_visit_count")).isEqualTo(0);
        assertThat(features.get("is_new_merchant")).isEqualTo(1);
        assertThat(features.get("avg_distance_home_km")).isEqualTo(0.0);
        assertThat(features.get("distance_zscore")).isEqualTo(0.0);
        assertThat(features.get("history_length")).isEqualTo(0);
        assertThat(features.get("time_since_last_txn_s")).isEqualTo(2_592_000.0);
    }
}
