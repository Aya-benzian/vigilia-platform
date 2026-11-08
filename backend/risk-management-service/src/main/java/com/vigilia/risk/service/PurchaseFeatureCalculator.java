package com.vigilia.risk.service;

import com.vigilia.risk.dto.RiskEvaluationRequest;
import com.vigilia.risk.entity.CustomerActivityEntity;
import com.vigilia.risk.repository.CustomerActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes the canonical 29 Purchase features required by ML Serving (purchase-v2).
 *
 * Feature definitions mirror cross_domain_features.py#PURCHASE_COLUMNS_29 exactly
 * to avoid training-serving skew. All rolling windows are strictly point-in-time:
 * history is queried using events that occurred BEFORE the current transaction timestamp.
 *
 * Cold-start defaults from feature_spec.json are applied when no history exists.
 *
 * Feature groups (29 total):
 *  1. Basics          (10): amount, log_amount, hour_of_day, day_of_week, is_weekend,
 *                            is_night, customer_age, distance_home_merchant_km,
 *                            city_pop_log, merchant_category
 *  2. Spending stats   (5): customer_avg_amount, customer_std_amount, customer_max_amount,
 *                            amount_to_avg_ratio, amount_zscore_customer
 *  3. Habits           (5): hour_share, category_share, merchant_visit_count,
 *                            avg_distance_home_km, distance_zscore
 *  4. Velocity         (5): txn_count_1h, txn_count_24h, txn_count_7d,
 *                            amount_sum_24h, time_since_last_txn_s
 *  5. Novelty          (4): is_new_merchant, is_new_category, history_length,
 *                            days_since_first_txn
 */
@Component
public class PurchaseFeatureCalculator {

    private static final Logger log = LoggerFactory.getLogger(PurchaseFeatureCalculator.class);

    /** 30 days in seconds — cold-start sentinel for time_since_last_txn_s. */
    private static final double SENTINEL_TIME_S = 2_592_000.0;

    /** Earth radius in km for Haversine formula. */
    private static final double EARTH_RADIUS_KM = 6371.0;

    private final CustomerActivityRepository activityRepository;

    public PurchaseFeatureCalculator(CustomerActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    /**
     * Builds the full 29-feature map for a Purchase transaction.
     * All history queries use {@code req.getTimestamp()} as the exclusive upper bound.
     *
     * @param req the current risk evaluation request
     * @return map of feature name → value, ready to send to ML Serving
     */
    public Map<String, Object> compute(RiskEvaluationRequest req) {
        UUID customerId  = req.getCustomerId();
        Instant txTime   = req.getTimestamp();
        double  amount   = req.getAmount() != null ? req.getAmount().doubleValue() : 0.0;

        ZonedDateTime zdt = txTime.atZone(ZoneOffset.UTC);
        int hourOfDay  = zdt.getHour();
        int dayOfWeek  = zdt.getDayOfWeek().getValue() - 1; // 0=Mon...6=Sun (matches Python dt.dayofweek)

        // Retrieve full purchase history strictly before this transaction
        List<CustomerActivityEntity> history =
                activityRepository.findPurchaseHistoryBefore(customerId, txTime);

        Map<String, Object> f = new HashMap<>();

        // 1. Basics (10)
        f.put("amount",                     amount);
        f.put("log_amount",                 Math.log1p(amount));
        f.put("hour_of_day",                hourOfDay);
        f.put("day_of_week",                dayOfWeek);
        f.put("is_weekend",                 (dayOfWeek == 5 || dayOfWeek == 6) ? 1 : 0);
        f.put("is_night",                   hourOfDay <= 5 ? 1 : 0);
        f.put("customer_age",               req.getCustomerAge() != null ? req.getCustomerAge().doubleValue() : 0.0);
        f.put("distance_home_merchant_km",  computeDistanceKm(req));
        f.put("city_pop_log",               Math.log1p(req.getCityPopulation() != null ? req.getCityPopulation() : 0));
        f.put("merchant_category",          req.getMerchantCategory() != null ? req.getMerchantCategory() : "unknown");

        // 2. Spending stats (5)
        double[] spendingStats = computeSpendingStats(history, amount);
        f.put("customer_avg_amount",        spendingStats[0]);
        f.put("customer_std_amount",        spendingStats[1]);
        f.put("customer_max_amount",        spendingStats[2]);
        f.put("amount_to_avg_ratio",        spendingStats[3]);
        f.put("amount_zscore_customer",     spendingStats[4]);

        // 3. Habits (5)
        double distKm = computeDistanceKm(req);
        double[] habitStats = computeHabitStats(history, amount, hourOfDay,
                req.getMerchantCategory(), req.getMerchantId(), distKm, req);
        f.put("hour_share",                 habitStats[0]);
        f.put("category_share",             habitStats[1]);
        f.put("merchant_visit_count",       (int) habitStats[2]);
        f.put("avg_distance_home_km",       habitStats[3]);
        f.put("distance_zscore",            habitStats[4]);

        // 4. Velocity (5)
        long txn1h  = activityRepository.countPurchaseInWindow(customerId, txTime.minusSeconds(3600), txTime);
        long txn24h = activityRepository.countPurchaseInWindow(customerId, txTime.minusSeconds(86400), txTime);
        long txn7d  = activityRepository.countPurchaseInWindow(customerId, txTime.minusSeconds(7 * 86400L), txTime);
        BigDecimal sum24h = activityRepository.sumPurchaseAmountInWindow(customerId, txTime.minusSeconds(86400), txTime);

        double timeSinceLast = SENTINEL_TIME_S;
        if (!history.isEmpty()) {
            Instant lastTxTime = history.get(history.size() - 1).getOccurredAt();
            timeSinceLast = (double) (txTime.getEpochSecond() - lastTxTime.getEpochSecond());
        }

        f.put("txn_count_1h",               (int) txn1h);
        f.put("txn_count_24h",              (int) txn24h);
        f.put("txn_count_7d",               (int) txn7d);
        f.put("amount_sum_24h",             sum24h != null ? sum24h.doubleValue() : 0.0);
        f.put("time_since_last_txn_s",      timeSinceLast);

        // 5. Novelty & reliability (4)
        String cat    = req.getMerchantCategory();
        int merchantVisitCount = (int) habitStats[2];
        boolean isNewMerchant  = (merchantVisitCount == 0);
        boolean isNewCategory  = cat == null || history.stream().noneMatch(h -> cat.equals(h.getCategory()));
        int historyLength      = history.size();
        double daysSinceFirst  = history.isEmpty() ? 0.0
                : (txTime.getEpochSecond() - history.get(0).getOccurredAt().getEpochSecond()) / 86400.0;

        f.put("is_new_merchant",            isNewMerchant ? 1 : 0);
        f.put("is_new_category",            isNewCategory ? 1 : 0);
        f.put("history_length",             historyLength);
        f.put("days_since_first_txn",       daysSinceFirst);

        log.debug("[FEAT-PURCHASE] customerId={} historyLen={} txn24h={} timeSinceLast={}s",
                customerId, historyLength, txn24h, timeSinceLast);
        return f;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Haversine great-circle distance in km between customer home and merchant.
     * Mirrors cross_domain_features.py#haversine_km exactly.
     */
    private double computeDistanceKm(RiskEvaluationRequest req) {
        if (req.getCustomerLatitude() == null || req.getCustomerLongitude() == null
                || req.getMerchantLatitude() == null || req.getMerchantLongitude() == null) {
            return 0.0;
        }
        return haversineKm(
                req.getCustomerLatitude(),
                req.getCustomerLongitude(),
                req.getMerchantLatitude(),
                req.getMerchantLongitude()
        );
    }

    private double haversineKm(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        double lat1 = Math.toRadians(lat1Deg);
        double lon1 = Math.toRadians(lon1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double lon2 = Math.toRadians(lon2Deg);

        double dlat = lat2 - lat1;
        double dlon = lon2 - lon1;
        double a = Math.pow(Math.sin(dlat / 2.0), 2)
                 + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dlon / 2.0), 2);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
        return EARTH_RADIUS_KM * c;
    }

    /**
     * Computes [avg, std, max, amount_to_avg_ratio, amount_zscore].
     * Returns cold-start zeros when no history exists.
     */
    private double[] computeSpendingStats(List<CustomerActivityEntity> history, double amount) {
        if (history.isEmpty()) {
            return new double[]{0.0, 0.0, 0.0, amount / 1.0, 0.0};
        }
        double[] amounts = history.stream()
                .mapToDouble(h -> h.getAmount().doubleValue())
                .toArray();

        double avg = mean(amounts);
        double std = std(amounts);
        double max = max(amounts);
        double ratio   = amount / Math.max(avg, 1.0);
        double zscore  = std > 0.0 ? (amount - avg) / std : 0.0;
        return new double[]{avg, std, max, ratio, zscore};
    }

    /**
     * Computes [hour_share, category_share, merchant_visit_count, avg_distance_home_km, distance_zscore].
     * All calculations mirror cross_domain_features.py exactly to prevent training-serving skew.
     */
    private double[] computeHabitStats(List<CustomerActivityEntity> history,
                                       double amount,
                                       int currentHour,
                                       String currentCategory,
                                       String merchantId,
                                       double currentDistKm,
                                       RiskEvaluationRequest req) {
        if (history.isEmpty()) {
            return new double[]{0.0, 0.0, 0.0, 0.0, 0.0};
        }
        int n = history.size();

        // hour_share: fraction of past purchases at the same hour
        long sameHour = history.stream().filter(h -> {
            int h_hour = h.getOccurredAt().atZone(ZoneOffset.UTC).getHour();
            return h_hour == currentHour;
        }).count();
        double hourShare = (double) sameHour / n;

        // category_share
        long sameCat = currentCategory == null ? 0 :
                history.stream().filter(h -> currentCategory.equals(h.getCategory())).count();
        double catShare = (double) sameCat / n;

        // merchant_visit_count: count of past purchases at the same merchant
        int merchantVisitCount = 0;
        if (merchantId != null) {
            merchantVisitCount = (int) history.stream()
                    .filter(h -> matchesMerchantId(h, merchantId))
                    .count();
        }

        // avg_distance_home_km and distance_zscore
        // Calculate distance from customer home (lat, lon) to each past purchase's merchant coordinates (h.lat, h.lon)
        double avgDist;
        double distZscore;
        double[] dists = history.stream()
                .mapToDouble(h -> {
                    if (req.getCustomerLatitude() != null && req.getCustomerLongitude() != null
                            && h.getLat() != null && h.getLon() != null) {
                        return haversineKm(
                                req.getCustomerLatitude(),
                                req.getCustomerLongitude(),
                                h.getLat().doubleValue(),
                                h.getLon().doubleValue()
                        );
                    }
                    return currentDistKm;
                })
                .toArray();

        avgDist = mean(dists);
        double stdDist = std(dists);
        distZscore = stdDist > 0.0 ? (currentDistKm - avgDist) / stdDist : 0.0;

        return new double[]{hourShare, catShare, merchantVisitCount, avgDist, distZscore};
    }

    /** Whether a history entry matches a given merchant ID. Currently always false since merchantId is not stored. */
    private boolean matchesMerchantId(CustomerActivityEntity h, String merchantId) {
        return merchantId != null && merchantId.equals(h.getCounterpartyId());
    }

    private double mean(double[] arr) {
        double sum = 0;
        for (double v : arr) sum += v;
        return sum / arr.length;
    }

    private double std(double[] arr) {
        if (arr.length < 2) return 0.0;
        double m = mean(arr);
        double variance = 0;
        for (double v : arr) variance += Math.pow(v - m, 2);
        return Math.sqrt(variance / arr.length);
    }

    private double max(double[] arr) {
        double m = arr[0];
        for (double v : arr) if (v > m) m = v;
        return m;
    }
}
