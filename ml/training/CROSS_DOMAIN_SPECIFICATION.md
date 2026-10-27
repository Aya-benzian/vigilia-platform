# Vigilia Cross-Domain Model I/O Specification & Feature Dictionary

| **Attribute** | **Specification** |
|---|---|
| **Document ID** | SPEC-ML-CROSS-DOMAIN-V2 |
| **Purchase Spec Version** | `purchase-v2` (48 Features) |
| **Transfer Spec Version** | `transfer-v2` (38 Features) |
| **Status** | **LOCKED & CANONICAL** (Binding on Python Training, FastAPI Serving & Spring Boot Risk Service) |
| **Enforcement** | FastAPI rejects any request with missing, extra, or out-of-range features with HTTP 422 (`FEATURE_VALIDATION_ERROR`). Version mismatch yields HTTP 409 (`VERSION_MISMATCH`). |

---

## 1. Request Envelope (Non-Feature Protocol)

Every prediction request sent from the Spring Boot `Risk Management Service` to the FastAPI Model Serving must conform to this schema:

```json
{
  "request_id": "e2c4a8f0-1111-4a6b-8f2e-aaaaaaaaaaaa",
  "feature_spec_version": "purchase-v2",
  "features": {
    "...": "exactly the 48 features for purchase, or 38 for transfer"
  }
}
```

* `request_id` (UUID): Correlation identifier for tracing and immutable audit logging.
* `feature_spec_version` (string): Must match loaded model alias (`purchase-v2` or `transfer-v2`).
* `features` (object): Strict key-value dictionary. No extra fields allowed (`extra="forbid"` in Pydantic v2).

---

## 2. Cold-Start & Sentinel Standards

To guarantee mathematical parity between Java feature computation and Python training:

| Feature Type | Cold-Start (No History) Value | Mathematical Treatment |
|---|:---:|---|
| **Counts & Frequencies** | `0` or `0.0` | Exact integer zero or float 0.0. |
| **Monetary Sums** | `0.0` | Default zero dollars. |
| **Averages / Means** | `0.0` | Default zero baseline. |
| **Standard Deviations** | `0.0` | Default zero variance. |
| **Z-Scores** | `0.0` | Defined as $\frac{x - \mu}{\sigma + 10^{-6}}$; returns $0.0$ when $\sigma = 0$. |
| **Novelty Flags (`is_new_*`)** | `1` | First encounter is strictly novel ($1$). |
| **Ratios & Proportions** | `0.0` | Defined as $\frac{A}{\max(B, 1.0)}$ or conditional $0.0$ when $B = 0$. |
| **Elapsed Days (`days_since_*`)** | `0.0` | Zero days elapsed. |
| **Elapsed Seconds (`time_since_last_*_s`)** | `2,592,000.0` | **30-Day Sentinel in Seconds** ($30 \times 86,400\text{ s}$). Prevents infinity and distinguishes fresh velocity from long inactivity. |

---

## 3. Purchase Model Canonical Feature Dictionary (48 Features — `purchase-v2`)

| # | Feature Name | Type | Allowed Range | Cold-Start | Exact Formula / Source |
|---|---|:---:|:---:|:---:|---|
| 1 | `amount` | float | $(0, \infty)$ | None (Required) | Transaction amount in USD |
| 2 | `log_amount` | float | $[0, \infty)$ | None | $\log(1 + \text{amount})$ |
| 3 | `hour_of_day` | int | $[0, 23]$ | None | Hour extracted from transaction timestamp |
| 4 | `day_of_week` | int | $[0, 6]$ | None | Day of week ($0 = \text{Monday}, 6 = \text{Sunday}$) |
| 5 | `is_weekend` | int | $\{0, 1\}$ | None | $1$ if `day_of_week` $\in \{5, 6\}$, else $0$ |
| 6 | `is_night` | int | $\{0, 1\}$ | None | $1$ if `hour_of_day` $\in [0, 5]$, else $0$ |
| 7 | `customer_age` | float | $[18, 120]$ | None | $(\text{timestamp} - \text{dob}) / 365.25$ |
| 8 | `distance_home_merchant_km` | float | $[0, 20000]$ | None | Great-circle Haversine between customer home and merchant GPS |
| 9 | `city_pop_log` | float | $[0, 20]$ | None | $\log(1 + \text{city\_pop})$ of cardholder home city |
| 10 | `merchant_category` | string | Categorical | None | Merchant business category (one-hot encoded) |
| 11 | `customer_avg_amount` | float | $[0, \infty)$ | `0.0` | Historical mean of prior purchase amounts |
| 12 | `customer_std_amount` | float | $[0, \infty)$ | `0.0` | Standard deviation of prior purchase amounts |
| 13 | `customer_max_amount` | float | $[0, \infty)$ | `0.0` | Maximum single prior purchase amount |
| 14 | `amount_to_avg_ratio` | float | $[0, \infty)$ | `0.0` | $\text{amount} / \max(\text{customer\_avg\_amount}, 1.0)$ |
| 15 | `amount_zscore_customer` | float | $(-\infty, \infty)$ | `0.0` | $(\text{amount} - \text{customer\_avg\_amount}) / (\text{customer\_std\_amount} + 10^{-6})$ |
| 16 | `hour_share` | float | $[0, 1.0]$ | `0.0` | Proportion of past purchases made in this exact hour of day |
| 17 | `category_share` | float | $[0, 1.0]$ | `0.0` | Proportion of past purchases made in this merchant category |
| 18 | `merchant_visit_count` | int | $[0, \infty)$ | `0` | Cumulative times customer previously bought at this merchant |
| 19 | `avg_distance_home_km` | float | $[0, 20000]$ | `0.0` | Historical average Haversine distance of past purchases |
| 20 | `distance_zscore` | float | $(-\infty, \infty)$ | `0.0` | $(\text{distance} - \text{avg\_distance}) / (\text{std\_distance} + 10^{-6})$ |
| 21 | `txn_count_1h` | int | $[0, \infty)$ | `0` | Number of purchases completed in the rolling 1 hour prior to $t$ |
| 22 | `txn_count_24h` | int | $[0, \infty)$ | `0` | Number of purchases completed in the rolling 24 hours prior to $t$ |
| 23 | `txn_count_7d` | int | $[0, \infty)$ | `0` | Number of purchases completed in the rolling 7 days prior to $t$ |
| 24 | `amount_sum_24h` | float | $[0, \infty)$ | `0.0` | Total monetary purchase spend in the rolling 24 hours prior to $t$ |
| 25 | `time_since_last_txn_s` | float | $[0, 2592000]$ | `2592000.0` | Seconds elapsed since previous purchase; capped at 30-day sentinel |
| 26 | `is_new_merchant` | int | $\{0, 1\}$ | `1` | $1$ if `merchant_visit_count` $= 0$, else $0$ |
| 27 | `is_new_category` | int | $\{0, 1\}$ | `1` | $1$ if customer has zero prior transactions in this category, else $0$ |
| 28 | `history_length` | int | $[0, \infty)$ | `0` | Total cumulative count of past purchases made by customer |
| 29 | `days_since_first_txn` | float | $[0, \infty)$ | `0.0` | Days elapsed since customer's very first recorded purchase |
| 30 | `inbound_transfer_amount_24h` | float | $[0, \infty)$ | `0.0` | Total money received via transfer in rolling 24 hours prior to $t$ |
| 31 | `inbound_transfer_count_24h` | int | $[0, \infty)$ | `0` | Number of inbound transfers received in rolling 24 hours prior to $t$ |
| 32 | `outbound_transfer_count_24h`| int | $[0, \infty)$ | `0` | Number of outbound transfers sent in rolling 24 hours prior to $t$ |
| 33 | `time_since_last_transfer_s` | float | $[0, 2592000]$ | `2592000.0` | Seconds since most recent transfer (in or out); sentinel $2,592,000$ |
| 34 | `balance_drain_ratio` | float | $[0, \infty)$ | `0.0` | $\text{amount} / \max(\text{available\_wallet\_balance}, 1.0)$ strictly prior to $t$ |
| 35 | `inbound_transfer_amount_7d` | float | $[0, \infty)$ | `0.0` | Total inbound transfer money received in rolling 7 days prior to $t$ |
| 36 | `inbound_transfer_amount_30d`| float | $[0, \infty)$ | `0.0` | Total inbound transfer money received in rolling 30 days prior to $t$ |
| 37 | `outbound_transfer_amount_7d`| float | $[0, \infty)$ | `0.0` | Total outbound transfer money sent in rolling 7 days prior to $t$ |
| 38 | `avg_inbound_transfer_30d` | float | $[0, \infty)$ | `0.0` | Average amount per inbound transfer received over the last 30 days |
| 39 | `inbound_to_avg_ratio` | float | $[0, \infty)$ | `0.0` | $\text{inbound\_transfer\_amount\_24h} / \max(\text{avg\_inbound\_transfer\_30d}, 1.0)$ |
| 40 | `days_since_first_transfer` | float | $[0, \infty)$ | `0.0` | Days elapsed since customer's first observed wire transfer |
| 41 | `transfer_history_length` | int | $[0, \infty)$ | `0` | Total historical transfers (inbound + outbound) in wallet ledger |
| 42 | `account_age_days` | float | $[0, \infty)$ | `0.0` | Days elapsed since user registered account on Vigilia |
| 43 | `total_history_length` | int | $[0, \infty)$ | `0` | Cumulative transactions across all domains (`history_length` + `transfer_history_length`) |
| 44 | `total_txn_count_1h` | int | $[0, \infty)$ | `0` | Total multi-domain transactions completed in last hour |
| 45 | `domain_mix_30d` | float | $[0, 1.0]$ | `0.0` | $\text{purchases\_30d} / \max(\text{total\_txns\_30d}, 1.0)$ |
| 46 | `reviews_30d` | int | $[0, \infty)$ | `0` | Historical transactions referred to human review in last 30d strictly prior to $t$ |
| 47 | `rejections_30d` | int | $[0, \infty)$ | `0` | Historical transactions rejected in last 30d strictly prior to $t$ |
| 48 | `days_since_last_review` | float | $[0, \infty)$ | `30.0` | Days since customer was last flagged for review; sentinel $30.0$ |

---

## 4. Transfer Model Canonical Feature Dictionary (38 Features — `transfer-v2`)

| # | Feature Name | Type | Allowed Range | Cold-Start | Exact Formula / Source |
|---|---|:---:|:---:|:---:|---|
| 1 | `amount` | float | $(0, \infty)$ | None (Required) | Transfer amount in local currency |
| 2 | `log_amount` | float | $[0, \infty)$ | None | $\log(1 + \text{amount})$ |
| 3 | `hour_of_day` | int | $[0, 23]$ | None | Hour extracted from simulation clock |
| 4 | `day_of_week` | int | $[0, 6]$ | None | Day of week ($0 = \text{Monday}, 6 = \text{Sunday}$) |
| 5 | `sender_avg_amount` | float | $[0, \infty)$ | `0.0` | Historical mean of prior transfers sent by sender |
| 6 | `sender_std_amount` | float | $[0, \infty)$ | `0.0` | Standard deviation of prior transfers sent by sender |
| 7 | `sender_max_amount` | float | $[0, \infty)$ | `0.0` | Maximum single prior transfer sent by sender |
| 8 | `amount_to_avg_ratio` | float | $[0, \infty)$ | `0.0` | $\text{amount} / \max(\text{sender\_avg\_amount}, 1.0)$ |
| 9 | `sender_amount_zscore` | float | $(-\infty, \infty)$ | `0.0` | $(\text{amount} - \text{sender\_avg\_amount}) / (\text{sender\_std\_amount} + 10^{-6})$ |
| 10 | `is_new_recipient` | int | $\{0, 1\}$ | `1` | $1$ if sender has never sent to this recipient ID before, else $0$ |
| 11 | `pair_transfer_count` | int | $[0, \infty)$ | `0` | Cumulative transfers previously sent from this sender to this recipient |
| 12 | `recipient_share` | float | $[0, 1.0]$ | `0.0` | Fraction of sender's past transfers directed to this specific recipient |
| 13 | `days_since_last_pair_transfer` | float | $[0, \infty)$ | `30.0` | Days since sender last sent money to this recipient; sentinel $30.0$ |
| 14 | `distinct_recipients_30d` | int | $[0, \infty)$ | `0` | Number of unique recipient IDs paid by sender in the last 30 days |
| 15 | `recipient_inbound_count_24h`| int | $[0, \infty)$ | `0` | Total inbound transfers received by recipient account in last 24h |
| 16 | `recipient_unique_senders_24h`| int | $[0, \infty)$ | `0` | **Money Mule Indicator**: Distinct senders paying this recipient in last 24h |
| 17 | `sender_txn_count_1h` | int | $[0, \infty)$ | `0` | Transfers sent by sender in the rolling 1 hour prior to $t$ |
| 18 | `sender_txn_count_24h` | int | $[0, \infty)$ | `0` | Transfers sent by sender in the rolling 24 hours prior to $t$ |
| 19 | `amount_to_balance_ratio` | float | $[0, \infty)$ | `0.0` | $\text{amount} / \max(\text{oldbalanceOrg}, 1.0)$ strictly prior to transaction |
| 20 | `history_length` | int | $[0, \infty)$ | `0` | Total cumulative transfers sent by sender in account lifetime |
| 21 | `purchase_count_24h` | int | $[0, \infty)$ | `0` | Card purchases completed by user in the rolling 24 hours prior to $t$ |
| 22 | `purchase_amount_sum_24h` | float | $[0, \infty)$ | `0.0` | Total purchase monetary spend in the rolling 24 hours prior to $t$ |
| 23 | `time_since_last_purchase_s` | float | $[0, 2592000]$ | `2592000.0` | Seconds elapsed since last card purchase; sentinel $2,592,000$ |
| 24 | `recent_high_risk_category_purchase` | int | $\{0, 1\}$ | `0` | $1$ if user made a purchase in high-risk categories (`shopping_net`, `misc_net`) in 24h |
| 25 | `purchase_amount_sum_7d` | float | $[0, \infty)$ | `0.0` | Total purchase spend across past 7 days prior to $t$ |
| 26 | `purchase_amount_sum_30d` | float | $[0, \infty)$ | `0.0` | Total purchase spend across past 30 days prior to $t$ |
| 27 | `avg_purchase_amount_30d` | float | $[0, \infty)$ | `0.0` | Average purchase transaction value over past 30 days |
| 28 | `purchase_to_transfer_ratio_30d` | float | $[0, \infty)$ | `0.0` | $\text{purchase\_amount\_sum\_30d} / \max(\text{transfer\_amount\_sum\_30d}, 1.0)$ |
| 29 | `distinct_merchants_30d` | int | $[0, \infty)$ | `0` | Count of unique merchants patronized in past 30 days |
| 30 | `days_since_first_purchase` | float | $[0, \infty)$ | `0.0` | Days elapsed since customer's very first recorded purchase |
| 31 | `purchase_history_length` | int | $[0, \infty)$ | `0` | Total cumulative purchase count across user lifetime |
| 32 | `account_age_days` | float | $[0, \infty)$ | `0.0` | Days elapsed since user registered account on Vigilia |
| 33 | `total_history_length` | int | $[0, \infty)$ | `0` | Cumulative transactions across all domains (`purchase_history_length` + `history_length`) |
| 34 | `total_txn_count_1h` | int | $[0, \infty)$ | `0` | Total multi-domain transactions completed in last hour |
| 35 | `domain_mix_30d` | float | $[0, 1.0]$ | `0.0` | $\text{transfers\_30d} / \max(\text{total\_txns\_30d}, 1.0)$ |
| 36 | `reviews_30d` | int | $[0, \infty)$ | `0` | Historical transactions referred to human review in last 30d strictly prior to $t$ |
| 37 | `rejections_30d` | int | $[0, \infty)$ | `0` | Historical transactions rejected in last 30d strictly prior to $t$ |
| 38 | `days_since_last_review` | float | $[0, \infty)$ | `30.0` | Days since customer was last flagged for review; sentinel $30.0$ |

---

## 5. Mandatory Safety & Point-in-Time Rules

1. **Strictly Open Right Interval $[t - W, t)$**:
   * No feature calculation may include events occurring at timestamp $\ge t$.
   * `reviews_30d` and `rejections_30d` count only settled historical decisions before $t$. The current transaction's eventual decision or fraud label is strictly invisible.
2. **Leakage Columns Excluded**:
   * `newbalanceOrig` and `newbalanceDest` are forbidden.
   * `isFlaggedFraud` is forbidden.
   * `gender` is excluded to satisfy AI fairness regulations.
   * Identifiers (`customer_id`, `merchant_id`, `recipient_id`, `transaction_id`) are never passed as inputs.
