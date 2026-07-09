# Changelog — sigdep-sync

Le format suit [Keep a Changelog](https://keepachangelog.com/) et
adhère à [Semantic Versioning](https://semver.org/).

> Note : les entrées 2.0.0 → 2.1.0 n'ont pas été reportées ici au fil de
> l'eau ; voir les tags Git et l'historique des commits. La 2.1.1 reprend
> le suivi ci-dessous.

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
