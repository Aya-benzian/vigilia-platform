# Comprehensive Technical Justification: Selection of PaySim for the Transfer (Virement) Fraud Pipeline

| **Metadata** | **Details** |
|---|---|
| **Document Type** | Machine Learning Architecture Decision & Team Alignment Guide |
| **Document ID** | ADR-ML-002 (Resolves Conception Document v4 Open Decision D-01) |
| **Author** | Member 1 (ML Training & Statistical Validation) |
| **Target Audience** | Vigilia Engineering Team (Team Lead Aya-benzian, Microservice Owners, Academic Jury) |
| **Status** | **Approved & Finalized for Transfer Pipeline Implementation** |
| **Scope Boundary** | Strictly ML Training, Feature Engineering, Data Hygiene & Model Contracts |

---

## Table of Contents
1. [Executive Summary](#1-executive-summary)
2. [The Core ML Philosophy: "Garbage In, Garbage Out" & The Duty of the ML Engineer](#2-the-core-ml-philosophy-garbage-in-garbage-out--the-duty-of-the-ml-engineer)
3. [Deep Dive into Data Leakage: What It Is and Why It Destroys Real-World Models](#3-deep-dive-into-data-leakage-what-it-is-and-why-it-destroys-real-world-models)
4. [Why a Separate Dataset is Mandatory: The Dual-Domain Architecture](#4-why-a-separate-dataset-is-mandatory-the-dual-domain-architecture)
5. [In-Depth Evaluation of Candidate Datasets & Rejection Rationale](#5-in-depth-evaluation-of-candidate-datasets--rejection-rationale)
6. [Why PaySim is the Absolute Best Choice for Vigilia Transfers](#6-why-paysim-is-the-absolute-best-choice-for-vigilia-transfers)
7. [The Engineering of Genuine Transfer "Logic" (Features)](#7-the-engineering-of-genuine-transfer-logic-features)
8. [The Exact Column-by-Column Treatment of PaySim](#8-the-exact-column-by-column-treatment-of-paysim)
9. [How to Pitch and Defend This Decision to Team & Jury](#9-how-to-pitch-and-defend-this-decision-to-team--jury)

---

## 1. Executive Summary

In Vigilia Conception Document v4 (Section 14), our architecture establishes a **Dual-Domain system**:
1. **Merchant Purchases (`purchase-fraud-model`)** evaluated on the Sparkov dataset.
2. **Peer-to-Peer Transfers (`transfer-fraud-model`)** evaluating wire transfers and peer-to-peer money movement.

Following a deep benchmark of public financial datasets, **PaySim (filtered to the `TRANSFER` transaction subset)** has been selected as the sole official dataset for the Transfer fraud model.

This document provides the full, unabridged technical explanation of:
* Why credit card datasets (Sparkov) cannot evaluate bank transfers.
* Why competitor datasets (IBM AML, Feedzai BAF, IEEE-CIS) were rejected.
* How we identify and eliminate **Data Leakage** (specifically the post-transaction balance leakage in PaySim).
* How we transform raw transfer logs into predictive behavioral features (Money Mule indicators, Account Takeover velocity, and Balance Drainage ratios).
* Exactly what to say in team alignment meetings and academic defense presentations.

---

## 2. The Core ML Philosophy: "Garbage In, Garbage Out" & The Duty of the ML Engineer

A recurring question in data science is: **"If a dataset gives us 15 or 22 columns, shouldn't we feed all of them into the model?"**

The answer is an unequivocal **NO**. 

In professional Machine Learning engineering, there is a foundational law:
$$\text{\textbf{Garbage In, Garbage Out (GIGO)}}$$

If an engineer blindly throws all database columns into an algorithm, the model will not learn intelligent fraud detection—it will engage in what we call **"Stupid Training" (Overfitting and Noise Memorization)**:
* **Memorization over Generalization**: If a tree model splits on customer names (`first`, `last`) or street addresses (`street`), it simply memorizes that *"John Doe on Elm Street committed fraud"*. When a new customer or transaction arrives in production, this memorized rule has zero predictive power.
* **Failure in Production**: The model will show deceptively high scores on training data (e.g., 99% accuracy) but will completely fail to detect new fraud patterns when deployed live.

### The Three Categories of Columns That an ML Engineer MUST Cancel / Drop:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│             THE THREE TYPES OF COLUMNS TO CANCEL / DROP IN ML               │
├──────────────────────────┬──────────────────────────┬───────────────────────┤
│ 1. IDENTIFIERS & NOISE   │ 2. DATA LEAKAGE (FUTURE) │ 3. REDUNDANT COLUMNS  │
├──────────────────────────┼──────────────────────────┼───────────────────────┤
│ • Names, SSNs, street    │ • Post-transaction data  │ • Timestamps in unix  │
│   addresses, transaction │ • Outcome status codes   │   when ISO exists     │
│   UUIDs, card numbers    │ • Balances computed      │ • Zip/State when GPS  │
│ • Cause: Overfitting and │   AFTER settlement       │   coordinates exist   │
│   memorization           │ • Cause: Cheating in test│ • Cause: Collinearity │
│                          │   and failing in prod    │   and slow compute    │
└──────────────────────────┴──────────────────────────┴───────────────────────┘
```

**Our core duty as ML Engineers is Feature Selection and Feature Engineering**: dropping the noise and synthesizing raw signals into **behavioral logic**.

---

## 3. Deep Dive into Data Leakage: What It Is and Why It Destroys Real-World Models

### 3.1 The Real-World Analogy (The Leaked Exam Sheet)
To understand Data Leakage intuitively, imagine a teacher preparing a difficult university examination:
* By mistake, the teacher leaves the **official answer key sheet** stapled to the back of the student's exam paper.
* The student sits in the exam room, copies the answers, and scores a perfect **20/20 (100%)**.
* The teacher celebrates, thinking the student is a genius.
* But the next month, that student is hired by a major engineering firm. In the real world, there is no leaked answer sheet. The student gets **0/20** and cannot complete basic engineering tasks.

**This is Data Leakage in Machine Learning.**  
Data Leakage occurs when information from the future, information that could not possibly be known at prediction time, or direct proxies of the target label are accidentally fed into the model during training.

The model achieves near-perfect metrics during testing ($PR\text{-}AUC \approx 0.999$), giving the team a false sense of security. But when deployed in production, it fails completely.

### 3.2 The Specific Data Leakage Trap in PaySim
In the PaySim dataset, the simulator generates several columns for each transaction:
* `amount`: The money being transferred.
* `oldbalanceOrg`: Sender's balance **before** the transaction.
* `newbalanceOrig`: Sender's balance **after** the transaction.
* `oldbalanceDest`: Recipient's balance **before** the transaction.
* `newbalanceDest`: Recipient's balance **after** the transaction.

#### The Trap:
In a real bank, when a customer opens the Vigilia mobile app, fills out the form, and taps **"Send Money"**, the transaction is in a **PENDING_EVALUATION** state. The bank calls our ML Serving API (`POST /predict/transfer`) to ask:
$$\text{"Should we approve this transfer, or is it fraudulent?"}$$

At that exact microsecond, **the transfer has NOT been executed yet**. The money has not left the sender's account, and it has not arrived in the recipient's account. Therefore, in the real world:
$$\textbf{newbalanceOrig and newbalanceDest DO NOT EXIST AT PREDICTION TIME!}$$

Furthermore, in the PaySim simulator logic:
* In legitimate transfers: $\text{newbalanceOrig} = \text{oldbalanceOrg} - \text{amount}$.
* In simulated fraudulent transfers: Due to the synthetic injection rules, this equation frequently violates normal arithmetic (e.g., balance drops to zero regardless of amount, or recipient balances do not increment normally).

If an inexperienced engineer includes `newbalanceOrig` or `newbalanceDest`, the decision tree immediately notices:
$$\text{IF } (\text{oldbalanceOrg} - \text{amount} \neq \text{newbalanceOrig}) \implies \text{FRAUD = 1}$$

The model achieves **99.9% false accuracy** by solving a trivial arithmetic shortcut created by the simulator's code. It learns **zero fraud behavior**.

#### Our Rigorous Mitigation:
In our preprocessing pipeline:
1. **`newbalanceOrig` is STRICTLY DROPPED.**
2. **`newbalanceDest` is STRICTLY DROPPED.**
3. **`isFlaggedFraud` is STRICTLY DROPPED** (this is a static business rule for amounts $> 200,000$, not an ML feature).
4. **`oldbalanceOrg` is KEPT**, because the sender's balance *prior* to tapping the button is legitimate, historical information that the bank's core ledger already possesses.

By dropping these post-transaction fields, the model is forced to evaluate genuine behavioral risk (velocity, account-draining ratios, and counterparty relationships).

---

## 4. Why a Separate Dataset is Mandatory: The Dual-Domain Architecture

In Vigilia, transactions fall into two fundamentally distinct financial domains:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   VIGILIA DUAL TRANSACTION DOMAINS COMPARISON               │
├──────────────────────────┬──────────────────────────────────────────────────┤
│ ATTRIBUTE                │ PURCHASE DOMAIN           │ TRANSFER DOMAIN      │
├──────────────────────────┼───────────────────────────┼──────────────────────┤
│ Transaction Nature       │ Cardholder → Merchant     │ Customer → Customer  │
│ Counterparty             │ Business Store / Pos      │ Peer Beneficiary     │
│ Payment Instrument       │ Debit/Credit Card         │ Bank Wire / Transfer │
│ Primary Fraud Vector     │ Stolen credentials,       │ Account Takeover     │
│                          │ skimming, CNP fraud       │ (ATO), Money Mules   │
│ Essential Coordinates    │ GPS (lat/long) of Store   │ Account numbers only │
│ Balance Awareness        │ Irrelevant (Credit line)  │ Critical (Ledger)    │
│ Platform Model           │ purchase-fraud-model      │ transfer-fraud-model │
│ Dedicated Dataset        │ Sparkov (Credit Card)     │ PaySim (Transfer)    │
└──────────────────────────┴───────────────────────────┴──────────────────────┘
```

### Why Sparkov Cannot Be Used for Transfers:
1. **Lack of Recipient Concept**: Sparkov records credit card transactions made at merchants (e.g., `fraud_Rippin`, grocery stores, gas stations). In a wire transfer, money moves between two customers. Sparkov has no concept of a destination account number or beneficiary wallet.
2. **Physical Geography vs. Ledger Transactions**: Sparkov's strongest fraud signal is the Haversine distance between the cardholder's home address and the merchant store coordinates. Wire transfers occur electronically between bank accounts over the internet; there is no physical merchant location.
3. **Absence of Balances**: Sparkov has no balance fields. In wire transfer fraud, the single most lethal indicator is **account draining** (a fraudster transferring 98% to 100% of the victim's available liquid funds in a single transaction).

Attempting to train a transfer fraud model on credit card data is scientifically invalid and would immediately be rejected during an academic or regulatory audit.

---

## 5. In-Depth Evaluation of Candidate Datasets & Rejection Rationale

To ensure absolute rigor, we evaluated four major candidate datasets against the requirements outlined in Conception Document Section 14.2.2:

```
┌───────────────────────────────────────────────────────────────────────────────────────────────┐
│                                CANDIDATE BENCHMARK MATRIX                                     │
├─────────────────────────┬───────────────┬─────────────────┬─────────────────┬─────────────────┤
│ CRITERION (§14.2.2)     │ PAYSIM (P2P)  │ IBM AML (AMLSim)│ FEEDZAI BAF     │ IEEE-CIS        │
├─────────────────────────┼───────────────┼─────────────────┼─────────────────┼─────────────────┤
│ Domain Match            │ 100% Transfer │ Multi-hop AML   │ Account Opening │ E-Commerce Card │
│ Sender & Recipient IDs  │ YES           │ YES             │ NO              │ NO              │
│ Account Balance Fields  │ YES           │ NO              │ NO              │ NO              │
│ Temporal Ordering       │ YES (Hourly)  │ YES             │ NO (Monthly)    │ YES             │
│ Severe Real Imbalance   │ YES (0.77%)   │ ~0.1%           │ ~1.1%           │ ~3.5%           │
│ Real-Time Serving Fit   │ EXCELLENT     │ POOR (Batch GNN)│ POOR (Signup)   │ POOR (Duplicate)│
│ Open Academic License   │ CC BY-SA 4.0  │ CDLA-Permissive │ CC BY 4.0       │ Kaggle Custom   │
│ VERDICT                 │ SELECTED ⭐   │ REJECTED ❌     │ REJECTED ❌     │ REJECTED ❌     │
└─────────────────────────┴───────────────┴─────────────────┴─────────────────┴─────────────────┘
```

### Detailed Rejection Justifications:

#### 1. Rejection of IBM Transactions for Anti-Money Laundering (AMLSim)
* **What it is**: A synthetic dataset developed by the MIT-IBM Watson AI Lab simulating multi-hop money laundering patterns (fan-in, fan-out, cyclical laundering, scatter-gather chains across shell banks).
* **Why it fails Vigilia's use case**:
  * **Not a Point-of-Sale Real-Time Problem**: AML detection is a regulatory compliance workflow executed in batch processing hours or days after transactions settle, using heavy Graph Neural Networks (GNNs). Vigilia's Transfer API requires synchronous, real-time transaction scoring under a strict 1500 ms SLA at the moment the customer submits the transfer.
  * **No Balances**: IBM AML tracks transaction amounts and currency conversions, but provides no account balance context. It cannot compute liquid balance drainage ratios.

#### 2. Rejection of Feedzai Bank Account Fraud (BAF — NeurIPS 2022)
* **What it is**: A benchmark dataset released by Feedzai at NeurIPS 2022 focusing on synthetic identity theft and account opening fraud.
* **Why it fails Vigilia's use case**:
  * **Wrong Lifecycle Stage**: BAF evaluates whether an applicant opening a new bank account is using a fake or stolen identity (KYC fraud). It does not contain peer-to-peer transaction logs or transfer beneficiary relationships.

#### 3. Rejection of IEEE-CIS / ULB Datasets
* **What it is**: Anonymized e-commerce card transaction logs (V1–V339 features, PCA-transformed).
* **Why it fails Vigilia's use case**:
  * **Duplicate Domain**: It covers card payments, which we already thoroughly model using Sparkov. It has no sender-recipient account relationships or wire transfer semantics.

---

## 6. Why PaySim is the Absolute Best Choice for Vigilia Transfers

PaySim was created by researchers at NTNU (Edgar Lopez-Rojas et al.) using an agent-based simulator parameterized from real financial logs provided by a major international mobile money service.

It is universally considered the gold-standard benchmark in peer-to-peer and mobile transfer fraud research for five distinct reasons:

### 1. Perfect Match with the Vigilia Customer Dashboard UI
In Vigilia, the transfer user journey is:
1. Customer logs into the dashboard.
2. Navigates to **"Send Money"**.
3. Enters the beneficiary's wallet / account number and the transfer amount.
4. Clicks **"Confirm Transfer"**.

PaySim mirrors this architecture exactly:
* `nameOrig`: The initiating customer ID (`C...`).
* `nameDest`: The beneficiary customer ID (`C...` for peer customers, `M...` for merchants).
* `amount`: The requested transfer value.
* `oldbalanceOrg`: The balance available in the sender's wallet immediately prior to confirmation.

### 2. Statistical Alignment with the Purchase Pipeline
In our credit card pipeline (Sparkov), we train on realistic, severely imbalanced data (~0.58% fraud in train). 
In PaySim, when filtered to the `TRANSFER` transaction type:
* **Total Transactions**: **532,909 transfers**.
* **Fraudulent Transactions**: **4,097 transfers**.
* **Class Imbalance**: **0.769% fraud rate**.

This provides exceptional statistical symmetry with our Sparkov pipeline, ensuring that both models undergo repeated stratified cross-validation and threshold optimization under genuine, real-world class distributions.

### 3. Chronological Continuity Without Lookahead Bias
PaySim transactions are indexed by a sequential `step` variable:
* $1 \text{ step} = 1 \text{ hour}$ of simulated real-world time.
* The dataset spans 744 steps, representing a complete, unbroken **30-day operational month**.
* This enables strict chronological splitting:
  $$\text{Train (Steps 1–520)} \longrightarrow \text{Calibration (Steps 521–595)} \longrightarrow \text{Test (Steps 596–744)}$$
* This prevents temporal lookahead bias and ensures that the model is tested strictly on future time horizons.

---

## 7. The Engineering of Genuine Transfer "Logic" (Features)

By dropping leaked and noisy fields, we engineer **predictive behavioral features** that directly satisfy Section 14.3.2 of the Conception Document:

### Feature 1: Money Mule Network Detection (`recipient_unique_senders_24h`)
* **Fraud Logic**: In wire transfer fraud, fraudsters rarely cash out stolen money directly into their own primary bank account. Instead, they recruit or compromise **"Money Mules" (Comptes Mulets)**—temporary accounts used to aggregate stolen funds from multiple compromised victims before rapidly withdrawing the cash.
* **Engineering**: We track inbound transactions to `nameDest` over a rolling 24-hour window. If an account that normally receives 1 transfer a month suddenly receives transfers from **12 different senders within 24 hours**, the `recipient_unique_senders_24h` feature spikes, signaling an active mule aggregation node.

### Feature 2: Account Drainage Ratio (`amount_to_balance_ratio`)
* **Fraud Logic**: Legitimate customers typically transfer a small to moderate fraction of their available funds (e.g., sending \$200 for rent from a \$3,000 balance $\implies \text{ratio} = 0.067$). Conversely, when an Account Takeover (ATO) fraudster breaches an account, they attempt to **drain the liquid funds completely** before the victim notices and contacts the bank.
* **Engineering**:
  $$\text{amount\_to\_balance\_ratio} = \frac{\text{amount}}{\text{oldbalanceOrg} + 1.0}$$
* Transfers where this ratio approaches $1.0$ (or exceeds $1.0$ due to overdraft attempts) represent an extreme behavioral anomaly.

### Feature 3: Sender ATO Burst Velocity (`sender_txn_count_1h`, `sender_txn_count_24h`)
* **Fraud Logic**: Automated credential-stuffing and script-driven account drains execute rapid sequential transactions to siphon money across multiple channels before transaction limits or lockout thresholds engage.
* **Engineering**: Rolling hourly and daily counts grouped strictly by `nameOrig`.

### Feature 4: Relationship Familiarity (`is_new_recipient`, `pair_transfer_count`)
* **Fraud Logic**: Legitimate users regularly transfer money to recurring beneficiaries (family, landlords, colleagues). Fraudulent transfers are almost always routed to a previously unseen recipient account.
* **Engineering**: Binary flag and cumulative historical frequency of the ordered pair $(nameOrig, nameDest)$.

### Feature 5: Temporal Cyclical Features (`hour_of_day`, `day_of_week`)
* **Fraud Logic**: Large unauthorized transfers disproportionately spike during late-night and early-morning hours when victims are asleep and cannot immediately receive SMS / push fraud alerts.
* **Engineering**:
  $$\text{hour} = \text{step} \pmod{24}, \quad \text{dow} = \left(\left\lfloor \frac{\text{step}}{24} \right\rfloor\right) \pmod 7$$

---

## 8. The Exact Column-by-Column Treatment of PaySim

Below is the definitive data dictionary specifying the role of every single raw column in the PaySim dataset for our training pipeline:

| Column Name | Raw Type | Pipeline Status | Technical & Architectural Rationale |
|---|:---:|:---:|---|
| `step` | `int` | **ENGINEERED** | Converted to `hour_of_day` and `day_of_week`. Also defines chronological train/val/test splits. |
| `type` | `str` | **FILTERED** | Filtered strictly to `TRANSFER` (the specific transaction domain of our model). |
| `amount` | `float` | **ENGINEERED** | Transformed to `amt_log = log1p(amount)` to compress monetary skew. |
| `nameOrig` | `str` | **BEHAVIORAL GROUP** | Used as the customer identifier to compute sender velocity, z-scores, and group cross-validation. Never passed as a raw string feature. |
| `oldbalanceOrg` | `float` | **ENGINEERED** | Sender's pre-transaction balance. Combined with amount to compute `amount_to_balance_ratio`. |
| `newbalanceOrig` | `float` | **DROPPED ❌** | **CRITICAL LEAKAGE:** Balance after transaction. Does not exist at decision time. Dropped to eliminate simulator rule cheating. |
| `nameDest` | `str` | **BEHAVIORAL GROUP** | Used to compute recipient mule fan-in counts (`recipient_unique_senders_24h`). Never passed as a raw string feature. |
| `oldbalanceDest` | `float` | **OPTIONAL / DROPPED** | Recipient pre-transaction balance. Optional signal of recipient history. |
| `newbalanceDest` | `float` | **DROPPED ❌** | **CRITICAL LEAKAGE:** Recipient balance after transaction. Post-transaction outcome leakage. |
| `isFraud` | `int (0/1)` | **TARGET ($y$)** | Ground-truth binary target variable for classification. |
| `isFlaggedFraud` | `int (0/1)` | **DROPPED ❌** | Static rule threshold ($amount > 200,000$). Redundant with ML amount features. |

---

## 9. How to Pitch and Defend This Decision to Team & Jury

Use the following structured scripts during team meetings, Jira / PR discussions, or your final academic project defense:

### 9.1 The 60-Second Elevator Pitch
> *"In our Conception Document, Vigilia is explicitly designed as a Dual-Domain platform: Merchant Purchases vs. Wire Transfers. We cannot use our credit card dataset (Sparkov) for wire transfers because wire transfers are peer-to-peer (customer-to-customer)—they require sender IDs, recipient accounts, and bank balance contexts.  
> After benchmarking the top 4 financial datasets (PaySim, IBM AML, Feedzai BAF, and IEEE-CIS), PaySim is the only dataset that fulfills 100% of the requirements defined in Section 14.2.2 of our conception. It provides explicit counterparty accounts, hourly timestamps over 30 days, pre-transaction balances, and a realistic 0.77% class imbalance.  
> Furthermore, we have implemented rigorous data hygiene: we explicitly dropped post-transaction balance fields (`newbalanceOrig/Dest`) to eliminate data leakage. This directly unblocks our `/predict/transfer` API and provides the backend with realistic Money Mule and velocity risk signals."*

### 9.2 Anticipated Tough Questions & Bulletproof Answers

#### Question 1: *"Why don't we use IBM AML (AMLSim) since it's also about bank accounts?"*
> **Answer**:  
> *"IBM AML is designed for Anti-Money Laundering regulatory audits. It investigates multi-hop shell bank laundering rings over days or weeks using batch graph analysis. Our Vigilia Transfer API is a real-time point-of-transfer transaction authorization system that must score a transaction in under 1500 ms at the moment a customer clicks 'Send'. Furthermore, IBM AML does not have account balances, making it impossible to calculate balance drainage ratios."*

#### Question 2: *"Doesn't PaySim have severe data leakage around the balance columns?"*
> **Answer (Demonstrates High Engineering Maturity)**:  
> *"Yes, and that is precisely where novice implementations fail. In naive projects, people train on `newbalanceOrig` and `newbalanceDest`. Because the simulator's arithmetic differs for fraudulent records, models achieve 99.9% false accuracy by exploiting post-transaction arithmetic.  
> We identified this trap immediately: our preprocessing pipeline strictly drops `newbalanceOrig` and `newbalanceDest`. We retain only `oldbalanceOrg`—which is legitimate historical data known prior to execution. By doing this, we force the model to learn genuine behavioral patterns rather than simulator artifacts."*

#### Question 3: *"Why can't we just use Sparkov for both models and save time?"*
> **Answer**:  
> *"Sparkov is an e-commerce credit card dataset: its primary features are card numbers, store categories (supermarkets, gas stations), and physical GPS distances to physical merchant locations.  
> Wire transfers do not have physical merchant coordinates or grocery store categories—they have peer recipient accounts, bank balances, and mule network dynamics. Training a wire transfer model on credit card data is scientifically flawed and would fail any academic or industrial review."*

### 9.3 Ready-to-Send Message for Aya & The Team (Slack / Discord / Teams)

> **@Aya @team**
>
> In accordance with our Conception Document v4 (Section 14 & Open Decision D-01), I have completed the comprehensive benchmark and technical justification for our **Transfer (Virement) fraud model dataset**.
>
> I have selected **PaySim (TRANSFER subset)** as the official dataset. Here is the concise rationale:
> 1. **Domain Precision**: It is purpose-built for peer-to-peer transfers between sender (`nameOrig`) and recipient (`nameDest`), matching our Customer Dashboard "Send Money" workflow.
> 2. **Conception Compliance**: It satisfies every checklist item in Section 14.2.2 (chronological hourly steps, balance contexts, and an authentic **0.77% fraud rate**).
> 3. **High-Value Features**: It enables engineering real transfer fraud signals, including **Money Mule detection** (`recipient_unique_senders_24h`) and **account draining ratios** (`amount_to_balance_ratio`).
> 4. **Zero Data Leakage**: We have explicitly eliminated post-transaction balance fields (`newbalanceOrig/Dest`) to ensure the model learns genuine fraud dynamics rather than simulator rules.
>
> The complete, in-depth architectural justification has been published to the repository:  
> `ml/training/TRANSFER_DATASET_JUSTIFICATION.md`
>
> Let me know if anyone has questions so we can lock Open Decision D-01 and proceed with training the Transfer pipeline! 🚀
