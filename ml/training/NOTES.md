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
11. [Progress Log — Session 2](#11-progress-log--what-was-done-session-2--2026-10-15)
12. [Real Dataset Numbers](#12-real-dataset-numbers-from-data_check--2026-10-15)
13. [Pipeline Stage Overview](#13-current-pipeline-status)
14. [What is a venv (Virtual Environment) vs Real Data](#14-what-is-a-venv-virtual-environment-vs-real-data)
15. [Session 3 Updates — PR & Quality Gate Hardening](#15-session-3-updates--pr--quality-gate-hardening-2026-10-16)
16. [Master Conception & Cahier des Charges Roadmap](#16-master-conception--cahier-des-charges-roadmap)
17. [Pipeline Verification vs Heavy Training Execution](#17-pipeline-verification-vs-heavy-training-execution)
18. [Understanding MLflow, Docker & The Live Results Dashboard](#18-understanding-mlflow-docker--the-live-results-dashboard)
19. [Session 4 Progress Log — Phase 2 Optuna Tuning Complete & Frozen Parameters](#19-session-4-progress-log--phase-2-optuna-tuning-complete--frozen-parameters-2026-10-18)
20. [Session 5 Progress Log — Phases 3–7 Complete: Full Training Pipeline, Champion Elected & MLflow Registry](#20-session-5-progress-log--phases-37-complete-full-training-pipeline-champion-elected--mlflow-registry-2026-10-18)

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

---

## 14. What is a venv (Virtual Environment) vs Real Data

### Virtual Environment (.venv) != Virtual Data
- **`venv`** stands for **Python Virtual Environment**.
- It is **not** virtual or simulated data. It is a completely isolated local folder (`ml/training/.venv/`) containing:
  1. A dedicated Python interpreter (Python 3.12.9)
  2. Isolated pip packages (`pandas`, `scikit-learn`, `xgboost`, `optuna`, `mlflow`)
- **Why it is used**: Prevents package version collisions with system software or other projects. It guarantees reproducibility across machines and Docker CI.

### Real Data Confirmation
- The data files are **real CSV files on disk** in `ml/training/data/raw/`:
  - `fraudTrain.csv`: 351 MB (1,296,675 real transactions)
  - `fraudTest.csv`: 150 MB (555,719 real transactions)
- What was run during unit tests (`pytest`) were fast smoke-test samples to verify code logic and algorithms without waiting hours.
- Real heavy training will run against these full disk datasets during Phase 2.

---

## 15. Session 3 Updates — PR & Quality Gate Hardening (2026-10-16)

### Key Actions Completed
1. **Model Factories Determinism (`models.py`)**: Added explicit `random_state=SEED` across all 5 model factories (`make_logreg`, `make_random_forest`, `make_xgboost`, `make_svm`, `make_mlp`) to satisfy SonarCloud static analysis security/reliability rules.
2. **Coverage Configuration (`.coveragerc`)**: Removed `relative_files = True` so coverage reports output absolute paths. This matches the CI container path rewriting via `sed` for SonarCloud Docker.
3. **Unit Tests Added (`test_models.py`)**: Implemented unit tests verifying factory construction, seed preservation, and estimator types for all 5 models.
4. **Preprocess Tests Unskipped (`test_preprocess.py`)**:
   - `test_unseen_category_handled`: Verified OneHotEncoder gracefully handles unseen test categories (`handle_unknown="ignore"`).
   - `test_stable_column_order`: Verified identical output feature order between train and test transformations.
   - All 9 preprocess and model tests passing locally.
5. **Git Pull Request**: Committed to `feat/ml-training-setup` and opened PR into `develop`.
6. **Trivy Scan Context**: Trivy container scan alerts on PR are related to Kubernetes manifest configurations in `infrastructure/kubernetes/*.yaml` (Aya's DevOps files), not ML training Python code.

---

## 16. Master Conception & Cahier des Charges Roadmap

Full overview of all phases based on the professor's specification, `docs/requirements-traceability.md`, and `docs/ownership.md`:

```
┌────────────────────────────────────────────────────────┐
│  Phase 1: Architecture & Testing Setup                 │  <-- ✅ 100% COMPLETE
│  (13 modules built, schema contract, unit tests, PR)   │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│  Phase 2: Heavy Experimentation (The Training Run)     │  <-- 🔜 NEXT STEP
│  (Tune → CV Compare → Statistical Tests → Selection)   │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│  Phase 3: Calibration, Thresholding & Final Test       │
│  (Calibrate on Part B, Threshold on C, fraudTest ONCE) │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│  Phase 4: MLflow Registration & Team Handoff           │
│  (Promote @champion, export artifacts for Member 2)    │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│  Phase 5: Academic Report & Defense Deliverables       │
│  (Friedman table, CD diagram, calibration curve)       │
└──────────────────────────┴─────────────────────────────┘
```

### Detailed Execution Steps for Remaining Phases

#### Phase 2 — Experiment Execution (Heavy Training)
1. **Step 2.1: Hyperparameter Tuning (`python -m src.pipeline tune`)**
   - Run Optuna across Part A (TPE sampler, 30 trials per model, 3-fold inner PR-AUC CV).
   - Models tuned: Logistic Regression, Random Forest, XGBoost, SVM, MLP.
   - Output: `artifacts/best_params.json`.
2. **Step 2.2: 5x10 Repeated Cross-Validation (`python -m src.pipeline compare`)**
   - Run 50 folds on identical splits for 5 models + 2 baselines.
   - Compute PR-AUC (primary metric), ROC-AUC, F1, F2, MCC, Brier score.
   - Also evaluate cardholder grouping via `StratifiedGroupKFold(cc_num)`.
   - Output: `artifacts/cv_results.csv`.
3. **Step 2.3: Statistical Rigor & Hypothesis Testing (`python -m src.pipeline stats`)**
   - Friedman non-parametric rank test across repeated CV folds.
   - Pairwise Wilcoxon signed-rank tests with Holm-Bonferroni correction.
   - Bootstrap 95% confidence intervals and Cohen's d effect sizes.
   - Output: Statistical comparison tables and Critical Difference (CD) diagram.
4. **Step 2.4: Objective Champion Selection (`python -m src.pipeline select`)**
   - Automatically executes the pre-committed decision rule in `src/select_model.py`.
   - Metric hierarchy: PR-AUC superiority -> Brier Score tie-breaker -> explainability.

#### Phase 3 — Calibration & Final Evaluation
1. **Step 3.1: Probability Calibration & Threshold Tuning (`python -m src.pipeline calibrate`)**
   - Fit `CalibratedClassifierCV` (isotonic or sigmoid) on holdout **Part B** (never seen during training).
   - Find optimal operating threshold tau on holdout **Part C** using fraud business cost matrix and F2-score (prioritizing fraud recall).
   - Output: Calibrated pipeline artifact and optimal threshold.
2. **Step 3.2: Final Academic Test Evaluation (`python -m src.pipeline evaluate`)**
   - Evaluated **EXACTLY ONCE** on the untouched `fraudTest.csv` (555,719 rows).
   - Computes final unbiased test metrics: PR-AUC, ROC-AUC, F1, F2, MCC, Brier score, and inference latency per transaction.

#### Phase 4 — MLflow Registry & Team Interface (Contract 1)
1. **Step 4.1: MLflow Model Registry (`python -m src.pipeline register`)**
   - Connect to local or cluster MLflow server (`http://localhost:5000`).
   - Log parameters, metrics, artifact binaries, and curves.
   - Promote model version to `@champion` alias.
   - Generate `model_card.json`.
2. **Step 4.2: Team Handoff (Contract 1)**
   - **Member 2 (FastAPI Serving)**: Receives `@champion` pipeline and `src/schema.py`. Can run inference directly without reimplementing feature engineering.
   - **Member 4 (Frontend UI)**: Receives model card metrics and latency benchmarks for the analyst dashboard.

#### Phase 5 — Academic Report & Defense Deliverables
- Model Benchmark Table (Baselines + 5 ML models with mean +/- std).
- Statistical Significance Tables (Friedman p-value, Wilcoxon/Holm matrix).
- Visualizations: Critical Difference Diagram, PR/ROC curves, Reliability Diagram (before vs after calibration).
- Final presentation script for the team's 5-minute project defense.

---

## 17. Pipeline Verification vs Heavy Training Execution

### 1. Why we tested on small/mock data first
- In software engineering and MLOps, testing is divided into two distinct steps:
  1. **Pipeline Construction & Verification (Smoke/Unit Tests)**:
     - Tests code logic, transformations, edge cases (unseen categories, NaN handling), and data contracts.
     - Uses small dummy/synthetic batches so that `pytest` runs in 2 seconds instead of hours.
     - Guarantees zero runtime crashes, mathematical correctness, and SonarCloud/CI compliance *before* launching heavy compute.
  2. **Heavy Real Training Execution**:
     - Runs the validated pipeline on the full 1.3 million real transactions (`fraudTrain.csv`).
     - Executes Optuna hyperparameter tuning, 5x10 repeated cross-validation folds, and model training.

### 2. How to properly run pipeline stages in PowerShell
When running commands from the terminal on Windows, ensure that the `.venv` Python and `PYTHONPATH` are active:

```powershell
# 1. Navigate to training workspace
cd c:\Users\Administrateur\Documents\vigilia-platform\ml\training

# 2. Activate Python 3.12 virtual environment
.\.venv\Scripts\activate

# 3. Add src directory to Python search path
$env:PYTHONPATH="src"

# 4. Instant verification on REAL data (takes ~5 seconds):
python -m src.pipeline data_check

# 5. Run heavy tuning (~30-40 mins of computation):
python -m src.pipeline tune
```

### 3. How to run with Docker (Option 2 — Aya / Team Standard)
Since the `vigilia-ml-training` Docker image and `vigilia-mlflow` server are running, you can run directly inside Docker with live volume mounts:

```powershell
# From repository root (vigilia-platform):
docker run --rm `
  --network vigilia_default `
  -v ${PWD}/ml/training/src:/workspace/src `
  -v ${PWD}/ml/training/data:/workspace/data `
  -v ${PWD}/ml/training/artifacts:/workspace/artifacts `
  vigilia-ml-training python -m src.pipeline tune
```

This guarantees 100% containerized execution with direct network connection to `http://mlflow:5000` and PostgreSQL!

---

## 18. Understanding MLflow, Docker & The Live Results Dashboard

### 1. The Big Picture: How the Pieces Connect
- **The Engine (Member 1 ML Code)**:
  - Trains the 5 fraud detection models (Logistic Regression, Random Forest, XGBoost, SVM, MLP).
  - Calculates metrics: PR-AUC, ROC-AUC, F1, F2, Brier score, inference latency.
- **The Database + Tracking Server (Aya / Member 5 Infra in Docker)**:
  - `vigilia-postgres`: Persistent PostgreSQL database storing all experiment metadata.
  - `vigilia-mlflow`: The MLflow tracking server orchestrating experiments and model artifacts.
  - Port conflict resolution: Host port mapped to `5434:5432` in `docker-compose.yml` to prevent collisions with existing system Postgres.
- **The Live Dashboard (`http://localhost:5000`)**:
  - The built-in MLflow web UI connected to the database.
  - Instead of looking at raw text in a terminal, results stream live to this dashboard.
  - You, Aya, and the professor can visually inspect and compare models, parameters, and curves.

### 2. Live Verification Status (2026-10-17)
- `vigilia-postgres` and `vigilia-mlflow` running and healthy in Docker.
- Tracking URI verified active at `http://localhost:5000`.
- MLflow web dashboard confirmed live in browser with `vigilia-smoke-test` experiment visible.
- When `tune` is executed, the `fraud-sparkov-tuning` experiment appears in real-time with each model's trial results.

---

## 19. Session 4 Progress Log — Phase 2 Optuna Tuning Complete & Frozen Parameters (2026-10-18)

### 1. Optuna Hyperparameter Optimization Execution
- **Strategy**: 30 trials per model evaluated on 3-fold stratified cross-validation over the tuning subsample ($N=100,000$ transactions from Split A). Objective metric: PR-AUC (`average_precision`).
- **All 5 Models Tuned and Logged to MLflow** (`fraud-sparkov-tuning`, Experiment ID 2):

| Model | Optuna Best PR-AUC | Optimal Hyperparameters Selected | Execution Time |
|---|:---:|---|:---:|
| **XGBoost** | **0.9508** 🏆 | `learning_rate=0.0524`, `max_depth=7`, `n_estimators=415`, `subsample=0.741`, `colsample_bytree=0.681`, `reg_lambda=0.0130`, `scale_pos_weight=99.4` | ~2.2 h |
| **Random Forest** | **0.9194** | `n_estimators=462`, `max_depth=24`, `min_samples_leaf=1`, `max_features=0.3` | ~11.3 h |
| **MLP (Neural Net)** | **0.8785** | `hidden_layer_sizes=(128, 128, 128)`, `alpha=9.28e-5`, `learning_rate_init=0.00385` | ~8.0 h |
| **SVM (RBF)** | **0.5965** | `C=29.94`, `gamma='scale'` (on $N=10,000$ subsample) | ~3.5 min |
| **Logistic Regression** | **0.3379** | `penalty='l2'`, `C=0.00158`, `solver='lbfgs'` | ~26.7 min |

### 2. Engineering Hardening & Bug Fixes
- **Stratified Subsampling Fix**: In `src/tune.py`, the initial call `stratified_subsample(X_tune, ...)` threw a `KeyError: 'is_fraud'` because the target column had been extracted prior to passing features. Fixed cleanly using `sklearn.model_selection.train_test_split(..., stratify=y_tune)`.
- **MLflow Resumption & Idempotence**: Added `_get_cached_params(model_name)` in `src/tune.py`. If a model's tuning run is already marked `FINISHED` in MLflow, its parameters are loaded directly from the tracking server without re-computing 20+ hours of CPU calculation.
- **Artifact Export**: Generated and persisted `ml/training/artifacts/best_params.json` containing the frozen hyperparameter dictionary for all five models.

### 3. Pipeline Status at End of Phase 2
- Phase 1 (Data & Features): ✅ Complete
- Phase 2 (Optuna Tuning): ✅ Complete (`artifacts/best_params.json` created)

---

## 20. Session 5 Progress Log — Phases 3–7 Complete: Full Training Pipeline, Champion Elected & MLflow Registry (2026-10-18)

### 1. Phase 3: Cross-Validation & Model Comparison
We executed both cross-validation regimes on the $N=150,000$ comparison subsample from Split A:
1. **Repeated Stratified K-Fold (5 repeats × 10 folds = 50 folds total)**:
   - Evaluates pure discriminative ability across distinct sample splits.
2. **Stratified Group K-Fold (5 folds grouped by `cc_num`)**:
   - Evaluates generalization to entirely unseen cardholders (zero credit card overlap between train and test folds).

| Model | 50-Fold Repeated Stratified PR-AUC | 5-Fold Stratified Group PR-AUC (by `cc_num`) | Rank | Status |
|---|:---:|:---:|:---:|:---:|
| **XGBoost** | **0.9547 ± 0.0078** | **0.8719 ± 0.0312** | **1** 🏆 | Statistically superior |
| **Random Forest** | **0.9199 ± 0.0105** | **0.8985 ± 0.0200** | 2 | Strong generalization |
| **MLP (Neural Net)** | **0.8856 ± 0.0181** | **0.8707 ± 0.0193** | 3 | Competitive |
| **Logistic Regression** | **0.2841 ± 0.0260** | **0.2673 ± 0.0464** | 4 | Linear limitation |
| **SVM (LinearSVC)** | **0.2175 ± 0.0221** | **0.2023 ± 0.0350** | 5 | Linear baseline |
| **Dummy (Prior)** | **0.0092 ± 0.0001** | **0.0092 ± 0.0001** | 6 | Naive baseline |

*Logged to MLflow Experiment 3 (`fraud-sparkov-compare`). Results saved to `ml/training/artifacts/cv_results.pkl`.*

### 2. Phase 4: Statistical Hypothesis Testing
Rigorous statistical tests conducted following the Demšar (2006) protocol:
- **Omnibus Friedman Test**:
  - $\chi^2 = 250.0000$, $p = 5.50 \times 10^{-52} \ll 0.05$.
  - Rejects the null hypothesis of equal performance across models with overwhelming confidence.
- **Post-Hoc Pairwise Wilcoxon Signed-Rank Test with Holm-Bonferroni Correction**:
  - Comparison between top-ranked XGBoost and all other candidates:
    - XGBoost vs Random Forest: $p = 2.66 \times 10^{-14} < 0.05$ (Statistically Significant)
    - XGBoost vs MLP: $p = 2.66 \times 10^{-14} < 0.05$ (Statistically Significant)
    - XGBoost vs Logistic Regression: $p = 2.66 \times 10^{-14} < 0.05$ (Statistically Significant)
    - XGBoost vs SVM: $p = 2.66 \times 10^{-14} < 0.05$ (Statistically Significant)
    - XGBoost vs Dummy: $p = 2.66 \times 10^{-14} < 0.05$ (Statistically Significant)
- **Effect Sizes (Cohen's d relative to XGBoost)**:
  - Random Forest: $d = -3.73$ (Very large practical gap)
  - MLP: $d = -4.90$
  - LogReg: $d = -34.54$
  - SVM: $d = -44.03$
  - Dummy: $d = -169.33$
- **Artifacts Generated**:
  - `reports/tables/ci_effect_sizes.csv`
  - `reports/tables/wilcoxon_holm_pvalues.csv`
  - `reports/figures/ci_barchart.png`
  - `reports/figures/cv_distributions.png`
  - *Logged to MLflow Experiment 4 (`stats_tests`).*

### 3. Phase 5: Automated Model Selection Rule
Applying the pre-committed multi-criteria selection rule:
1. Candidate with highest mean PR-AUC: `xgboost` (mean = $0.9547$).
2. Tied set: Models with Holm-adjusted $p \ge 0.05$ or $|PR\text{-}AUC - 0.9547| \le 0.005$:
   - Tied set: `['xgboost']` (No other model is within margin or statistically tied).
3. **Official Champion Elected**: **`xgboost`**. Persisted to `ml/training/artifacts/selected_model.txt`.

### 4. Phase 6: Probability Calibration & Operating Threshold Selection
- **Part A Retraining**: Fitted Champion XGBoost with frozen Optuna parameters on full Part A ($1,037,340$ transactions).
- **Part B Calibration**:
  - Applied Isotonic Regression via `CalibratedClassifierCV(FrozenEstimator(pipe), method="isotonic")` on Part B ($129,667$ transactions).
  - **Brier Score on Part B**: **$0.0012$** (Near-perfect reliability curve).
  - Figure generated: `reports/figures/reliability_curve.png`.
- **Part C Threshold Selection**:
  - Optimized threshold to maximize $F_2$ score (weighting Recall $2\times$ over Precision to penalize missed frauds) on Part C ($129,668$ transactions).
  - **Optimal Operating Threshold**: **$0.1948$** ($F_2 = 0.8611$, $F_1 = 0.8313$).
  - Figure generated: `reports/figures/pr_curve_part_c.png`.
  - Saved: `ml/training/artifacts/calibrated_pipeline.pkl` and `ml/training/artifacts/threshold.txt`.

### 5. Phase 7: Final Test Set Evaluation (`fraudTest.csv` — Run ONCE)
Evaluated once on untouched test set ($555,719$ real transactions, $2,145$ frauds):

| Metric | Test Set Value | Business Interpretation |
|---|:---:|---|
| **PR-AUC** | **0.8624** | Primary metric (~$223\times$ lift over random base rate $0.0039$) |
| **ROC-AUC** | **0.9966** | Exceptional ranking discrimination |
| **F1 Score** | **0.7593** | Harmonic mean of precision & recall |
| **F2 Score** | **0.8025** | Prioritizes fraud capture ($2\times$ weight on recall) |
| **Recall (Sensitivity)** | **83.40%** | Caught $1,789$ out of $2,145$ fraudulent transactions |
| **Precision** | **69.69%** | Less than 1 in 3 flagged alerts are false alarms |
| **Specificity** | **99.86%** | Extremely low customer friction for legitimate transactions |
| **Matthews Corr (MCC)**| **0.7614** | Balanced correlation across severe imbalance |
| **Brier Score** | **0.0011** | Superbly calibrated probabilities for downstream serving |
| **Confusion Matrix** | **TP=1,789 \| FP=778 \| FN=356 \| TN=552,796** | Missed only 356 frauds out of 555k transactions |
| **Serving Latency** | **35.83 ms / sample** | Sub-100ms real-time SLA satisfied |

*Saved to `ml/training/artifacts/test_metrics.json`, `reports/tables/final_test_metrics.csv`, and `reports/figures/confusion_matrix_test.png`.*

### 6. MLflow Model Registry Champion Export (Contract 10)
- Registered Model Name: `fraud-model`
- Version: `1`
- Model Alias: `@champion`
- Serialization Format: Cloudpickle (encapsulates XGBoost + Scikit-Learn CalibratedClassifierCV)
- Verified Member 3 serving retrieval:
  ```python
  import mlflow.sklearn
  model = mlflow.sklearn.load_model("models:/fraud-model@champion")
  ```

### 7. Complete Artifact Hand-off Inventory
All artifacts required by Members 2, 3, 4, and 5 are fully generated and validated:

| File | Size | Destination / Consumer | Description |
|---|:---:|:---:|---|
| `artifacts/calibrated_pipeline.pkl` | 2.89 MB | Member 3 (ML Serving) | Calibrated Champion XGBoost pipeline |
| `artifacts/threshold.txt` | 19 B | Member 3 (ML Serving) | Operating threshold ($0.1948$) |
| `artifacts/feature_spec.json` | 2.2 KB | Member 2 & 3 (Contract 1) | Raw fields, types, ranges & engineered features |
| `artifacts/model_card.json` | 975 B | Member 2 & 4 (Doc §15) | Model card with performance, matrix & limitations |
| `artifacts/test_metrics.json` | 497 B | Member 1 & Team | Final evaluation numbers on `fraudTest.csv` |
| `artifacts/test_predictions.parquet` | 46.7 MB | Member 2 (Explainability) | 555k test rows + labels + calibrated scores + predictions for UMAP/t-SNE |
| `artifacts/scores_part_b.parquet` | 12.1 MB | Member 2 (Explainability) | 129k rows Part B for Conformal Prediction |
| `artifacts/scores_part_c.parquet` | 12.1 MB | Member 2 & 3 | 129k rows Part C for threshold & abstention studies |
| `artifacts/best_params.json` | 687 B | Member 1 & Audit | Frozen Optuna hyperparameters for all 5 models |
| `artifacts/cv_results.pkl` | 3.2 KB | Member 1 & Audit | 50-fold repeated CV & 5-fold group CV scores |
| `artifacts/selected_model.txt` | 7 B | Member 1 & Audit | Contains `"xgboost"` |
| `reports/figures/*.png` | ~250 KB | Team & Final Report | 5 publication-quality figures (CV, PR, Reliability, CM, Effect Sizes) |
| `reports/tables/*.csv` | ~2 KB | Team & Final Report | 3 summary tables (Metrics, Wilcoxon p-values, Effect sizes) |

---
**Status**: Member 1 ML Training Pipeline is **100% COMPLETE**. Ready for commit and review!





