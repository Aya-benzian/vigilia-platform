package com.vigilia.risk.service;

import com.vigilia.risk.dto.RiskEvaluationRequest;
import com.vigilia.risk.entity.CustomerActivityEntity;
import com.vigilia.risk.repository.CustomerActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Computes the canonical 20 Transfer features required by ML Serving (transfer-v2).
 *
 * Feature definitions mirror cross_domain_features.py#TRANSFER_COLUMNS_20 exactly
 * to avoid training-serving skew. All rolling windows are strictly point-in-time.
 *
 * Cold-start defaults from feature_spec.json are applied when no history exists:
 *   - days_since_last_pair_transfer: 30.0
 *   - is_new_recipient: 1
 *   - all count/amount stats: 0
 *
 * Feature groups (20 total):
 *  1. Basics          (4): amount, log_amount, hour_of_day, day_of_week
 *  2. Sender habits   (5): sender_avg_amount, sender_std_amount, sender_max_amount,
 *                           amount_to_avg_ratio, sender_amount_zscore
 *  3. Recipient rel.  (5): is_new_recipient, pair_transfer_count, recipient_share,
 *                           days_since_last_pair_transfer, distinct_recipients_30d
 *  4. Mule pattern    (2): recipient_inbound_count_24h, recipient_unique_senders_24h
 *  5. Velocity        (2): sender_txn_count_1h, sender_txn_count_24h
 *  6. Balance         (2): amount_to_balance_ratio, history_length
 */
@Component
public class TransferFeatureCalculator {

    private static final Logger log = LoggerFactory.getLogger(TransferFeatureCalculator.class);

    /** 30 days — cold-start sentinel for days_since_last_pair_transfer. */
    private static final double SENTINEL_DAYS = 30.0;

    private final CustomerActivityRepository activityRepository;

    public TransferFeatureCalculator(CustomerActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    /**
     * Builds the full 20-feature map for a Transfer transaction.
     * All history queries use {@code req.getTimestamp()} as the exclusive upper bound.
     *
     * @param req the current risk evaluation request (sender perspective)
     * @return map of feature name → value, ready to send to ML Serving
     */
    public Map<String, Object> compute(RiskEvaluationRequest req) {
        UUID senderId    = req.getCustomerId();
        Instant txTime   = req.getTimestamp();
        double  amount   = req.getAmount() != null ? req.getAmount().doubleValue() : 0.0;
        String  recipientId = req.getRecipientId(); // may be null for cold-start

        ZonedDateTime zdt = txTime.atZone(ZoneOffset.UTC);
        int hourOfDay  = zdt.getHour();
        int dayOfWeek  = zdt.getDayOfWeek().getValue() - 1; // 0=Mon...6=Sun (matches Python dt.dayofweek)

        // Full outbound transfer history for the sender (point-in-time)
        List<CustomerActivityEntity> senderHistory =
                activityRepository.findTransferHistoryBefore(senderId, txTime);

        Map<String, Object> f = new HashMap<>();

        // 1. Basics (4)
        f.put("amount",         amount);
        f.put("log_amount",     Math.log1p(amount));
        f.put("hour_of_day",    hourOfDay);
        f.put("day_of_week",    dayOfWeek);

        // 2. Sender habits (5)
        double[] senderStats = computeSenderStats(senderHistory, amount);
        f.put("sender_avg_amount",      senderStats[0]);
        f.put("sender_std_amount",      senderStats[1]);
        f.put("sender_max_amount",      senderStats[2]);
        f.put("amount_to_avg_ratio",    senderStats[3]);
        f.put("sender_amount_zscore",   senderStats[4]);

        // 3. Recipient relationship (5)
        double[] recipientRelStats = computeRecipientRelStats(senderHistory, recipientId, txTime, amount);
        f.put("is_new_recipient",                (int) recipientRelStats[0]);
        f.put("pair_transfer_count",             (int) recipientRelStats[1]);
        f.put("recipient_share",                 recipientRelStats[2]);
        f.put("days_since_last_pair_transfer",   recipientRelStats[3]);
        f.put("distinct_recipients_30d",         (int) recipientRelStats[4]);

        // 4. Mule pattern — inbound traffic to recipient in last 24h (2)
        long recipientInbound24h      = 0;
        long recipientUniqueSenders24h = 0;
        if (recipientId != null) {
            Instant since24h = txTime.minusSeconds(86400);
            recipientInbound24h       = activityRepository.countInboundToRecipientInWindow(
                    recipientId, since24h, txTime);
            recipientUniqueSenders24h = activityRepository.countDistinctSendersToRecipientInWindow(
                    recipientId, since24h, txTime);
        }
        f.put("recipient_inbound_count_24h",    (int) recipientInbound24h);
        f.put("recipient_unique_senders_24h",   (int) recipientUniqueSenders24h);

        // 5. Sender velocity (2)
        long senderTxn1h  = activityRepository.countTransferSentInWindow(senderId, txTime.minusSeconds(3600), txTime);
        long senderTxn24h = activityRepository.countTransferSentInWindow(senderId, txTime.minusSeconds(86400), txTime);
        f.put("sender_txn_count_1h",    (int) senderTxn1h);
        f.put("sender_txn_count_24h",   (int) senderTxn24h);

        // 6. Balance & reliability (2)
        double senderBalance = req.getSenderBalance() != null ? req.getSenderBalance() : 0.0;
        double balanceRatio  = amount / Math.max(senderBalance, 1.0);
        f.put("amount_to_balance_ratio", balanceRatio);
        f.put("history_length",          senderHistory.size());

        log.debug("[FEAT-TRANSFER] senderId={} historyLen={} isNewRecipient={} balanceRatio={}",
                senderId, senderHistory.size(), (int) recipientRelStats[0], balanceRatio);
        return f;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Computes [avg, std, max, amount_to_avg_ratio, amount_zscore] for outbound transfers.
     * Returns cold-start zeros when no history exists.
     */
    private double[] computeSenderStats(List<CustomerActivityEntity> history, double amount) {
        if (history.isEmpty()) {
            return new double[]{0.0, 0.0, 0.0, amount / 1.0, 0.0};
        }
        double[] amounts = history.stream()
                .mapToDouble(h -> h.getAmount().doubleValue())
                .toArray();

        double avg   = mean(amounts);
        double std   = std(amounts);
        double max   = max(amounts);
        double ratio = amount / Math.max(avg, 1.0);
        double z     = std > 0.0 ? (amount - avg) / std : 0.0;
        return new double[]{avg, std, max, ratio, z};
    }

    /**
     * Computes [is_new_recipient, pair_transfer_count, recipient_share,
     *           days_since_last_pair_transfer, distinct_recipients_30d].
     */
    private double[] computeRecipientRelStats(List<CustomerActivityEntity> senderHistory,
                                              String recipientId,
                                              Instant txTime,
                                              double amount) {
        if (senderHistory.isEmpty() || recipientId == null) {
            return new double[]{1.0, 0.0, 0.0, SENTINEL_DAYS, 0.0};
        }

        // Transfers to this specific recipient
        List<CustomerActivityEntity> pairTxns = senderHistory.stream()
                .filter(h -> recipientId.equals(h.getCounterpartyId()))
                .toList();

        int isNewRecipient  = pairTxns.isEmpty() ? 1 : 0;
        int pairCount       = pairTxns.size();
        double recipientShare = senderHistory.isEmpty() ? 0.0 : (double) pairCount / senderHistory.size();

        double daysSinceLast = SENTINEL_DAYS;
        if (!pairTxns.isEmpty()) {
            Instant lastPair = pairTxns.get(pairTxns.size() - 1).getOccurredAt();
            daysSinceLast = (double) (txTime.getEpochSecond() - lastPair.getEpochSecond()) / 86400.0;
        }

        // distinct_recipients_30d
        Instant since30d = txTime.minusSeconds(30L * 86400);
        Set<String> distinctRecipients = new HashSet<>();
        for (CustomerActivityEntity h : senderHistory) {
            if (!h.getOccurredAt().isBefore(since30d) && h.getCounterpartyId() != null) {
                distinctRecipients.add(h.getCounterpartyId());
            }
        }
        int distinctRecip30d = distinctRecipients.size();

        return new double[]{isNewRecipient, pairCount, recipientShare, daysSinceLast, distinctRecip30d};
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
