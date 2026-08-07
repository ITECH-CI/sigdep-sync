# Changelog — sigdep-sync

Le format suit [Keep a Changelog](https://keepachangelog.com/) et
adhère à [Semantic Versioning](https://semver.org/).

> Note : les entrées 2.0.0 → 2.1.0 n'ont pas été reportées ici au fil de
> l'eau ; voir les tags Git et l'historique des commits. La 2.1.1 reprend
> le suivi ci-dessous.

## [2.2.0] — non publié

### Ajouté

- **Résilience du push (SYNC-12)** : un échec de **transport** (coupure réseau,
  timeout, HTTP 5xx/429, `stream was reset: CANCEL` sur HTTP/2) est désormais
  distingué d'un rejet **applicatif** (400 : lot invalide). Sur un échec de
  transport, le lot est **ré-essayé** avec backoff exponentiel borné + jitter
  (`SIGDEP_HTTP_MAX_RETRIES`, défaut 5 ; délais `…_RETRY_INITIAL_DELAY_MS` /
  `…_RETRY_MAX_DELAY_MS`) avant de mettre l'entité en pause. Auparavant, la
  moindre coupure d'une seconde mettait l'entité en pause pour tout le cycle —
  problème observé sur `LAB_RESULTS` (seule entité enchaînant beaucoup de
  requêtes) quand nginx fermait la connexion HTTP/2 via `GOAWAY`. Un rejet
  applicatif, lui, ne provoque **aucun** retry (comportement inchangé).
- **Taille de lot paramétrable par type d'entité** (`batch-size-overrides`) :
  les entités aux payloads lourds peuvent utiliser des lots plus petits sans
  changer le `batch-size` global. Défaut : `LAB_RESULTS: 100`
  (`SIGDEP_BATCH_SIZE_LAB_RESULTS`). Réduit la taille des requêtes et la
  sensibilité aux coupures de transport.
- **Commande de remise en file des `DEAD_LETTER`** (`--requeue-dead-letter`) :
  remet en file les lignes bloquées (rejets de validation ayant épuisé leurs
  tentatives) une fois la cause corrigée côté hub — `status=PENDING`,
  `attempts=0`, `last_error` conservé. Optionnellement ciblée par entité
  (`--requeue-dead-letter=LAB_RESULTS`). La commande requeue puis arrête l'agent
  sans démarrer de cycle (scheduler désactivé pour ce lancement), et passe par
  le verrou d'écriture du buffer. Remplace le `UPDATE outbox` lancé à la main
  via un conteneur `alpine + sqlite3`.
- **Visibilité des identifiants exclus faute de mapping (SYNC-11)** : un type
  `patient_identifier_type` OpenMRS absent de `identifier-mapping` était
  jusqu'ici **exclu de la synchro en silence** — un site nommant son type ARV
  autrement que les clés configurées voyait tous ses codes ARV disparaître sans
  trace. L'agent loggue désormais un WARN la première fois qu'un type non mappé
  est rencontré (dédupliqué pour ne pas spammer le journal), nommant le type et
  le nombre d'identifiants concernés, avec la clé de config à ajouter.

### Corrigé

- **Suppressions logiques (void) invisibles côté hub (SYNC-10)** : la borne de
  pagination des extracteurs ne tenait compte que de
  `COALESCE(date_changed, date_created)`. Or, sur OpenMRS, voider une ligne
  renseigne `date_voided` mais ne met pas toujours à jour `date_changed` : la
  suppression ne faisait donc pas avancer le watermark, la ligne voidée n'était
  jamais renvoyée et le hub conservait une **donnée fantôme** (encore comptée
  dans les analyses Superset). Tous les extracteurs bornent désormais sur
  `GREATEST(COALESCE(date_changed, date_created), COALESCE(date_voided, date_created))`
  (idem pour les entités composées patient/personne et PTME mère/enfant), de
  sorte qu'un void récent repasse dans la fenêtre et propage `voided=true`.
  Couvert par `EncounterVoidedWatermarkIT` (MySQL testcontainers) : capture du
  void + contre-épreuve que l'ancienne borne le ratait.
- **`SQLITE_BUSY` (« database is locked ») sous charge** : régression introduite
  par le pipeline découplé de la 2.1.2. Le producteur (`enqueueBatch`) et le
  consommateur (`markSent`/`markRejected`/`updateWatermark`) écrivaient en
  concurrence sur le buffer SQLite ; sur un gros backfill (p. ex. 5000+
  lab_results en une transaction), l'enqueue tenait le verrou d'écriture plus
  longtemps que le `busy_timeout` et l'autre écrivain échouait. Les écritures du
  buffer sont désormais **sérialisées** par un verrou applicatif
  (`BufferWriteLock`), ce qui élimine la contention à la racine. `busy_timeout`
  porté à 5 s en complément.

### Interne

- **Tests d'intégration exécutés en CI** : les tests `*IT` (testcontainers
  MySQL) n'étaient lancés NI par `mvn test` NI par la CI — surefire ne matche
  que `*Test`, la CI faisait `mvn -DskipTests package`, et aucun failsafe
  n'était configuré. Ils existaient sans jamais tourner. Ajout du plugin
  failsafe (phase `verify`) et bascule de la CI `build.yml` vers `mvn verify`
  (sans `-DskipTests`). `mvn test` reste unitaire pur (rapide, sans Docker) ;
  `mvn verify` ajoute les IT.

## [2.1.2] — non publié

### Ajouté

- **Pipeline extraction/push découplé** : l'extraction du lot suivant ne bloque
  plus sur l'ACK du lot en cours. Profondeur bornée par `SIGDEP_PIPELINE_DEPTH`
  (défaut 2) ; le pipeline se met en pause si le hub est injoignable, sans
  sur-extraire. `sync_state` n'avance que sur ACK, ordre de push préservé par
  entity_type.
- **Version identifiable** : la version du JAR est alignée sur le tag Git
  (`-Drevision`), logue au démarrage (version, SHA, date de build), et l'image
  porte les labels OCI `version`/`revision`/`created`. L'image peut être
  épinglée par digest dans `docker-compose.site.yml` (procédure au README).
- **Smoke test de démarrage en CI** : l'image doit démarrer (sans base OpenMRS)
  et échouer proprement sur config invalide avant d'être poussée sur GHCR.

### Corrigé

- **Pagination keyset composite `(date, id)`** : 10 extracteurs paginaient sur
  la date seule et pouvaient sauter des lignes quand plus de `batchSize` lignes
  partageaient le même `date_changed` (migration de masse OpenMRS). Tous passent
  en keyset `(date, id)` avec tie-breaker sur la PK. Index recommandés
  documentés (`docs/keyset-indexes.md`).
- **Rejets de dépendance vs validation** : un `UNKNOWN_PATIENT` (parent pas
  encore ingéré — décalage d'ordonnancement) ne consomme plus de tentative et
  ne finit plus en `DEAD_LETTER` ; il est rejoué jusqu'à ce que le parent
  existe. Compteur de dépendances en attente par entité + log DEBUG de
  diagnostic. Vérifié : `COALESCE(date_changed, date_created)` extrait bien les
  lignes jamais modifiées (`date_changed` NULL).
- **Configuration fail-fast** : l'agent refuse de démarrer (code de sortie non
  nul) si la config est invalide — URL du hub sans schéma, clé API restée à
  `changeme`, code site absent… — au lieu d'échouer silencieusement à chaque
  cycle. Le message nomme la variable d'environnement fautive (clé API masquée).
- **Découpage DDL du buffer** robuste aux `;` en commentaire/littéral.

### Performance

- **Enqueue de l'outbox par lot** dans une transaction unique + `PRAGMA
  journal_mode=WAL` / `synchronous=NORMAL` : l'enqueue de 500 lignes passe de
  ~1,5 s à quelques dizaines de ms.
- **Dédup des logs d'échec** d'extraction récurrents (1 stack au 1er échec, puis
  résumé WARN, rappel périodique) au lieu de N stacks identiques par cycle.

## [2.1.1] — non publié

### Corrigé

- **Rejeu du dépistage (screening)** : l'extracteur screening utilise
  désormais un curseur **keyset** `(screening_date, hiv_screening_id)` au
  lieu d'un simple `screening_date >= ?`. La table amont n'ayant pas de
  `date_changed`, le curseur à granularité JOUR ne franchissait jamais la
  frontière du jour courant et ré-extrayait toute la journée à chaque cycle
  (rejeu absorbé en upsert idempotent côté hub, mais transactions
  `audit.sync_batch` qui s'accumulaient). Le tie-breaker `id` est persisté
  (`sync_state.last_id`, `outbox.source_id`) et le curseur n'avance que sur
  un batch 100 % accepté → robuste aux rejets, aucune ligne sautée.
  Migrations de schéma idempotentes (bases existantes non impactées).
  (`73cb042`)

### Documentation

- README racine traduit en français.
- Owner GHCR officiel figé sur `ghcr.io/itech-ci/sigdep-sync` dans
  la documentation et dans `deploy/docker-compose.site.yml`.

## [1.0.2] — 2026-05-21

### Ajouté

- **Packaging Windows** : ZIP `sigdep-sync-windows-<version>.zip`
  attaché à chaque release GitHub, contenant WinSW (service
  Windows natif), le fat-jar et un JRE Temurin 17 embarqué. Voir
  `packaging/windows/README.md` pour la procédure d'installation.

## [1.0.1] — 2026-05-21

### Corrigé

- CI : registre Docker en minuscules pour supporter les owners
  GitHub à casse mixte.
- CI : checkout sigdep-contracts placé dans le workspace
  (`.sigdep-contracts/`) pour respecter la limite de
  `actions/checkout`.

## [1.0.0] — 2026-05-21

Première release fonctionnelle de l'agent SIGDEP-3. Exécuté côté
site, lit OpenMRS local et pousse les données au hub central.

### Extractors

L'agent extrait les données suivantes depuis l'OpenMRS local
(MySQL, lecture seule) :

- **Patients** : démographie, identifiants nationaux.
- **Visites** (fiche PEC - Suivi patient) : signes vitaux, stade
  OMS, dépistage TB, régime ARV, jours de traitement, charge virale
  rapportée. Capture aussi les facts IVSA (concepts 165063, 165357,
  165369, 165324) via une requête sur `obs` par concept_id.
- **Initiations** (fiche initiale adulte + enfant) : 70+ obs
  mappés, dont profession, religion, niveau d'éducation, statut
  matrimonial, lieu de naissance (propagés à `core.patients` côté
  hub). Conversion automatique du poids de naissance grammes→kg.
- **Clôtures** : motif (DEATH / TRANSFER / VOLUNTARY_STOP /
  HIV_NEGATIVE / LOST), dates et causes.
- **Lab results** : extraction par bilan complet, CD4, charge virale.
- **TPT** : suivi du traitement préventif tuberculose.
- **Dépistage** (HIV screening) : module openmrs/hivscreening,
  données anonymes.
- **PTME** : mères enceintes + suivi enfants exposés + visites
  associées (4 extractors @Order 80-83).

### Architecture

- **Outbox SQLite** local pour la persistance des records en
  attente d'envoi, avec watermark par entité.
- **OutboxFlusher** : pagination par `SIGDEP_BATCH_SIZE` (500 par
  défaut), arrêt anticipé en cas d'échec HTTP pour préserver les
  cycles suivants.
- **Retry + DEAD_LETTER** : un record rejeté par le hub est rejoué
  jusqu'à `SIGDEP_MAX_REJECT_ATTEMPTS` fois (10 par défaut), puis
  parqué en DEAD_LETTER.
- **OkHttp timeouts configurables** via `SIGDEP_HTTP_CONNECT_/
  READ_/WRITE_TIMEOUT_SECONDS` (défaut 10/60/60).
- **Authentification OIDC** auprès du hub via le client
  `sigdep-agent` Keycloak (client_credentials).

### Déploiement

- Distribution sous forme de fat-jar exécutable.
- Unit systemd `packaging/systemd/sigdep-sync.service` + exemple
  `.env`.
- Variables d'environnement documentées dans le README.

### Scripts opérationnels

- `scripts/reset-agent.sh` : purge outbox + sync_state, restart
  systemd, avec confirmation interactive et flag `--yes`. Pour
  forcer une ré-extraction complète depuis openmrs.

### Connu mais non bloquant pour v1

- `DispensationExtractor` non écrit : dans SIGDEP la dispensation
  est captée sur la visite, pas comme encounter séparé. Le hub
  recalcule donc la métrique depuis `core.visits.arv_treatment_days`.
- Pas de tests automatisés. Dette technique reconnue.

[1.0.0]: https://github.com/ITECH-CI/sigdep-sync/releases/tag/v1.0.0
