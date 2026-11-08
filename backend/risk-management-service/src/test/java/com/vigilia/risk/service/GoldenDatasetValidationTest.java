package com.vigilia.risk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Task 8: Golden Dataset Validation.
 *
 * Validates that:
 * 1. The canonical 29 Purchase features produced by PurchaseFeatureCalculator match
 *    the schema and mathematical calculations of ml/training/artifacts/golden_rows_purchase.json.
 * 2. The canonical 20 Transfer features produced by TransferFeatureCalculator match
 *    the schema and mathematical calculations of ml/training/artifacts/golden_rows_transfer.json.
 * 3. Feature calculations (log1p, ratios, z-scores, velocity, flags) are mathematically consistent
 *    between Java and Python to avoid training-serving skew.
 */
@ExtendWith(MockitoExtension.class)
class GoldenDatasetValidationTest {

    private static final Set<String> EXPECTED_PURCHASE_COLUMNS_29 = Set.of(
            "amount", "log_amount", "hour_of_day", "day_of_week", "is_weekend",
            "is_night", "customer_age", "distance_home_merchant_km", "city_pop_log",
            "merchant_category", "customer_avg_amount", "customer_std_amount",
            "customer_max_amount", "amount_to_avg_ratio", "amount_zscore_customer",
            "hour_share", "category_share", "merchant_visit_count", "avg_distance_home_km",
            "distance_zscore", "txn_count_1h", "txn_count_24h", "txn_count_7d",
            "amount_sum_24h", "time_since_last_txn_s", "is_new_merchant",
            "is_new_category", "history_length", "days_since_first_txn"
    );

    private static final Set<String> EXPECTED_TRANSFER_COLUMNS_20 = Set.of(
            "amount", "log_amount", "hour_of_day", "day_of_week",
            "sender_avg_amount", "sender_std_amount", "sender_max_amount",
            "amount_to_avg_ratio", "sender_amount_zscore", "is_new_recipient",
            "pair_transfer_count", "recipient_share", "days_since_last_pair_transfer",
            "distinct_recipients_30d", "recipient_inbound_count_24h",
            "recipient_unique_senders_24h", "sender_txn_count_1h", "sender_txn_count_24h",
            "amount_to_balance_ratio", "history_length"
    );

    @Mock
    private CustomerActivityRepository activityRepository;

    private PurchaseFeatureCalculator purchaseCalculator;
    private TransferFeatureCalculator transferCalculator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        purchaseCalculator = new PurchaseFeatureCalculator(activityRepository);
        transferCalculator = new TransferFeatureCalculator(activityRepository);
    }

    private File findArtifactFile(String filename) {
        List<Path> candidates = List.of(
                Paths.get("../../ml/training/artifacts", filename),
                Paths.get("../ml/training/artifacts", filename),
                Paths.get("ml/training/artifacts", filename)
        );
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p.toFile();
            }
        }
        return null;
    }

    @Test
    @DisplayName("Task 8: Golden rows for Purchase contain exactly the 29 canonical purchase-v2 features")
    void goldenDataset_purchase_containsAll29Features() throws Exception {
        File goldenFile = findArtifactFile("golden_rows_purchase.json");
        assertThat(goldenFile)
                .as("golden_rows_purchase.json must exist in ml artifacts")
                .isNotNull()
                .exists();

        List<Map<String, Object>> rows = objectMapper.readValue(
                goldenFile,
                new TypeReference<>() {}
        );
        assertThat(rows).isNotEmpty();

        // Check first 20 rows
        int sampleSize = Math.min(20, rows.size());
        for (int i = 0; i < sampleSize; i++) {
            Map<String, Object> row = rows.get(i);
            assertThat(row.keySet())
                    .as("Row %d should contain exactly all 29 purchase-v2 features", i)
                    .containsAll(EXPECTED_PURCHASE_COLUMNS_29);

            // Verify mathematical formulas from golden rows
            double amount = ((Number) row.get("amount")).doubleValue();
            double logAmount = ((Number) row.get("log_amount")).doubleValue();
            assertThat(logAmount).isCloseTo(Math.log1p(amount), offset(1e-5));

            int hour = ((Number) row.get("hour_of_day")).intValue();
            int isNight = ((Number) row.get("is_night")).intValue();
            assertThat(isNight).isEqualTo(hour <= 5 ? 1 : 0);

            int dow = ((Number) row.get("day_of_week")).intValue();
            int isWeekend = ((Number) row.get("is_weekend")).intValue();
            assertThat(isWeekend).isEqualTo((dow == 5 || dow == 6) ? 1 : 0);

            double avgAmt = ((Number) row.get("customer_avg_amount")).doubleValue();
            double ratio = ((Number) row.get("amount_to_avg_ratio")).doubleValue();
            assertThat(ratio).isCloseTo(amount / Math.max(avgAmt, 1.0), offset(1e-4));
        }
    }

    @Test
    @DisplayName("Task 8: Golden rows for Transfer contain exactly the 20 canonical transfer-v2 features")
    void goldenDataset_transfer_containsAll20Features() throws Exception {
        File goldenFile = findArtifactFile("golden_rows_transfer.json");
        assertThat(goldenFile)
                .as("golden_rows_transfer.json must exist in ml artifacts")
                .isNotNull()
                .exists();

        List<Map<String, Object>> rows = objectMapper.readValue(
                goldenFile,
                new TypeReference<>() {}
        );
        assertThat(rows).isNotEmpty();

        int sampleSize = Math.min(20, rows.size());
        for (int i = 0; i < sampleSize; i++) {
            Map<String, Object> row = rows.get(i);
            assertThat(row.keySet())
                    .as("Row %d should contain exactly all 20 transfer-v2 features", i)
                    .containsAll(EXPECTED_TRANSFER_COLUMNS_20);

            double amount = ((Number) row.get("amount")).doubleValue();
            double logAmount = ((Number) row.get("log_amount")).doubleValue();
            assertThat(logAmount).isCloseTo(Math.log1p(amount), offset(1e-5));

            double senderAvg = ((Number) row.get("sender_avg_amount")).doubleValue();
            double ratio = ((Number) row.get("amount_to_avg_ratio")).doubleValue();
            assertThat(ratio).isCloseTo(amount / Math.max(senderAvg, 1.0), offset(1e-4));
        }
    }

    @Test
    @DisplayName("Task 8: PurchaseFeatureCalculator generates all 29 features matching golden schema")
    void purchaseFeatureCalculator_computesAll29FeaturesConsistentWithGolden() {
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-15T14:30:00Z"); // Monday (dow=0), 14:00 (is_night=0)

        RiskEvaluationRequest req = new RiskEvaluationRequest();
        req.setTransactionId(UUID.randomUUID());
        req.setCustomerId(customerId);
        req.setTransactionType(TransactionType.PURCHASE);
        req.setAmount(new BigDecimal("120.00"));
        req.setTimestamp(now);
        req.setCustomerAge(35);
        req.setCityPopulation(50000);
        req.setMerchantCategory("groceries");
        req.setCustomerLatitude(48.8566);
        req.setCustomerLongitude(2.3522);
        req.setMerchantLatitude(48.8600);
        req.setMerchantLongitude(2.3500);

        CustomerActivityEntity pastTx1 = new CustomerActivityEntity();
        pastTx1.setId(UUID.randomUUID());
        pastTx1.setCustomerId(customerId);
        pastTx1.setAmount(new BigDecimal("80.00"));
        pastTx1.setCategory("groceries");
        pastTx1.setOccurredAt(now.minusSeconds(7200));

        CustomerActivityEntity pastTx2 = new CustomerActivityEntity();
        pastTx2.setId(UUID.randomUUID());
        pastTx2.setCustomerId(customerId);
        pastTx2.setAmount(new BigDecimal("100.00"));
        pastTx2.setCategory("electronics");
        pastTx2.setOccurredAt(now.minusSeconds(14400));

        when(activityRepository.findPurchaseHistoryBefore(eq(customerId), eq(now)))
                .thenReturn(List.of(pastTx2, pastTx1));
        when(activityRepository.countPurchaseInWindow(eq(customerId), any(), eq(now)))
                .thenReturn(1L);
        when(activityRepository.sumPurchaseAmountInWindow(eq(customerId), any(), eq(now)))
                .thenReturn(new BigDecimal("80.00"));

        Map<String, Object> features = purchaseCalculator.compute(req);

        // 1. Must produce exactly the 29 keys
        assertThat(features.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_PURCHASE_COLUMNS_29);

        // 2. Validate individual values
        assertThat((Double) features.get("amount")).isEqualTo(120.0);
        assertThat((Double) features.get("log_amount")).isCloseTo(Math.log1p(120.0), offset(1e-6));
        assertThat((Integer) features.get("hour_of_day")).isEqualTo(14);
        assertThat((Integer) features.get("day_of_week")).isEqualTo(0); // Monday is 0 in Python
        assertThat((Integer) features.get("is_weekend")).isEqualTo(0);
        assertThat((Integer) features.get("is_night")).isEqualTo(0);
        assertThat((Double) features.get("customer_age")).isEqualTo(35.0);
        assertThat((Double) features.get("city_pop_log")).isCloseTo(Math.log1p(50000), offset(1e-6));
        assertThat((String) features.get("merchant_category")).isEqualTo("groceries");

        // Spending stats: past amounts = [100.0, 80.0] -> avg = 90.0, max = 100.0
        assertThat((Double) features.get("customer_avg_amount")).isEqualTo(90.0);
        assertThat((Double) features.get("customer_max_amount")).isEqualTo(100.0);
        assertThat((Double) features.get("amount_to_avg_ratio")).isCloseTo(120.0 / 90.0, offset(1e-4));
        assertThat((Integer) features.get("history_length")).isEqualTo(2);
        assertThat((Integer) features.get("is_new_category")).isEqualTo(0); // groceries was seen before
    }

    @Test
    @DisplayName("Task 8: TransferFeatureCalculator generates all 20 features matching golden schema")
    void transferFeatureCalculator_computesAll20FeaturesConsistentWithGolden() {
        UUID senderId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-15T09:15:00Z");

        RiskEvaluationRequest req = new RiskEvaluationRequest();
        req.setTransactionId(UUID.randomUUID());
        req.setCustomerId(senderId);
        req.setTransactionType(TransactionType.TRANSFER);
        req.setAmount(new BigDecimal("500.00"));
        req.setTimestamp(now);
        req.setRecipientId("rec-123");
        req.setSenderBalance(2000.0);

        CustomerActivityEntity pastTx = new CustomerActivityEntity();
        pastTx.setId(UUID.randomUUID());
        pastTx.setCustomerId(senderId);
        pastTx.setCounterpartyId("rec-123");
        pastTx.setAmount(new BigDecimal("300.00"));
        pastTx.setOccurredAt(now.minusSeconds(86400));

        when(activityRepository.findTransferHistoryBefore(eq(senderId), eq(now)))
                .thenReturn(List.of(pastTx));
        when(activityRepository.countTransferSentInWindow(eq(senderId), any(), eq(now)))
                .thenReturn(1L);
        when(activityRepository.countInboundToRecipientInWindow(eq("rec-123"), any(), eq(now)))
                .thenReturn(0L);
        when(activityRepository.countDistinctSendersToRecipientInWindow(eq("rec-123"), any(), eq(now)))
                .thenReturn(0L);

        Map<String, Object> features = transferCalculator.compute(req);

        // 1. Must produce exactly the 20 keys
        assertThat(features.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_TRANSFER_COLUMNS_20);

        // 2. Validate individual values
        assertThat((Double) features.get("amount")).isEqualTo(500.0);
        assertThat((Double) features.get("log_amount")).isCloseTo(Math.log1p(500.0), offset(1e-6));
        assertThat((Integer) features.get("hour_of_day")).isEqualTo(9);
        assertThat((Integer) features.get("day_of_week")).isEqualTo(0); // Monday is 0 in Python
        assertThat((Double) features.get("sender_avg_amount")).isEqualTo(300.0);
        assertThat((Double) features.get("sender_max_amount")).isEqualTo(300.0);
        assertThat((Double) features.get("amount_to_avg_ratio")).isCloseTo(500.0 / 300.0, offset(1e-4));
        assertThat((Integer) features.get("is_new_recipient")).isEqualTo(0); // rec-123 already seen
        assertThat((Integer) features.get("pair_transfer_count")).isEqualTo(1);
        assertThat((Double) features.get("amount_to_balance_ratio")).isCloseTo(500.0 / 2000.0, offset(1e-4));
        assertThat((Integer) features.get("history_length")).isEqualTo(1);
    }
}
