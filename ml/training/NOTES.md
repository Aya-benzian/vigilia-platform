# ML Training — Team Notes & Decisions Log
> **Member 1 — Machine Learning & Statistical Validation**
> This file is updated every time we make an important decision, learn something, or complete a step.
> Use it to explain the work to teammates or the professor.

---

## Table of Contents
1. [Project Role](#1-project-role)
2. [What is scikit-learn (sklearn)?](#2-what-is-scikit-learn-sklearn)
3. [Data Cleaning — How It Works](#3-data-cleaning--how-it-works)
4. [The sklearn Pipeline](#4-the-sklearn-pipeline)
5. [Dataset — Sparkov Fraud Data](#5-dataset--sparkov-fraud-data)
6. [Data Splits — A / B / C / Test](#6-data-splits--a--b--c--test)
7. [Environment Setup](#7-environment-setup)
8. [Files Created](#8-files-created)
9. [Tests Passing](#9-tests-passing)
10. [Next Steps](#10-next-steps)

---

## 1. Project Role

Member 1 owns **everything related to training and validating the ML model**:

| Responsibility | Output |
|---|---|
| Load and explore the dataset | `DATASET.md` |
| Build preprocessing pipeline | `src/preprocess.py` |
| Train 5 models + 2 baselines | `src/models.py`, `src/baselines.py` |
| Tune hyperparameters (Optuna) | `src/tune.py` |
| Compare models with repeated CV | `src/cv_compare.py` |
| Run statistical tests (Friedman, Wilcoxon, Holm) | `src/stats_tests.py` |
| Apply pre-committed selection rule | `src/select_model.py` DONE |
| Calibrate models on part B | `src/calibrate.py` |
| Final evaluation on fraudTest (once) | `src/evaluate.py` |
| Register champion model in MLflow | `src/register.py` |
| Hand off artifacts to Members 2, 3, 4, 5 | `src/export_artifacts.py` |

**Member 1 does NOT own:** serving, SHAP, UI, monitoring, business rules (those are Members 2-5).

---

## 2. What is scikit-learn (sklearn)?

**scikit-learn** (imported as `sklearn`) is the most popular Python library for Machine Learning.
It gives us ready-made tools so we do not build everything from scratch.

| Tool | What it does | Our use |
|---|---|---|
| `LogisticRegression` | Linear model for classification | Model 1 of 5 |
| `RandomForestClassifier` | Ensemble of decision trees | Model 2 of 5 |
| `SVC` / `LinearSVC` | Support Vector Machine | Model 3 of 5 |
| `MLPClassifier` | Neural network | Model 4 of 5 |
| `XGBClassifier` (XGBoost) | Gradient boosting | Model 5 of 5 |
| `DummyClassifier` | Trivial baseline | Baseline 1 |
| `Pipeline` | Chains preprocessing + model into one object | Core architecture |
| `SimpleImputer` | Fills missing values automatically | Data cleaning |
| `OneHotEncoder` | Converts text categories to numbers | Feature encoding |
| `StandardScaler` | Normalizes numbers to the same scale | For LogReg, SVM, MLP |
| `RepeatedStratifiedKFold` | Cross-validation with repeats | Model comparison |
| `CalibratedClassifierCV` | Calibrates model probabilities | Calibration step |
| `average_precision_score` | PR-AUC metric (our primary metric) | Model selection |

Why sklearn?
- All 5 models have the same `.fit()` and `.predict_proba()` interface -> easy to compare
- The `Pipeline` prevents data leakage automatically
- Industry standard -- professor and teammates will recognize it immediately

---

## 3. Data Cleaning -- How It Works

### What needs cleaning in the Sparkov dataset

| Problem | Solution | Where |
|---|---|---|
| Missing values (NaN) | SimpleImputer (median for numbers, most-frequent for text) | Inside Pipeline |
| Identifier columns (trans_num, first, last, street) | Dropped -- never used as features | preprocess.py |
| cc_num (credit card number) | Used only as CV group key -- never a feature | io.py / cv_compare.py |
| Raw timestamps | Parsed to hour, dow, is_night, age | preprocess.py |
| Skewed amounts (amt) | log1p(amt) -> amt_log | preprocess.py |
| Skewed city population | log1p(city_pop) -> city_pop_log | preprocess.py |
| Text categories | OneHotEncoder -> numbers | Inside Pipeline |
| Different scales | StandardScaler (only for LogReg, SVM, MLP) | Inside Pipeline |

### WARNING -- Why we do NOT clean the data manually first

If we clean the full dataset before splitting into train/test folds, the cleaning step
"sees" the test data = data leakage = artificially high scores that will not hold in production.

The sklearn Pipeline solves this:
- pipeline.fit(X_train_fold) -> imputer, scaler fitted ONLY on training fold
- pipeline.predict(X_test_fold) -> applies already-fitted transformers to test

No leakage possible.

---

## 4. The sklearn Pipeline

A Pipeline chains multiple steps together into one object:

```
Step 1: add_engineered_features  -- create amt_log, hour, distance_km, age, etc.
Step 2: drop identifiers          -- remove trans_num, first, last, street
Step 3: ColumnTransformer         -- SimpleImputer + OneHotEncoder
Step 4: StandardScaler            -- normalize (only for LogReg, SVM, MLP)
Step 5: Model                     -- LogisticRegression / RF / XGBoost / SVM / MLP
```

The Pipeline is the single object logged to MLflow.
Member 2 (serving) receives this Pipeline and calls pipeline.predict_proba(raw_input) directly.
No separate preprocessing needed on the serving side.

---

## 5. Dataset -- Sparkov Fraud Data

| Field | Value |
|---|---|
| Source | Kaggle -- "Credit Card Transactions Fraud Detection Dataset" by kartik2112 |
| Generator | Sparkov simulator -- SYNTHETIC data |
| Training file | fraudTrain.csv -- 351 MB (confirmed on disk) |
| Test file | fraudTest.csv -- 150 MB (confirmed on disk) |
| Target | is_fraud (0 = legitimate, 1 = fraud) |
| Imbalance | Very severe (~0.5% fraud) |
| Note | Synthetic data -> high scores do not prove real-world performance |

### Features we use

| Feature | Type | Note |
|---|---|---|
| amt -> amt_log | float | log1p transform |
| trans_date_trans_time -> hour, dow, is_night | datetime | engineered |
| dob + trans_date -> age | date | engineered |
| lat, long, merch_lat, merch_long -> distance_km | float | Haversine formula |
| city_pop -> city_pop_log | int | log1p transform |
| category | text | OneHotEncoded |
| gender | text | Binary encoded |

### Features we drop (never use as input to model)

| Column | Why |
|---|---|
| trans_num | Unique transaction ID -- identifier |
| first, last, street | Personal identifiers |
| cc_num | Credit card number -- used as CV group key only |

---

## 6. Data Splits -- A / B / C / Test

Splits are consecutive in time (sorted by transaction date):

```
fraudTrain.csv (sorted by date oldest to newest)
  Part A (first 80%) -- tuning + model comparison + final model training
  Part B (next 10%)  -- calibration only (model never trained on this)
  Part C (last 10%)  -- threshold selection only

fraudTest.csv -- final evaluation, used EXACTLY ONCE at the very end
```

### Why this order matters

| Rule | Reason |
|---|---|
| A then B then C is time-ordered | Simulates production: model trained on past, evaluated on future |
| B never used for training | Calibrator must see data the model has not trained on |
| fraudTest used ONCE | Looking at it multiple times = implicit tuning = cheating |
| Subsamples of A are disjoint | Tuning subsample != comparison subsample |

---

## 7. Environment Setup -- What We Did (2026-10-13)

### Step 1: Downloaded dataset
- fraudTrain.csv (351 MB) placed in ml/training/data/raw/
- fraudTest.csv (150 MB) placed in ml/training/data/raw/

### Step 2: Python version issue
- Machine had Python 3.14 -- too new, many ML packages do not support it yet
- Installed Python 3.12.9 from https://www.python.org/downloads/release/python-3129/
- Checked "Add python.exe to PATH" during install

### Step 3: Created virtual environment with Python 3.12
```
py -3.12 -m venv .venv
.venv\Scripts\activate
pip install --timeout 120 --retries 5 -r requirements.txt
pip freeze > requirements.lock.txt
```

### Step 4: Verified all packages installed correctly
```
numpy 2.5.3      pandas 3.0.6     sklearn 1.9.1
mlflow 3.17.0    xgboost 3.4.1    optuna 5.0.0    pydantic 2.14.0
```
108 packages installed and pinned in requirements.lock.txt.

### Problem encountered and fix
- First pip install failed: network timeout while downloading numpy (12 MB file)
- Fix: added --timeout 120 --retries 5 flags to pip install

---

## 8. Files Created -- What Each One Does

### Configuration files
| File | Purpose |
|---|---|
| requirements.txt | Pinned package versions (source of truth) |
| requirements.lock.txt | Exact reproducible environment (generated by pip freeze) |
| Dockerfile | Multi-stage Docker image for training |
| Makefile | Run pipeline stages: make tune, make all, etc. |
| pytest.ini | pytest configuration |
| conftest.py | pytest path setup so all imports work |
| DATASET.md | Dataset documentation -- fill (verify) fields on day 1 |
| NOTES.md | This file -- team knowledge base |

### Source modules (src/)
| File | Purpose |
|---|---|
| config.py | All settings: seeds, paths, splits, MLflow names, cost matrix |
| io.py | Load CSVs, hash files, split A/B/C, subsampling |
| preprocess.py | Feature engineering, sklearn Pipeline builder, Contract 1 |
| schema.py | Pydantic v2 input schema -- Contract 1 with Member 2 -- DONE |
| baselines.py | DummyClassifier + AmountThreshold baseline |
| models.py | 5 model factories (LogReg, RF, XGBoost, SVM, MLP) |
| tune.py | Optuna hyperparameter tuning for all 5 models |
| cv_compare.py | Repeated CV on identical folds for all models |
| stats_tests.py | Friedman, Wilcoxon, Holm, CIs, effect sizes, figures |
| select_model.py | FULLY WRITTEN -- pre-committed selection rule -- DONE |
| calibrate.py | Calibrate models on part B, choose threshold on part C |
| evaluate.py | Final test evaluation (run once at the very end) |
| cost_review.py | Cost view + review-budget curve |
| export_artifacts.py | All handoff files for Members 2, 3, 4, 5 |
| register.py | Register champion model in MLflow with @champion alias |
| pipeline.py | Orchestrator -- make all runs this |
| benchmark_ulb.py | NICE optional -- ULB dataset ranking check |

### Tests (tests/)
| File | Tests | Status |
|---|---|---|
| test_schema.py | Pydantic validation (7 tests) | 7/7 passing |
| test_select_model.py | Selection rule logic (7 tests) | 7/7 passing |
| test_preprocess.py | Haversine, features, encoding (6 tests) | waiting for preprocess.py |
| test_pipeline_smoke.py | Load model from MLflow (4 tests) | waiting for register.py |

---

## 9. Tests Passing (2026-10-13)

Command to run:
```
.venv\Scripts\activate
python -m pytest tests/test_schema.py tests/test_select_model.py -v
```

Result: 14 passed in 1.20s

Schema validation tests (test_schema.py):
- valid input passes
- negative amount rejected
- invalid gender rejected
- dob after transaction date rejected
- lat out of range rejected
- long out of range rejected
- zero city_pop rejected

Selection rule tests (test_select_model.py):
- clear winner selected correctly
- Brier tiebreaker applied when all tied
- practical margin excludes inferior model
- sensitivity agreement detected
- sensitivity disagreement resolved
- sensitivity conflict escalated
- explainability tiebreaker (XGBoost beats RF when Brier equal)

---

## 10. Next Steps

| Order | Task | File |
|---|---|---|
| 1 | Implement data loading + splitting | src/io.py |
| 2 | Implement feature engineering + Pipeline | src/preprocess.py |
| 3 | Implement baseline models | src/baselines.py |
| 4 | Implement 5 model factories | src/models.py |
| 5 | Implement Optuna tuning | src/tune.py |
| 6 | Implement repeated CV | src/cv_compare.py |
| 7 | Implement statistical tests | src/stats_tests.py |
| 8 | Implement calibration | src/calibrate.py |
| 9 | Implement final evaluation | src/evaluate.py |
| 10 | Implement cost/review curves | src/cost_review.py |
| 11 | Implement artifact export | src/export_artifacts.py |
| 12 | Implement MLflow registration | src/register.py |
| 13 | Wire together in pipeline | src/pipeline.py |

---

## 11. Progress Log — What Was Done (Session 2 — 2026-10-15)

### Modules Fully Implemented

All 13 src/ modules are now COMPLETE (no more NotImplementedError stubs):

| Module | Purpose |
|---|---|
| src/data_io.py | Load CSVs, SHA-256 hash, temporal A/B/C split, disjoint subsamples |
| src/preprocess.py | Feature engineering (haversine, age, hour, dow, is_night, log transforms) + sklearn Pipeline |
| src/schema.py | Pydantic v2 input validation -- Contract 1 with Member 2 |
| src/baselines.py | DummyClassifier + AmountThresholdClassifier (best F1 threshold search) |
| src/models.py | 5 model factories: LogReg, RandomForest, XGBoost, SVM, MLP |
| src/tune.py | Optuna tuning for all 5 models (30 trials each, TPE sampler, 3-fold inner CV PR-AUC) |
| src/cv_compare.py | RepeatedStratifiedKFold (5x10=50 scores) + StratifiedGroupKFold (5 folds, cc_num) |
| src/stats_tests.py | Friedman test, Wilcoxon pairwise, Holm correction, bootstrap CIs, Cohen d, figures |
| src/select_model.py | Pre-committed selection rule (FULLY WRITTEN + 7/7 tests passing before experiments) |
| src/calibrate.py | CalibratedClassifierCV with FrozenEstimator on part B, F2-threshold on part C |
| src/evaluate.py | Final test evaluation (run ONCE): PR-AUC, ROC-AUC, F1, F2, MCC, Brier, latency |
| src/register.py | MLflow Model Registry with cloudpickle, @champion alias, model_card.json |
| src/pipeline.py | CLI orchestrator: python -m src.pipeline [stage] |

### Bug Fixed: io.py -> data_io.py rename
- src/io.py conflicted with Python built-in io module
- Renamed to src/data_io.py
- Updated all imports in pipeline.py and tune.py automatically

### Bug Fixed: pandas 3.0 infer_datetime_format removed
- pd.to_datetime() no longer accepts infer_datetime_format=True in pandas 3.0+
- Removed that argument from preprocess.py and data_io.py

### Bug Fixed: Windows console encoding
- Arrow character caused UnicodeEncodeError on Windows cp1252 console
- Replaced with -> ASCII equivalent in data_io.py

### How to run the pipeline
All commands from ml/training/ with venv activated:

  .venv\Scripts\activate
  $env:PYTHONPATH="src"

  .venv\Scripts\python.exe -m src.pipeline data_check   -- verify data (DONE)
  .venv\Scripts\python.exe -m src.pipeline tune         -- Optuna tuning (~30-40 min)
  .venv\Scripts\python.exe -m src.pipeline compare      -- Repeated CV comparison
  .venv\Scripts\python.exe -m src.pipeline stats        -- Friedman + Wilcoxon + Holm
  .venv\Scripts\python.exe -m src.pipeline select       -- Apply selection rule
  .venv\Scripts\python.exe -m src.pipeline calibrate    -- Calibrate on B, threshold on C
  .venv\Scripts\python.exe -m src.pipeline evaluate     -- FINAL TEST (run once)
  .venv\Scripts\python.exe -m src.pipeline register     -- Register @champion in MLflow
  .venv\Scripts\python.exe -m src.pipeline all          -- Run everything at once

---

## 12. Real Dataset Numbers (from data_check -- 2026-10-15)

### fraudTrain.csv
- Rows: 1,296,675
- Columns: 23
- Fraud rate: 0.5789% (7,506 frauds)
- Date range: 2019-01-01 to 2020-06-21
- SHA-256: fd7139200dbfcbed0b6742bbe05a4f1abce532c4fef20918228a651647a3e75d
- Missing values: 0 (no missing data in this dataset)

### fraudTest.csv
- Rows: 555,719
- Columns: 23
- Fraud rate: 0.3860% (2,145 frauds)
- Date range: 2020-06-21 to 2020-12-31
- SHA-256: 12d553ab19440c752d2531ee1af44bb64f12cc3d3839f1649f19e81c230545f0
- Missing values: 0

### A / B / C Splits (from fraudTrain)
- Part A (first 80%): 1,037,340 rows -- 5,968 frauds -- 0.5753%
- Part B (next 10%):    129,667 rows --   706 frauds -- 0.5445% -- OK for calibration
- Part C (last 10%):    129,668 rows --   832 frauds -- 0.6416% -- OK for threshold selection

### Cardholders
- Train: 983 unique cc_num (credit card numbers)
- Test:  924 unique cc_num

---

## 13. Current Pipeline Status

| Stage | Status | Notes |
|---|---|---|
| data_check | DONE | All numbers verified, DATASET.md filled |
| tune | NEXT | Run: python -m src.pipeline tune |
| compare | Waiting | After tune |
| stats | Waiting | After compare |
| select | Waiting | After stats |
| calibrate | Waiting | After select |
| evaluate | Waiting | After calibrate -- run ONCE |
| register | Waiting | Last step |
