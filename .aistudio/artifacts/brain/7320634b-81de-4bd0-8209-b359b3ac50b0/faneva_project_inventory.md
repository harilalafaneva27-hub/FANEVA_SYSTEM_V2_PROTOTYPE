# FANEVA SYSTEM V2 — Inventaire du Projet Android Natif

**Date de vérification** : 2026-10-20
**Statut de compilation** : Compilation réussie (BUILD SUCCESSFUL)
**Statut des tests** : 100% validés (6 tests unitaires Robolectric exécutés)
**Binaire généré** : `/.build-outputs/app-debug.apk`

---

## 1. Arborescence Complète Vérifiée dans l'Espace de Travail

### Configuration & Build Gradle
- `build.gradle.kts` (Racine du projet)
- `settings.gradle.kts` (Nom du projet : "FANEVA SYSTEM V2")
- `gradle.properties`
- `metadata.json`
- `gradle/libs.versions.toml` (Version Catalog avec Room, Compose, Navigation)
- `gradle/wrapper/gradle-wrapper.properties`
- `app/build.gradle.kts` (Application ID : `com.aistudio.fanevasystem.qxkp7z`, MinSdk 24, TargetSdk 36)
- `app/proguard-rules.pro`

### Code Source Principal (`/app/src/main/java/com/example/`)
- `MainActivity.kt` : Point d'entrée de l'application, initialisation Room, Injection ViewModel et Edge-to-Edge.
- **`domain/model/`** :
  - `Models.kt` : `UserRole` (Admin, Gérant, Vendeur avec masquage strict des marges), `StoreId` (A1 à A5), `CartItem`, `SaleType`, `PaymentMethod`, `AIInsight`, etc.
- **`data/local/`** :
  - `entity/Entities.kt` : 9 entités Room (`ProductEntity`, `StoreStockEntity`, `CustomerEntity`, `SaleEntity`, `SaleItemEntity`, `StockMovementEntity`, `DebtPaymentEntity`, `PendingSyncOperationEntity`, `SyncCursorEntity`, `UserPreferenceEntity`).
  - `dao/FanevaDaos.kt` : DAOs Room réactifs avec `Flow` et opérations suspendues.
  - `FanevaDatabase.kt` : Base Room locale avec initialisation et jeu de données de test complet.
- **`data/repository/`** :
  - `FanevaRepository.kt` : Transactions atomiques, interdiction stricte de stock négatif, déduplication par clé d'idempotence, synchronisation cohérente des dettes clients.
  - `SyncRepository.kt` : Modèles de données versionnés (`SyncBatchRequest`, `SyncBatchResponse`, ACK), Outbox locale et gestion sécurisée du curseur de synchronisation.
- **`ui/`** :
  - `FanevaViewModel.kt` : État global de l'application, catalogue, panier POS, transferts, gestion des dettes, moteur d'analyse FANEVA IA.
  - `navigation/FanevaNavigation.kt` : Navigation Material 3 et gestion du bouton retour Android (`BackHandler`).
  - `components/CommonComponents.kt` : Badges de synchronisation, sélecteur de magasin A1-A5, badge de rôle et cartes KPI.
  - `theme/` : `Color.kt`, `Theme.kt`, `Type.kt`.
  - **`screens/`** :
    - `DashboardScreen.kt` : Tableau de bord multi-magasins et indicateurs clés.
    - `PosScreen.kt` : Point de vente & caisse (comptant et crédit avec contrôle de plafond).
    - `InventoryScreen.kt` : Stocks multi-magasins A1-A5, protection de confidentialité vendeur.
    - `StoreTransfersScreen.kt` : Transfert inter-magasins avec vérification de stock source.
    - `CustomersDebtScreen.kt` : Carnet de crédit et reçus de règlement.
    - `SalesHistoryScreen.kt` : Journal d'audit et clés d'idempotence.
    - `SyncOutboxScreen.kt` : File d'attente Outbox et simulateur réseau.
    - `FanevaAiScreen.kt` : Module analytique en lecture seule.
    - `SettingsScreen.kt` : Sélecteur de profil démo et configuration Staging Cloudflare.

### Tests Unitaires & Robolectric (`/app/src/test/java/com/example/`)
- `ExampleRobolectricTest.kt` :
  1. `verify app name resource matches FANEVA SYSTEM V2` : Vérifié.
  2. `prevent negative stock on sale attempt` : Vérifié (rejet des surventes).
  3. `sale execution decrements stock accurately and generates outbox mutation` : Vérifié.
  4. `idempotency check prevents duplicate sales on retry` : Vérifié (déduplication).
  5. `credit sale increases customer debt atomically` : Vérifié (cohérence dette/stock).
  6. `inter-store transfer updates source and destination stocks atomically` : Vérifié.
- `ExampleUnitTest.kt`

### Ressources & Binaire APK
- `app/src/main/res/drawable/` : Logo FANEVA et icônes adaptatives (`ic_launcher_foreground.xml`, `ic_launcher_background.xml`).
- `app/src/main/res/values/strings.xml` : `app_name = "FANEVA SYSTEM V2"`.
- `/.build-outputs/app-debug.apk` : APK Android compilé et prêt à l'emploi.

---

## 2. Procédure d'Exportation

1. **Téléchargement du Projet Complet (ZIP)** :
   Dans la barre supérieure d'AI Studio, utilisez le menu des paramètres / options du projet pour sélectionner **"Export as ZIP"** (ou **"Push to GitHub"**). Le ZIP contiendra l'intégralité de l'arborescence ci-dessus, directement importable dans Android Studio.
2. **Récupération de l'APK** :
   Le fichier APK généré se trouve dans `/.build-outputs/app-debug.apk` et peut être téléchargé via le menu de build/outputs ou via l'émulateur en streaming.
