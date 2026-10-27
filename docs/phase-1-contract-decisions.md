# Vigilia Platform — Phase 1 : Décisions d'Architecture et Contrats d'Interface

**Rôle :** Membre 3 — Backend Spring Boot & Logique Métier
**Statut global de la Phase 1 :** TECHNQUEMENT PRÉPARÉE, VALIDATIONS D'ÉQUIPE EN ATTENTE
**Date :** 25 Octobre 2026

---

## 1. Contexte et Objectifs

Ce document formalise les décisions d'architecture, les conventions techniques et les contrats d'interface (REST, OpenAPI, ML, Événements) pour les cinq microservices sous la responsabilité de **Membre 3** :
1. `api-gateway` (Port 8080)
2. `auth-service` (Port 8081)
3. `customer-service` (Port 8082)
4. `transaction-service` (Port 8083)
5. `risk-management-service` (Port 8084)

Il clarifie également les interactions avec **Membre 2** (`ml-serving`), **Membre 4** (`notification-service`, `audit-service`, `frontend`) et **Membre 5** (`infrastructure`, CI/CD, observabilité).

---

## 2. Registre des Décisions d'Architecture (Phase 1)

### Décision 1 : Contrat FastAPI ML pour PURCHASE et TRANSFER
- **Sujet :** Modalité d'exposition de l'inférence ML par le service FastAPI de Membre 2 pour les deux types de transactions du système (achats par carte et virements bancaires).
- **Faits confirmés dans le dépôt :**
  - Le pipeline de Membre 1 (`ml/training/`) n'est actuellement entraîné et testé que sur le dataset Sparkov (`fraudTrain.csv` / `fraudTest.csv`), qui représente exclusivement des transactions de type carte de crédit / marchandise (`PURCHASE`).
  - Aucun modèle ni dataset pour les virements (`TRANSFER`) n'existe actuellement dans `ml/training/`.
  - `ml/serving/main.py` n'implémente qu'un mock `GET /predict` aléatoire sans paramètre.
- **Proposition recommandée :**
  - Exposer deux endpoints spécialisés : `POST /predict/purchase` et `POST /predict/transfer`.
  - *Alternative acceptée :* Endpoint unique `POST /predict` avec champ discriminateur obligatoire `transaction_type: "PURCHASE" | "TRANSFER"`.
- **Justification :** Les vecteurs de caractéristiques (features) d'un achat par carte (coordonnées GPS commerçant, catégorie marchande, distance) et d'un virement (RIB/IBAN émetteur/bénéficiaire, historique de solde, type de virement) sont structurellement hétérogènes. Deux endpoints dédiés évitent des schémas Pydantic optionnels confus.
- **Options alternatives :**
  - *Option B :* Endpoint unique `/predict` avec union discriminée Pydantic.
  - *Option C :* Vecteur plat anonymisé unique (rejeté car obscurcit le domaine).
- **Risques et conséquences :** Si M2 ne supporte pas encore `TRANSFER`, `risk-management-service` doit appliquer une règle heuristique conservatrice (fallback `PENDING_REVIEW`) pour les virements en attendant la livraison du modèle M1/M2.
- **Membres devant valider :** Membre 1, Membre 2, Membre 3, Responsable de projet.
- **Fichiers concernés :** `contracts/ml/prediction-contract-v1.yaml`, `ml/serving/main.py`, DTOs de `risk-management-service`.
- **Statut :** **PROPOSÉ** (En attente de validation par M2/M1).

---

### Décision 2 : Schéma des champs de requête et de réponse ML
- **Sujet :** Définition exacte des données échangées entre `risk-management-service` et `ml-serving`.
- **Faits confirmés dans le dépôt :**
  - M1 a formalisé `ml/training/artifacts/feature_spec.json` avec 10 champs d'entrée bruts : `trans_date_trans_time`, `amt`, `category`, `gender`, `dob`, `lat`, `long`, `city_pop`, `merch_lat`, `merch_long`.
  - Les variables dérivées (`amt_log`, `distance_km`, `hour`, `is_night`, `age`, `city_pop_log`) sont générées par le pipeline scikit-learn de M1.
- **Proposition recommandée :**
  - **Requête :** Le backend transmet les données métier brutes + métadonnées d'exécution (`transaction_id`, `correlation_id`, `timestamp`). Le service FastAPI M2 effectue la transformation via le pipeline pré-entraîné exporté dans MLflow.
  - **Réponse :** JSON standardisé contenant :
    ```json
    {
      "transaction_id": "uuid",
      "model_name": "purchase-fraud-model",
      "model_version": "1.0.0",
      "prediction": "FRAUD" | "LEGITIMATE",
      "fraud_probability": 0.8425,
      "uncertainty": 0.1250,
      "features_importance": [
        {"feature": "amt", "importance": 0.42},
        {"feature": "distance_km", "importance": 0.31}
      ]
    }
    ```
- **Justification :** Évite de dupliquer la formule mathématique Haversine ou les normalisations scikit-learn dans le backend Java, garantissant l'alignement strict avec l'entraînement.
- **Membres devant valider :** Membre 1, Membre 2, Membre 3, Membre 4.
- **Fichiers concernés :** `contracts/ml/prediction-contract-v1.yaml`.
- **Statut :** **PROPOSÉ**.

---

### Décision 3 : Politique de décision de risque, incertitude et seuils
- **Sujet :** Règles de transition d'état et prise de décision finale dans `risk-management-service`.
- **Faits confirmés dans le dépôt :**
  - `docs/architecture.md` (L12-L13) stipule : *"Risk management chooses `APPROVED`, `REJECTED`, or `PENDING_REVIEW`. ML failure safely falls back to `PENDING_REVIEW`."*
  - `docs/open-decisions.md` (L24) indique que les seuils réels restent une décision ouverte.
- **Proposition recommandée :**
  - Moteur de décision à 3 niveaux dans `risk-management-service` :
    1. **Règles d'exclusion strictes (Hard Rules) :**
       - Montant > Seuil critique compte ou pays sur liste noire -> `REJECTED` immédiat sans appel ML.
    2. **Évaluation ML + Incertitude :**
       - Si `uncertainty > UNCERTAINTY_THRESHOLD` (ex: 0.35 configurable) -> `PENDING_REVIEW`.
       - Si `fraud_probability >= FRAUD_THRESHOLD_HIGH` (ex: 0.80) -> `REJECTED`.
       - Si `fraud_probability <= FRAUD_THRESHOLD_LOW` (ex: 0.20) -> `APPROVED`.
       - Si entre les deux seuils -> `PENDING_REVIEW`.
    3. Les seuils sont injectés par variables d'environnement (`RISK_THRESHOLD_LOW`, `RISK_THRESHOLD_HIGH`, `RISK_UNCERTAINTY_THRESHOLD`) et documentés comme **non définitifs**.
- **Règle absolue :** Ni une erreur ML, ni une réponse invalide, ni une incertitude élevée ne doivent provoquer une approbation automatique.
- **Membres devant valider :** Membre 1, Membre 2, Membre 3, Responsable de projet.
- **Statut :** **PROPOSÉ** (Seuils numériques en attente de calibration M1/M2).

---

### Décision 4 : Résilience, Timeout, Circuit Breaker et Stratégie de Fallback
- **Sujet :** Gestion des défaillances et latences réseau lors des appels HTTP vers `ml-serving`.
- **Faits confirmés dans le dépôt :**
  - `contracts/observability/runtime-v0.1.md` réserve le port 8000 pour `ml-serving`.
  - `docs/open-decisions.md` (L27) classe le circuit breaker et timeout comme décision ouverte.
- **Proposition recommandée :**
  - Utilisation de **Resilience4j** dans `risk-management-service` :
    - **Connect Timeout :** 200 ms.
    - **Read Timeout :** 500 ms.
    - **Retry :** 1 tentative de retry uniquement sur erreur réseau `IOException`, délai 50 ms.
    - **Circuit Breaker :** Fenêtre glissante de 20 requêtes ; ouverture si le taux d'échec dépasse 50% ou si la latence au 95e percentile dépasse 600 ms ; durée d'ouverture 10 s avant demi-ouverture.
    - **Fallback :** En cas de `CallNotPermittedException`, `TimeoutException` ou 5xx du ML, le statut attribué à la transaction est impérativement **`PENDING_REVIEW`**, avec un motif explicite `FALLBACK_ML_UNAVAILABLE`.
- **Justification :** Protège le backend Spring Boot contre la saturation des threads en cas de surcharge du conteneur Python/Uvicorn, tout en garantissant la sécurité financière par mise en revue humaine.
- **Membres devant valider :** Membre 2, Membre 3, Membre 5.
- **Statut :** **PROPOSÉ**.

---

### Décision 5 : Intégration REST Notification / Audit et Transactional Outbox
- **Sujet :** Transmission des événements de décision vers `notification-service` et `audit-service`.
- **Faits confirmés dans le dépôt :**
  - `docs/open-decisions.md` (L11) verrouille la communication sur **REST** (pas de Kafka/RabbitMQ initialement).
  - Membre 4 est propriétaire de `notification-service` et `audit-service`.
- **Proposition recommandée :**
  - **Phase 1 & 2 (REST direct avec résilience) :** `transaction-service` et `risk-management-service` émettent des requêtes HTTP `POST` non-bloquantes (exécutées dans un pool asynchrone `@Async` Spring) vers :
    - `notification-service:8085/api/v1/notifications`
    - `audit-service:8086/api/v1/audit-events`
  - **Pattern Transactional Outbox (Phase 2+) :** Pour garantir la consistance transactionnelle sans broker de messages lourd, les événements sont persistés dans la table locale `<schema>.outbox_events` dans la même transaction que la décision, puis relayés de manière fiable.
- **Membres devant valider :** Membre 3, Membre 4, Membre 5.
- **Fichiers concernés :** `contracts/events/notification-event.yaml`, `contracts/events/audit-event.yaml`.
- **Statut :** **PROPOSÉ**.

---

### Décision 6 : Séparation des APIs Transaction et Revue Humaine
- **Sujet :** Découpage des endpoints pour le client final vs l'analyste de fraude.
- **Faits confirmés dans le dépôt :**
  - `transaction-service` gère la création et l'historique des transactions.
  - `risk-management-service` gère la logique de risque et la validation humaine.
- **Proposition recommandée :**
  - **`transaction-service` :**
    - `POST /api/v1/transactions` : Ingestion de transaction.
    - `GET /api/v1/transactions/{id}` : Consultation détaillée.
    - `GET /api/v1/transactions` : Historique filtré par client, date et statut.
  - **`risk-management-service` :**
    - `POST /api/v1/risk/evaluate` : Évaluation synchrone appelée par `transaction-service`.
    - `GET /api/v1/risk/reviews/pending` : File des transactions `PENDING_REVIEW` pour l'interface analyste.
    - `POST /api/v1/risk/reviews/{id}/decision` : Décision manuelle de l'analyste (`APPROVE` ou `REJECT` avec motif).
- **Membres devant valider :** Membre 3, Membre 4.
- **Statut :** **PROPOSÉ**.

---

### Décision 7 : Matrice RBAC et Authentification JWT
- **Sujet :** Modèle d'autorisations et transmission des identités.
- **Faits confirmés dans le dépôt :**
  - `docs/open-decisions.md` (L12) impose **JWT** sans état pour l'authentification inter-services.
- **Proposition recommandée :**
  - 3 rôles standardisés :
    1. **`ROLE_CUSTOMER` :** Accès limité à ses propres transactions (`/api/v1/transactions`) et son profil (`/api/v1/customers/me`).
    2. **`ROLE_ANALYST` :** Accès aux revues d'alertes (`/api/v1/risk/reviews/**`), audit trails et statistiques de détection.
    3. **`ROLE_ADMIN` :** Paramétrage des seuils de risque, gestion des règles, administration technique.
  - **Architecture de validation :**
    - L'API Gateway valide la signature du token JWT et extrait les claims (`sub`, `roles`, `customer_id`).
    - L'API Gateway propage l'identité via les headers HTTP internes sécurisés : `X-User-Id`, `X-User-Roles`, `X-Customer-Id`.
    - Les microservices internes valident les permissions à l'aide de Spring Security Method Security (`@PreAuthorize("hasRole('ANALYST')")`).
- **Membres devant valider :** Membre 3, Membre 4, Responsable de projet.
- **Statut :** **PROPOSÉ**.

---

### Décision 8 : Format d'erreur RFC 7807 / RFC 9457 et compatibilité
- **Sujet :** Standardisation de la représentation des erreurs HTTP.
- **Faits confirmés dans le dépôt :**
  - Les 5 microservices possèdent actuellement un DTO maison `ApiErrorResponse` (`code`, `message`, `status`, `correlationId`).
  - Spring Boot 3 intègre nativement `ProblemDetail` (RFC 7807, mis à jour par RFC 9457).
- **Proposition recommandée :**
  - Adopter le standard **RFC 7807 / RFC 9457** en s'appuyant sur `org.springframework.http.ProblemDetail`, tout en conservant les attributs existants en extensions :
    ```json
    {
      "type": "https://api.vigilia.com/errors/validation-error",
      "title": "Validation Error",
      "status": 400,
      "detail": "amount: must be greater than 0",
      "instance": "/api/v1/transactions",
      "code": "VALIDATION_ERROR",
      "correlationId": "550e8400-e29b-41d4-a716-446655440000",
      "timestamp": "2026-10-25T22:45:00Z"
    }
    ```
- **Justification :** Assure la conformité aux normes internationales d'API REST sans casser le code existant grâce à la conservation des propriétés `code` et `correlationId`.
- **Membres devant valider :** Membre 3, Membre 4, Membre 2.
- **Statut :** **PROPOSÉ**.

---

### Décision 9 : Propagation sortante de `X-Correlation-Id`
- **Sujet :** Traçabilité distribuée de bout en bout.
- **Faits confirmés dans le dépôt :**
  - `contracts/observability/runtime-v0.1.md` exige `X-Correlation-ID` sur toutes les requêtes et réponses.
  - Le filtre `CorrelationIdFilter` intercepte et journalise les requêtes entrantes, mais aucune transmission sortante n'est actuellement codée.
- **Proposition recommandée :**
  - Fournir un bean centralisé `RestClient.Builder` (ou `RestTemplateBuilder`) configuré avec un `ClientHttpRequestInterceptor` qui injecte systématiquement l'en-tête `X-Correlation-Id` récupéré depuis le `MDC` SLF4J (`MDC.get("correlationId")`).
- **Membres devant valider :** Membre 3, Membre 2, Membre 4, Membre 5.
- **Statut :** **PROPOSÉ**.

---

### Décision 10 : Schémas PostgreSQL et séparation de MLflow
- **Sujet :** Cartographie des données et périmètre des schémas.
- **Faits confirmés dans le dépôt :**
  - 4 schémas PostgreSQL isolés sont configurés pour M3 : `auth`, `customer`, `transaction`, `risk`.
  - MLflow possède une base de données séparée `mlflow`, avec son utilisateur dédié `mlflow`.
  - Les éventuels 4 autres schémas évoqués dans la conception v4 ne sont ni documentés ni justifiés par le code.
- **Proposition recommandée :**
  - Maintenir le périmètre M3 strictement borné à ses 4 schémas (`auth`, `customer`, `transaction`, `risk`).
  - Confirmer que `notification-service` et `audit-service` (appartenant à M4) disposeront le moment venu de leurs propres schémas dédiés (`notification`, `audit`) sans impacter les schémas M3.
  - Conserver la base `mlflow` totalement indépendante.
- **Membres devant valider :** Membre 3, Membre 4, Membre 5, Responsable de projet.
- **Statut :** **VALIDÉ pour les 4 schémas M3 & MLflow** / **EN ATTENTE pour les schémas M4**.
