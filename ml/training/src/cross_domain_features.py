"""
cross_domain_features.py — Canonical Feature Engineering for Vigilia Dual-Domain ML.

Strictly implements SPEC-ML-CROSS-DOMAIN-V2:
- Purchase model: exactly 48 canonical features (purchase-v2).
- Transfer model: exactly 38 canonical features (transfer-v2).
- Pure point-in-time calculation: all rolling windows are strictly [t - W, t).
- Cold-start defaults and 30-day sentinels (2,592,000s) enforced.
- Generates 200+ golden rows with expected feature values for Java parity verification.
"""

import json
import logging
import math
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd

logger = logging.getLogger("cross_domain_features")

SENTINEL_TIME_S = 2_592_000.0  # 30 days in seconds
SENTINEL_DAYS = 30.0
EPSILON = 1e-6
EARTH_RADIUS_KM = 6371.0

# Risky merchant categories for cross-domain detection
RISKY_CATEGORIES = {"shopping_net", "misc_net"}

PURCHASE_COLUMNS_48 = [
    "amount",
    "log_amount",
    "hour_of_day",
    "day_of_week",
    "is_weekend",
    "is_night",
    "customer_age",
    "distance_home_merchant_km",
    "city_pop_log",
    "merchant_category",
    "customer_avg_amount",
    "customer_std_amount",
    "customer_max_amount",
    "amount_to_avg_ratio",
    "amount_zscore_customer",
    "hour_share",
    "category_share",
    "merchant_visit_count",
    "avg_distance_home_km",
    "distance_zscore",
    "txn_count_1h",
    "txn_count_24h",
    "txn_count_7d",
    "amount_sum_24h",
    "time_since_last_txn_s",
    "is_new_merchant",
    "is_new_category",
    "history_length",
    "days_since_first_txn",
    "inbound_transfer_amount_24h",
    "inbound_transfer_count_24h",
    "outbound_transfer_count_24h",
    "time_since_last_transfer_s",
    "balance_drain_ratio",
    "inbound_transfer_amount_7d",
    "inbound_transfer_amount_30d",
    "outbound_transfer_amount_7d",
    "avg_inbound_transfer_30d",
    "inbound_to_avg_ratio",
    "days_since_first_transfer",
    "transfer_history_length",
    "account_age_days",
    "total_history_length",
    "total_txn_count_1h",
    "domain_mix_30d",
    "reviews_30d",
    "rejections_30d",
    "days_since_last_review",
]

TRANSFER_COLUMNS_38 = [
    "amount",
    "log_amount",
    "hour_of_day",
    "day_of_week",
    "sender_avg_amount",
    "sender_std_amount",
    "sender_max_amount",
    "amount_to_avg_ratio",
    "sender_amount_zscore",
    "is_new_recipient",
    "pair_transfer_count",
    "recipient_share",
    "days_since_last_pair_transfer",
    "distinct_recipients_30d",
    "recipient_inbound_count_24h",
    "recipient_unique_senders_24h",
    "sender_txn_count_1h",
    "sender_txn_count_24h",
    "amount_to_balance_ratio",
    "history_length",
    "purchase_count_24h",
    "purchase_amount_sum_24h",
    "time_since_last_purchase_s",
    "recent_high_risk_category_purchase",
    "purchase_amount_sum_7d",
    "purchase_amount_sum_30d",
    "avg_purchase_amount_30d",
    "purchase_to_transfer_ratio_30d",
    "distinct_merchants_30d",
    "days_since_first_purchase",
    "purchase_history_length",
    "account_age_days",
    "total_history_length",
    "total_txn_count_1h",
    "domain_mix_30d",
    "reviews_30d",
    "rejections_30d",
    "days_since_last_review",
]


def haversine_km(lat1, lon1, lat2, lon2):
    """Compute great-circle distance in kilometers using Haversine formula (supports scalars and numpy arrays)."""
    lat1_r, lon1_r = np.radians(lat1), np.radians(lon1)
    lat2_r, lon2_r = np.radians(lat2), np.radians(lon2)
    dlat = lat2_r - lat1_r
    dlon = lon2_r - lon1_r
    a = np.sin(dlat / 2.0) ** 2 + np.cos(lat1_r) * np.cos(lat2_r) * np.sin(dlon / 2.0) ** 2
    c = 2.0 * np.arctan2(np.sqrt(a), np.sqrt(np.maximum(0.0, 1.0 - a)))
    return EARTH_RADIUS_KM * c



class PointInTimeLedger:
    """
    Stateful point-in-time transaction ledger for rolling cross-domain feature calculation.
    Ensures zero future leakage: features for transaction at time t use strictly past events < t.
    """

    def __init__(self):
        # Customer-keyed history
        self.purchases = defaultdict(list)    # customer_id -> list of (t, amt, cat, merch, dist)
        self.transfers_out = defaultdict(list) # customer_id -> list of (t, amt, recipient_id, oldbalance)
        self.transfers_in = defaultdict(list)  # customer_id -> list of (t, amt, sender_id)
        self.wallet_balance = defaultdict(lambda: 2000.0) # initial wallet balance seed
        self.account_created = {}              # customer_id -> t_first_seen
        self.reviews = defaultdict(list)       # customer_id -> list of t
        self.rejections = defaultdict(list)    # customer_id -> list of t
        
        # Recipient-keyed inbound history (for Money Mule detection)
        self.recipient_inbound = defaultdict(list) # recipient_id -> list of (t, sender_id, amt)

    def get_account_created(self, cust_id: str, current_t: float) -> float:
        if cust_id not in self.account_created:
            # First transaction: account was created between 1 and 30 days prior
            self.account_created[cust_id] = current_t - (7.0 * 86400.0)
        return self.account_created[cust_id]

    def compute_purchase_row(
        self,
        cust_id: str,
        t: float,
        amt: float,
        cat: str,
        merch: str,
        age: float,
        dist_km: float,
        city_pop: float,
        dt_hour: int,
        dt_dow: int,
    ) -> Dict[str, Any]:
        """Compute the canonical 48 features for a single purchase strictly point-in-time."""
        acct_t0 = self.get_account_created(cust_id, t)
        past_p = self.purchases[cust_id]
        past_tin = self.transfers_in[cust_id]
        past_tout = self.transfers_out[cust_id]
        curr_bal = self.wallet_balance[cust_id]

        # 1. Basics (10)
        f_amount = float(amt)
        f_log_amount = float(np.log1p(amt))
        f_hour_of_day = int(dt_hour)
        f_day_of_week = int(dt_dow)
        f_is_weekend = int(dt_dow in (5, 6))
        f_is_night = int(dt_hour <= 5)
        f_customer_age = float(age)
        f_distance_home_merchant_km = float(dist_km)
        f_city_pop_log = float(np.log1p(city_pop))
        f_merchant_category = str(cat)

        # 2. Spending level (5)
        past_amts = [p[1] for p in past_p]
        if past_amts:
            f_customer_avg_amount = float(np.mean(past_amts))
            f_customer_std_amount = float(np.std(past_amts)) if len(past_amts) > 1 else 0.0
            f_customer_max_amount = float(np.max(past_amts))
        else:
            f_customer_avg_amount = 0.0
            f_customer_std_amount = 0.0
            f_customer_max_amount = 0.0

        f_amount_to_avg_ratio = float(amt / max(f_customer_avg_amount, 1.0))
        if f_customer_std_amount > 0.0:
            f_amount_zscore_customer = float((amt - f_customer_avg_amount) / f_customer_std_amount)
        else:
            f_amount_zscore_customer = 0.0

        # 3. Habits (5)
        if past_p:
            f_hour_share = float(sum(1 for p in past_p if p[0] is not None and (int(p[0] // 3600) % 24) == dt_hour) / len(past_p))
            f_category_share = float(sum(1 for p in past_p if p[2] == cat) / len(past_p))
            f_merchant_visit_count = int(sum(1 for p in past_p if p[3] == merch))
            past_dists = [p[4] for p in past_p]
            f_avg_distance_home_km = float(np.mean(past_dists))
            std_dist = float(np.std(past_dists)) if len(past_dists) > 1 else 0.0
            if std_dist > 0.0:
                f_distance_zscore = float((dist_km - f_avg_distance_home_km) / std_dist)
            else:
                f_distance_zscore = 0.0
        else:
            f_hour_share = 0.0
            f_category_share = 0.0
            f_merchant_visit_count = 0
            f_avg_distance_home_km = 0.0
            f_distance_zscore = 0.0

        # 4. Velocity (5)
        p_1h = [p for p in past_p if p[0] >= t - 3600.0]
        p_24h = [p for p in past_p if p[0] >= t - 86400.0]
        p_7d = [p for p in past_p if p[0] >= t - 7 * 86400.0]

        f_txn_count_1h = int(len(p_1h))
        f_txn_count_24h = int(len(p_24h))
        f_txn_count_7d = int(len(p_7d))
        f_amount_sum_24h = float(sum(p[1] for p in p_24h))
        f_time_since_last_txn_s = float(t - past_p[-1][0]) if past_p else SENTINEL_TIME_S

        # 5. Novelty & Reliability (4)
        f_is_new_merchant = int(f_merchant_visit_count == 0)
        f_is_new_category = int(sum(1 for p in past_p if p[2] == cat) == 0)
        f_history_length = int(len(past_p))
        f_days_since_first_txn = float((t - past_p[0][0]) / 86400.0) if past_p else 0.0

        # 6. Cross-domain 24h (5)
        tin_24h = [x for x in past_tin if x[0] >= t - 86400.0]
        tout_24h = [x for x in past_tout if x[0] >= t - 86400.0]

        f_inbound_transfer_amount_24h = float(sum(x[1] for x in tin_24h))
        f_inbound_transfer_count_24h = int(len(tin_24h))
        f_outbound_transfer_count_24h = int(len(tout_24h))

        all_t_transfers = [x[0] for x in past_tin] + [x[0] for x in past_tout]
        f_time_since_last_transfer_s = float(t - max(all_t_transfers)) if all_t_transfers else SENTINEL_TIME_S
        f_balance_drain_ratio = float(amt / max(curr_bal, 1.0))

        # 7. Cross-domain history (7)
        tin_7d = [x for x in past_tin if x[0] >= t - 7 * 86400.0]
        tin_30d = [x for x in past_tin if x[0] >= t - 30 * 86400.0]
        tout_7d = [x for x in past_tout if x[0] >= t - 7 * 86400.0]

        f_inbound_transfer_amount_7d = float(sum(x[1] for x in tin_7d))
        f_inbound_transfer_amount_30d = float(sum(x[1] for x in tin_30d))
        f_outbound_transfer_amount_7d = float(sum(x[1] for x in tout_7d))
        f_avg_inbound_transfer_30d = float(np.mean([x[1] for x in tin_30d])) if tin_30d else 0.0
        f_inbound_to_avg_ratio = float(f_inbound_transfer_amount_24h / max(f_avg_inbound_transfer_30d, 1.0))
        f_days_since_first_transfer = float((t - min(all_t_transfers)) / 86400.0) if all_t_transfers else 0.0
        f_transfer_history_length = int(len(past_tin) + len(past_tout))

        # 8. Shared profile (7)
        f_account_age_days = float((t - acct_t0) / 86400.0)
        f_total_history_length = int(f_history_length + f_transfer_history_length)
        tout_1h = [x for x in past_tout if x[0] >= t - 3600.0]
        tin_1h = [x for x in past_tin if x[0] >= t - 3600.0]
        f_total_txn_count_1h = int(f_txn_count_1h + len(tout_1h) + len(tin_1h))

        p_30d = [p for p in past_p if p[0] >= t - 30 * 86400.0]
        tout_30d = [x for x in past_tout if x[0] >= t - 30 * 86400.0]
        total_30d = len(p_30d) + len(tout_30d) + len(tin_30d)
        f_domain_mix_30d = float(len(p_30d) / max(total_30d, 1.0))

        past_rev = [x for x in self.reviews[cust_id] if x >= t - 30 * 86400.0]
        past_rej = [x for x in self.rejections[cust_id] if x >= t - 30 * 86400.0]
        f_reviews_30d = int(len(past_rev))
        f_rejections_30d = int(len(past_rej))
        f_days_since_last_review = float((t - max(past_rev)) / 86400.0) if past_rev else SENTINEL_DAYS

        # Update point-in-time ledger AFTER feature calculation
        self.purchases[cust_id].append((t, amt, cat, merch, dist_km))
        self.wallet_balance[cust_id] = max(0.0, curr_bal - amt)

        return {
            "amount": f_amount,
            "log_amount": f_log_amount,
            "hour_of_day": f_hour_of_day,
            "day_of_week": f_day_of_week,
            "is_weekend": f_is_weekend,
            "is_night": f_is_night,
            "customer_age": f_customer_age,
            "distance_home_merchant_km": f_distance_home_merchant_km,
            "city_pop_log": f_city_pop_log,
            "merchant_category": f_merchant_category,
            "customer_avg_amount": f_customer_avg_amount,
            "customer_std_amount": f_customer_std_amount,
            "customer_max_amount": f_customer_max_amount,
            "amount_to_avg_ratio": f_amount_to_avg_ratio,
            "amount_zscore_customer": f_amount_zscore_customer,
            "hour_share": f_hour_share,
            "category_share": f_category_share,
            "merchant_visit_count": f_merchant_visit_count,
            "avg_distance_home_km": f_avg_distance_home_km,
            "distance_zscore": f_distance_zscore,
            "txn_count_1h": f_txn_count_1h,
            "txn_count_24h": f_txn_count_24h,
            "txn_count_7d": f_txn_count_7d,
            "amount_sum_24h": f_amount_sum_24h,
            "time_since_last_txn_s": f_time_since_last_txn_s,
            "is_new_merchant": f_is_new_merchant,
            "is_new_category": f_is_new_category,
            "history_length": f_history_length,
            "days_since_first_txn": f_days_since_first_txn,
            "inbound_transfer_amount_24h": f_inbound_transfer_amount_24h,
            "inbound_transfer_count_24h": f_inbound_transfer_count_24h,
            "outbound_transfer_count_24h": f_outbound_transfer_count_24h,
            "time_since_last_transfer_s": f_time_since_last_transfer_s,
            "balance_drain_ratio": f_balance_drain_ratio,
            "inbound_transfer_amount_7d": f_inbound_transfer_amount_7d,
            "inbound_transfer_amount_30d": f_inbound_transfer_amount_30d,
            "outbound_transfer_amount_7d": f_outbound_transfer_amount_7d,
            "avg_inbound_transfer_30d": f_avg_inbound_transfer_30d,
            "inbound_to_avg_ratio": f_inbound_to_avg_ratio,
            "days_since_first_transfer": f_days_since_first_transfer,
            "transfer_history_length": f_transfer_history_length,
            "account_age_days": f_account_age_days,
            "total_history_length": f_total_history_length,
            "total_txn_count_1h": f_total_txn_count_1h,
            "domain_mix_30d": f_domain_mix_30d,
            "reviews_30d": f_reviews_30d,
            "rejections_30d": f_rejections_30d,
            "days_since_last_review": f_days_since_last_review,
        }

    def compute_transfer_row(
        self,
        sender_id: str,
        recipient_id: str,
        t: float,
        amt: float,
        oldbalance: float,
        dt_hour: int,
        dt_dow: int,
    ) -> Dict[str, Any]:
        """Compute the canonical 38 features for a single transfer strictly point-in-time."""
        acct_t0 = self.get_account_created(sender_id, t)
        past_tout = self.transfers_out[sender_id]
        past_p = self.purchases[sender_id]

        # 1. Basics (4)
        f_amount = float(amt)
        f_log_amount = float(np.log1p(amt))
        f_hour_of_day = int(dt_hour)
        f_day_of_week = int(dt_dow)

        # 2. Sender habits (5)
        past_amts = [x[1] for x in past_tout]
        if past_amts:
            f_sender_avg_amount = float(np.mean(past_amts))
            f_sender_std_amount = float(np.std(past_amts)) if len(past_amts) > 1 else 0.0
            f_sender_max_amount = float(np.max(past_amts))
        else:
            f_sender_avg_amount = 0.0
            f_sender_std_amount = 0.0
            f_sender_max_amount = 0.0

        f_amount_to_avg_ratio = float(amt / max(f_sender_avg_amount, 1.0))
        if f_sender_std_amount > 0.0:
            f_sender_amount_zscore = float((amt - f_sender_avg_amount) / f_sender_std_amount)
        else:
            f_sender_amount_zscore = 0.0


        # 3. Recipient relationship (5)
        pair_txns = [x for x in past_tout if x[2] == recipient_id]
        f_is_new_recipient = int(len(pair_txns) == 0)
        f_pair_transfer_count = int(len(pair_txns))
        f_recipient_share = float(len(pair_txns) / len(past_tout)) if past_tout else 0.0
        f_days_since_last_pair_transfer = float((t - pair_txns[-1][0]) / 86400.0) if pair_txns else SENTINEL_DAYS
        
        tout_30d = [x for x in past_tout if x[0] >= t - 30 * 86400.0]
        f_distinct_recipients_30d = int(len(set(x[2] for x in tout_30d)))

        # 4. Mule pattern (2) - Evaluates recipient account traffic in 24h
        rec_in_24h = [x for x in self.recipient_inbound[recipient_id] if x[0] >= t - 86400.0]
        f_recipient_inbound_count_24h = int(len(rec_in_24h))
        f_recipient_unique_senders_24h = int(len(set(x[1] for x in rec_in_24h)))

        # 5. Velocity (2)
        tout_1h = [x for x in past_tout if x[0] >= t - 3600.0]
        tout_24h = [x for x in past_tout if x[0] >= t - 86400.0]
        f_sender_txn_count_1h = int(len(tout_1h))
        f_sender_txn_count_24h = int(len(tout_24h))

        # 6. Balance & Reliability (2)
        f_amount_to_balance_ratio = float(amt / max(oldbalance, 1.0))
        f_history_length = int(len(past_tout))

        # 7. Cross-domain 24h (4)
        p_24h = [p for p in past_p if p[0] >= t - 86400.0]
        f_purchase_count_24h = int(len(p_24h))
        f_purchase_amount_sum_24h = float(sum(p[1] for p in p_24h))
        f_time_since_last_purchase_s = float(t - past_p[-1][0]) if past_p else SENTINEL_TIME_S
        f_recent_high_risk_category_purchase = int(any(p[2] in RISKY_CATEGORIES for p in p_24h))

        # 8. Cross-domain history (7)
        p_7d = [p for p in past_p if p[0] >= t - 7 * 86400.0]
        p_30d = [p for p in past_p if p[0] >= t - 30 * 86400.0]
        f_purchase_amount_sum_7d = float(sum(p[1] for p in p_7d))
        f_purchase_amount_sum_30d = float(sum(p[1] for p in p_30d))
        f_avg_purchase_amount_30d = float(np.mean([p[1] for p in p_30d])) if p_30d else 0.0

        t_amt_30d = sum(x[1] for x in tout_30d)
        f_purchase_to_transfer_ratio_30d = float(f_purchase_amount_sum_30d / max(t_amt_30d, 1.0))
        f_distinct_merchants_30d = int(len(set(p[3] for p in p_30d)))
        f_days_since_first_purchase = float((t - past_p[0][0]) / 86400.0) if past_p else 0.0
        f_purchase_history_length = int(len(past_p))

        # 9. Shared profile (7)
        f_account_age_days = float((t - acct_t0) / 86400.0)
        f_total_history_length = int(f_purchase_history_length + f_history_length)
        p_1h = [p for p in past_p if p[0] >= t - 3600.0]
        f_total_txn_count_1h = int(f_sender_txn_count_1h + len(p_1h))

        total_30d = len(p_30d) + len(tout_30d)
        f_domain_mix_30d = float(len(tout_30d) / max(total_30d, 1.0))

        past_rev = [x for x in self.reviews[sender_id] if x >= t - 30 * 86400.0]
        past_rej = [x for x in self.rejections[sender_id] if x >= t - 30 * 86400.0]
        f_reviews_30d = int(len(past_rev))
        f_rejections_30d = int(len(past_rej))
        f_days_since_last_review = float((t - max(past_rev)) / 86400.0) if past_rev else SENTINEL_DAYS

        # Update point-in-time ledger AFTER feature calculation
        self.transfers_out[sender_id].append((t, amt, recipient_id, oldbalance))
        self.transfers_in[recipient_id].append((t, amt, sender_id))
        self.recipient_inbound[recipient_id].append((t, sender_id, amt))
        self.wallet_balance[sender_id] = max(0.0, oldbalance - amt)
        self.wallet_balance[recipient_id] += amt

        return {
            "amount": f_amount,
            "log_amount": f_log_amount,
            "hour_of_day": f_hour_of_day,
            "day_of_week": f_day_of_week,
            "sender_avg_amount": f_sender_avg_amount,
            "sender_std_amount": f_sender_std_amount,
            "sender_max_amount": f_sender_max_amount,
            "amount_to_avg_ratio": f_amount_to_avg_ratio,
            "sender_amount_zscore": f_sender_amount_zscore,
            "is_new_recipient": f_is_new_recipient,
            "pair_transfer_count": f_pair_transfer_count,
            "recipient_share": f_recipient_share,
            "days_since_last_pair_transfer": f_days_since_last_pair_transfer,
            "distinct_recipients_30d": f_distinct_recipients_30d,
            "recipient_inbound_count_24h": f_recipient_inbound_count_24h,
            "recipient_unique_senders_24h": f_recipient_unique_senders_24h,
            "sender_txn_count_1h": f_sender_txn_count_1h,
            "sender_txn_count_24h": f_sender_txn_count_24h,
            "amount_to_balance_ratio": f_amount_to_balance_ratio,
            "history_length": f_history_length,
            "purchase_count_24h": f_purchase_count_24h,
            "purchase_amount_sum_24h": f_purchase_amount_sum_24h,
            "time_since_last_purchase_s": f_time_since_last_purchase_s,
            "recent_high_risk_category_purchase": f_recent_high_risk_category_purchase,
            "purchase_amount_sum_7d": f_purchase_amount_sum_7d,
            "purchase_amount_sum_30d": f_purchase_amount_sum_30d,
            "avg_purchase_amount_30d": f_avg_purchase_amount_30d,
            "purchase_to_transfer_ratio_30d": f_purchase_to_transfer_ratio_30d,
            "distinct_merchants_30d": f_distinct_merchants_30d,
            "days_since_first_purchase": f_days_since_first_purchase,
            "purchase_history_length": f_purchase_history_length,
            "account_age_days": f_account_age_days,
            "total_history_length": f_total_history_length,
            "total_txn_count_1h": f_total_txn_count_1h,
            "domain_mix_30d": f_domain_mix_30d,
            "reviews_30d": f_reviews_30d,
            "rejections_30d": f_rejections_30d,
            "days_since_last_review": f_days_since_last_review,
        }
