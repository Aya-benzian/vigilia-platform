# Vigilia — Human Analyst Workspace

> **Plateforme Fintech de Détection de Fraude & Gestion du Risque en Temps Réel**  
> **Module : Espace d'Analyse et d'Arbitrage Humain (Human-in-the-Loop)**

Ce dossier regroupe l'ensemble des composants, pages HTML, styles CSS, contrôleurs JavaScript et ressources graphiques composant l'espace complet d'analyse humaine (**Human Analyst Workspace**).

---

## 📁 Structure du Dossier

```text
human-analyst-dashboard/
├── analyst-overview.html       # Page 1 : Vue d'ensemble du poste de travail (KPIs, SLA, File Prioritaire)
├── review-queue.html           # Page 2 : Espace de traitement des dossiers (Filtres, Conformal Prediction)
├── case-investigation.html     # Page 3 : Enquête approfondie & Décision (SHAP, Isolation Forest, Seuils τ)
├── decision-history.html       # Page 4 : Historique des décisions & Journal d'Audit (Export CSV / ML)
├── README.md                   # Documentation technique et guide d'utilisation
├── assets/                     # Ressources visuelles officielles
│   ├── vigilia-logo-mark.png   # Logo officiel Vigilia (Navy 'V' + Orange trapezoid)
│   ├── login-shield-illustration.png # Illustration 3D glassmorphic du bouclier
│   └── favicon.png             # Favicon
├── css/                        # Feuilles de styles modulaires
│   ├── analyst-overview.css    # Styles spécifiques à la vue d'ensemble
│   ├── review-queue.css        # Styles de la file d'attente et des tableaux
│   └── variables.css           # Système de design global & Design Tokens Vigilia
└── js/                         # Contrôleurs interactifs JavaScript
    ├── analyst-overview.js     # Horloge UTC, compte à rebours SLA, actions rapides
    └── review-queue.js         # Filtrage dynamique, sélection, détection d'ambiguïté
```

---

## 🖥️ Description des 4 Vues du Dashboard

### 1. Vue d'Ensemble (`analyst-overview.html`)
- **En-tête dynamique** : Salutation personnalisée (`Bonjour Aïcha 👋`), statut du système ML et horloge UTC en temps réel.
- **4 Cartes Métriques (KPIs)** :
  - *Dossiers Critiques (< 30 min)* avec indicateur Donut à 70% et tendance.
  - *Mes Dossiers en Cours* (12 dossiers) avec sparkline bleue.
  - *En Attente d'Assignation* (18 dossiers) avec sparkline d'évolution.
  - *Résolus Aujourd'hui* (48 dossiers) avec indicateur Donut à 92%.
- **File Prioritaire (Priority Queue)** : Tableau interactif avec montants multi-devises (CAD, MAD, EUR, USD), scores IA, alertes conformes et bouton d'action *Prendre en charge*.
- **Charge de Travail (7 derniers jours)** : Mbiant SVG مساحي (Area Chart) affichant les cas traités et cas critiques.
- **Activité Récente** : Fil chronologique vertical des dernières décisions prises.

### 2. File de Traitement (`review-queue.html`)
- **Sélecteur d'Onglets** : Bascule entre *Unassigned Queue (24)* et *My Active Cases (12)*.
- **Barre de Contrôles & Filtres** :
  - Recherche plein-texte (ID Dossier, ID Transaction, Client).
  - Filtre par domaine (*Purchase*, *Transfer*).
  - Filtre par niveau de risque (*High > 0.85*, *Medium*, *Low*).
  - Filtre par montant (*> $5,000*, *$1,000–$5,000*, *< $1,000*).
  - Bascule exclusive **`Ambiguous Only ⓘ`** (isole les prédictions où l'ensemble conforme contient à la fois `['legit', 'fraud']`).
  - Bouton *Refresh 🔄*.
- **Tableau des Dossiers** : Badges de Conformal Set, compte à rebours dynamique du SLA restant et bouton d'investigation.
- **Carte État Vide (Empty State)** : S'affiche automatiquement lorsque les filtres ne renvoient aucun résultat, avec bouton de réinitialisation.

### 3. Enquête Approfondie & Décision (`case-investigation.html`)
- **Bandeau de Contexte** : ID Dossier (`VG-2048`), ID Transaction (`TXN-78421`), montant, domaine, statut et avertissement de conformité.
- **Onglets d'Enquête** : *Détails Transaction*, *Contexte Client*, *Explication IA (Actif)*, *Historique*.
- **Diagnostic ML Avancé** :
  - **Score de Risque** : Jauge tachymétrique (`0.8412`) comparée au seuil coût-sensible $\tau = 0.1200$ (Achat).
  - **Prédiction Conforme** : Alerte de zone d'ambiguïté statistique garantissant la couverture à 95% avec ensemble `["legitimate", "fraud"]`.
  - **Attributions SHAP (Waterfall)** : Impact des 8 principales variables (positives en rouge / négatives en vert).
  - **Détection d'Anomalie (Isolation Forest)** : Projection spatiale des observations avec score $-0.184$ et détection d'outlier (`True`).
- **Panneau de Décision (Decision Panel)** :
  - Sélection radio : *Approve (Légitime)*, *Reject (Fraude Confirmée)*, *Escalate (Tier 2 / Enquête)*.
  - Sélection obligatoire du *Code Motif*.
  - Justification textuelle obligatoire (minimum 20 caractères avec compteur en direct).
  - Notes internes optionnelles et validation finale.

### 4. Historique & Journal d'Audit (`decision-history.html`)
- **Journal Immuable** : Traçabilité complète et non modifiable des décisions prises par les analystes.
- **Boutons d'Exportation** :
  - `Export CSV` : Fichier tabulaire des décisions pour reporting.
  - `Export for ML` : Données au format JSON pour l'Active Learning et le réentraînement des modèles.
- **Métrique d'Audit** : Total des décisions (1,248), Approuvées (892), Rejetées (301), Escaladées (55).
- **Tiroir d'Audit (Audit Details Drawer)** : Affiche les métadonnées de la transaction, les scores ML, le waterfall SHAP et la justification rédigée par l'analyste lors de la sélection d'une ligne du tableau.

---

## 🎨 Palette de Couleurs & Design Tokens (Vigilia Brand)

| Rôle | Token CSS | Valeur Hex | Utilisation |
| :--- | :--- | :--- | :--- |
| **Fond d'Écran (Stage)** | `--bg` | `#F0F4FA` | Fond général des pages |
| **Surfaces & Cartes** | `--card-bg` | `#FFFFFF` | Conteneurs blancs avec ombres douces |
| **Texte Principal** | `--text-main` | `#0F172A` | Titres, montants et textes en gras |
| **Texte Secondaire** | `--text-muted` | `#64748b` | Libellés, en-têtes de colonnes, sous-titres |
| **Bordures & Lignes** | `--border-color` | `#E2E8F0` | Séparateurs et contours de cartes |
| **Couleur Marque (Copper)** | `--copper` / `--copper-pill` | `#D97236` / `#FFEDD5` | Onglets actifs, boutons CTA [Q], accents |
| **Bleu Vigilia (FinTech)** | `--blue` / `--blue-light` | `#2563EB` / `#EFF6FF` | Badges Transfer, courbes de tendance, liens |
| **Vert (Légitime / Succès)** | `--green` | `#10B981` | Badges Approved, conformité 95% |
| **Rouge (Fraude / Critique)** | `--red` | `#EF4444` | Alertes SLA critique, rejet de fraude |
| **Typographie** | `--font-family` | `Geist Sans` & `Geist Mono` | Typographie moderne haute lisibilité |

---

## 🚀 Accès Local & Test (Via Docker / Nginx)

Les pages sont directement servies par le conteneur Nginx sur le port `8087` :

- **Vue d'ensemble** : [http://localhost:8087/analyst-overview.html](http://localhost:8087/analyst-overview.html)
- **File de traitement** : [http://localhost:8087/review-queue.html](http://localhost:8087/review-queue.html)
- **Dossier d'investigation** : [http://localhost:8087/case-investigation.html?case=VG-2048](http://localhost:8087/case-investigation.html?case=VG-2048)
- **Historique & Audit** : [http://localhost:8087/decision-history.html](http://localhost:8087/decision-history.html)

*(Également accessibles sous le chemin dédié `/human-analyst-dashboard/...`)*
