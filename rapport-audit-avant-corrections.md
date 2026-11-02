# FANEVA SYSTEM V2 — Audit avant corrections

Date : 2026-10-25
Dépôt audité : `harilalafaneva27-hub/FANEVA_V2_IMPORT_TEMP`
Dépôt explicitement exclu : `harilalafaneva27-hub/FANEVA_SYSTEM`

## 1. État du dépôt et protection

- Accès GitHub : réussi.
- Branche par défaut : `main`.
- Commit initial audité : `0a63944ea0101a14913773f2b98cbc42cc6f493a` (`Fix Robolectric tests with Java 21`).
- Branche locale de travail créée : `audit/faneva-v2-corrections`.
- Référence locale conservée : tag `audit-baseline-0a63944` pointant vers le commit initial.
- Dépôt de production : non cloné et non modifié.
- État initial de la branche de travail après création : propre.
- Aucune publication distante et aucun changement de visibilité n’ont été effectués.

## 2. Inventaire

Le dépôt contient bien le projet source Android :

- Kotlin : `app/src/main/java/...` ; 18 fichiers Kotlin environ, dont les repositories, DAO, entités, base Room et ViewModel.
- Gradle : `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, wrapper properties.
- Tests : tests JVM/Robolectric sous `app/src/test` et test instrumenté sous `app/src/androidTest`.
- CI : `.github/workflows/android-build.yml`.
- Artefacts importés également présents : APK debug et archive source AI Studio.
- Aucun `gradlew` n’est versionné ; le workflow utilise une installation Gradle fournie par l’action `gradle/actions/setup-gradle`.

Configuration observée :

- Namespace : `com.example`.
- `applicationId` : `com.aistudio.fanevasystem.qxkp7z`.
- `minSdk` : 24.
- `targetSdk` : 36.
- `versionCode` : 1.
- `versionName` : `1.0`.
- Kotlin : 2.2.10.
- Android Gradle Plugin : 9.1.1.
- Java configuré dans le module : source/target 11 ; workflow : Java 21.
- Base Room version 1, sans migration et sans export de schéma.
- URL de démonstration par défaut : `https://staging-api.fanevasystem.workers.dev`.

## 3. Contrôle des secrets

Le contrôle des fichiers suivis n’a pas révélé de clé privée, de jeton ou de secret actif identifiable. `.env.example` ne contient qu’un placeholder commenté pour `GEMINI_API_KEY`. Les références à mots comme `secret`, `token` ou `password` correspondent à la configuration Gradle/AI Studio et aux commentaires de secrets, pas à des valeurs secrètes publiées.

Les APK, ZIP et images ont été exclus de l’interprétation textuelle des résultats. Aucun secret n’est recopié dans ce rapport.

## 4. Résultats par exigence

### F-01 — Synchronisation simulée : DÉFAUT CONFIRMÉ, critique

`SyncRepositoryImpl.triggerSync()` charge les opérations, les passe à `SYNCING`, attend `delay(1200)`, puis marque chaque opération `SYNCED` et avance le curseur sans effectuer d’appel HTTP. Les structures `SyncBatchRequest` et `SyncBatchResponse` sont seulement déclaratives. `stagingApiUrl` est stockée mais jamais utilisée par Retrofit/OkHttp.

Conséquences confirmées :

- aucune requête serveur réelle ;
- aucun contrôle de statut HTTP ou du corps de réponse ;
- aucun ACK réellement vérifié ;
- succès fictif possible lorsque le réseau simulé est actif ;
- une interruption après passage à `SYNCING` peut laisser des opérations bloquées ;
- aucune politique claire pour les opérations partiellement acquittées.

### F-02 — Sérialisation JSON : DÉFAUT CONFIRMÉ, critique

Dans `FanevaRepository.kt`, les payloads sont construits par interpolation de chaînes. Le résumé des articles utilise notamment `Map.toString()`, ce qui ne produit pas du JSON valide. Les guillemets, antislashs, caractères spéciaux et valeurs nulles peuvent casser les payloads. Les payloads concernés incluent au minimum `SALE`, `TRANSFER`, `DEBT_PAYMENT`, `CUSTOMER` et `PRODUCT`.

Une branche distante `origin/fix/v2-stock-json-validation` contient une correction partielle utilisant `JSONObject`/`JSONArray` pour les ventes/transferts, mais elle n’est pas fusionnée dans `main` et ne couvre pas encore tous les chemins métier ni les tests requis.

### F-03 — Idempotence : PARTIELLEMENT CORRIGÉE, majeur

`SaleEntity` et `PendingSyncOperationEntity` ont des index uniques sur `idempotencyKey`, et `processSale()` réutilise une vente existante si la même clé lui est explicitement fournie. Le test existant couvre ce cas.

Le défaut reste présent au niveau du flux UI : `FanevaViewModel.executeSale()` génère systématiquement `UUID.randomUUID()` à chaque appel. Une nouvelle tentative lancée après perte de réponse peut donc créer une nouvelle clé et une nouvelle vente. Les transferts et règlements ont aussi des UUID générés dans la méthode métier sans API explicite de retry stable.

### F-04 — Outbox bloquée et compteurs : DÉFAUT CONFIRMÉ, majeur

Le compteur compte uniquement `PENDING` et `FAILED`, pas `SYNCING`. Après une interruption, une opération `SYNCING` n’est donc plus reprise ni comptée comme attente. Le cycle ne récupère pas les opérations abandonnées. `insertOperation()` utilise `REPLACE` alors que l’index unique sur `idempotencyKey` peut remplacer silencieusement une opération métier existante.

La purge supprime toutes les opérations `SYNCED` sans vérifier qu’une preuve d’ACK et une règle de rétention sont disponibles. Le comportement n’est pas suffisamment sûr pour une synchronisation réelle.

### F-05 — Paiement supérieur à la dette : DÉFAUT CONFIRMÉ, majeur

`recordDebtPayment()` rejette seulement les montants `<= 0`. Pour une dette de 100 000 et un paiement de 150 000, il applique `coerceAtLeast(0.0)`, enregistre quand même un paiement de 150 000 et ramène la dette à zéro. La règle validée exige un refus intégral sans modification de la dette, du journal ni de l’outbox.

Les valeurs non finies (`NaN`, `Infinity`) ne sont pas rejetées explicitement et les tests correspondants n’existent pas.

### F-06 — Atomicité métier/outbox : DÉFAUT CONFIRMÉ, majeur

`processSale()` et `transferStock()` utilisent `database.withTransaction`, ce qui est une base correcte. En revanche `addNewCustomer()` et `addNewProduct()` écrivent d’abord les données métier, puis l’outbox hors transaction commune. Une panne entre les deux étapes peut laisser une donnée locale sans mutation synchronisable.

Les DAO de produits, stocks, clients et outbox utilisent plusieurs `REPLACE` ; leur usage doit être limité afin d’éviter les remplacements silencieux de données métier.

### F-07 — Réinitialisation de démonstration : DÉFAUT CONFIRMÉ, majeur

`resetDatabase()` appelle `populateInitialDemoData(database)` sans garde. Cette fonction écrit des préférences et curseur fixes, réinsère des produits/stocks/clients d’exemple avec des identifiants fixes et peut écraser des données à cause de `REPLACE`. Elle ne distingue pas un environnement vide d’une base contenant des données utilisateur et ne protège ni l’outbox ni le curseur.

### Transferts de stock : DÉFAUT PARTIELLEMENT CONFIRMÉ

Les contrôles de magasin distinct, quantité positive et stock source suffisant existent. Le transfert est transactionnel. Cependant :

- l’existence explicite des magasins et du produit n’est pas validée ;
- le résultat des `UPDATE` de stock n’est pas contrôlé ;
- le dépassement `Int` du stock destinataire n’est pas contrôlé dans `main` ;
- une destination absente est traitée comme stock zéro et créée/écrite implicitement selon le chemin DAO ;
- la sérialisation du payload est invalide pour certains caractères.

La branche distante de correction stock ajoute une partie des contrôles numériques et utilise `insertOrUpdateStock`, mais elle n’est pas encore intégrée ni testée dans cette branche.

### Rôles et autorisations : LIMITE CONFIRMÉE

Les rôles sont stockés et utilisés pour certains contrôles d’interface, par exemple la gestion d’inventaire et les transferts. Toutefois `switchRole()` permet de changer de rôle directement dans le prototype et les méthodes sensibles du repository ne vérifient pas le rôle. Il s’agit donc d’une simulation d’interface, pas d’une autorisation de sécurité. Cette limite doit être affichée/documentée et aucune sécurité serveur ne doit être déduite du rôle local.

## 5. Tests et build de référence

- Tests du projet exécutés dans l’environnement actuel : **non exécutés**.
- Motif : aucun `gradle`/wrapper `gradlew` n’est disponible dans le clone et la commande `gradle :app:testDebugUnitTest` échoue avec `gradle: command not found`.
- Build local : **non exécuté** pour le même motif.
- GitHub Actions : workflow présent, mais aucune exécution n’a été lancée par cet audit.
- Test sur Huawei Mate 10/API 28 : **non exécuté**.
- Test avec un vrai Worker Cloudflare : **non exécuté** ; aucun contrat API canonique ni endpoint confirmé n’est fourni.

Aucun test non exécuté n’est déclaré PASS.

## 6. Plan de correction proposé

1. **Synchronisation réelle** : définir un client HTTP injectable, utiliser l’URL configurée, sérialiser la requête avec Moshi, vérifier HTTP et ACK par mutation, traiter les rejets, conserver les non-acquittées, récupérer les `SYNCING` abandonnées et n’avancer le curseur que selon la réponse confirmée. En absence d’URL/serveur, retourner un état `SERVER_NOT_CONFIGURED` ou erreur explicite, jamais `Success`.
2. **JSON** : introduire des DTO Moshi ou des constructeurs JSON communs pour tous les types d’opérations et ajouter des tests de décodage avec guillemets, antislashs, accents, nulls et listes imbriquées.
3. **Idempotence** : persister la clé dès la création de l’opération métier, l’accepter dans les appels métier/UI et la réutiliser pour tout retry. Empêcher les clés générées à nouveau lors d’une relance.
4. **Outbox** : récupérer les `SYNCING` anciens, inclure tous les non-acquittés dans les compteurs, remplacer `REPLACE` par une insertion qui échoue en cas de collision et protéger la purge par un état ACK confirmé.
5. **Paiements** : rejeter explicitement les montants non finis, nuls, négatifs et strictement supérieurs à `currentDebt` avant toute écriture ; ajouter les tests d’atomicité.
6. **Atomicité** : placer client/produit, stocks initiaux et outbox dans une transaction commune ; vérifier le nombre de lignes affectées pour les mouvements.
7. **Démo et rôles** : rendre la réinitialisation non destructive et explicitement réservée à une base de démonstration vide ou à une action de purge confirmée ; documenter que les rôles locaux ne sont pas des contrôles de sécurité.
8. **Tests** : ajouter des tests pour F-01 à F-07, transferts, JSON, idempotence, valeurs non finies, crash/reprise outbox et protection des données utilisateur.
9. **Build** : ajouter/obtenir un wrapper Gradle reproductible ou utiliser GitHub Actions, exécuter tests puis build, inspecter l’APK, calculer SHA-256 et documenter signature/package/version/SDK.

Les corrections ne doivent commencer qu’après validation de ce plan et confirmation du contrat de réponse ACK du serveur, ou bien avec un mode explicitement documenté « serveur non configuré » pour les tests hors ligne.
