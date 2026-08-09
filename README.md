# sigdep-sync

Agent de synchronisation déployé sur chaque site SIGDEP-3. Lit la base
OpenMRS locale (MySQL, en lecture seule), met les enregistrements
canoniques en file d'attente dans une base SQLite locale (outbox +
watermarks), et pousse les lots vers `sigdep-hub` en HTTPS.

## Place dans la plateforme SIGDEP-3

Ce dépôt est l'un des trois projets qui composent SIGDEP-3 :

| Projet                                                             | Rôle                                                                       |
| ------------------------------------------------------------------ | -------------------------------------------------------------------------- |
| [`sigdep-contracts`](https://github.com/ITECH-CI/sigdep-contracts) | Bibliothèque Maven : DTOs et contrats d'API partagés                       |
| **`sigdep-sync`** (ce dépôt)                                       | Agent côté site — lit OpenMRS local, pousse les lots au hub                |
| [`sigdep-hub`](https://github.com/ITECH-CI/sigdep-hub)             | Serveur central — réception des lots, indicateurs, console web             |

C'est cet agent qui rend un site visible sur la console nationale. Un
processus JVM par site, fonctionnant en continu, dialogue avec :

- **OpenMRS MySQL**, en local sur le réseau du site (lecture seule).
- Un **tampon SQLite** sur disque pour la tolérance hors-ligne et la
  reprise.
- Le **hub central** en HTTPS, authentifié par une **clé API** propre au
  site (en-tête `X-API-Key`).

```
   ┌──────────────────┐     ┌─────────────── sigdep-sync ────────────────┐
   │  OpenMRS du site │     │                                              │
   │  (MySQL, lecture)│◄────┤ extracteurs ─► tampon SQLite ─► pusher       │
   └──────────────────┘     │                  (outbox)        │           │
                            │                                  │  HTTPS    │
                            └──────────────────────────────────┼───────────┘
                                                               │ JWT
                                                               ▼
                                                     sigdep-hub /api/v1/sync/*
```

## Ce qui est extrait

Chaque `*Extractor` sous `src/main/java/.../sync/extractor/` lit une
table OpenMRS (ou une jointure), promeut les concepts connus en colonnes
typées, conserve le reste dans `extra_data`, et émet un enregistrement
défini dans `sigdep-contracts` :

| Extracteur             | Source                                        | Entité côté hub             |
| ---------------------- | --------------------------------------------- | --------------------------- |
| `PatientExtractor`     | `patient`, `person`, `person_name`, …         | `core.patients`             |
| `VisitExtractor`       | `encounter` de type suivi                     | `core.visits`               |
| `InitiationExtractor`  | encounters « PEC - Mise sous traitement »     | `core.treatment_initiations`|
| `ClosureExtractor`     | encounters « PEC - Issue »                    | `core.closures`             |
| `LabResultExtractor`   | `obs` pour les concepts de biologie           | `core.lab_results`          |
| `TptExtractor`         | « PEC - Suivi TPT » + « PEC - Issue TPT »     | `core.tpt_records`          |

Les watermarks (date du dernier enregistrement transmis avec succès
pour chaque entité) sont stockés dans le tampon SQLite, ce qui permet
à l'agent de reprendre où il s'est arrêté après un redémarrage ou une
coupure réseau.

## Configuration

Toute la configuration runtime vit dans un fichier `.env` à la racine
du projet. `spring-dotenv` le charge automatiquement au démarrage, et
le même fichier fonctionne sur Linux, macOS et Windows sans wrapper.

```bash
cp .env.example .env
# Éditer .env — au minimum renseigner SIGDEP_SITE_CODE,
# SIGDEP_LOCAL_DB_PASSWORD, SIGDEP_CENTRAL_API_URL et
# SIGDEP_API_KEY.
```

Variables principales :

| Variable                             | Rôle                                                  |
| ------------------------------------ | ----------------------------------------------------- |
| `SIGDEP_SITE_CODE`                   | Code du site — doit exister dans `core.sites`         |
| `SIGDEP_LOCAL_DB_URL`                | URL JDBC de la base OpenMRS MySQL                     |
| `SIGDEP_LOCAL_DB_USER` / `_PASSWORD` | Un compte **lecture seule** sur la base OpenMRS       |
| `SIGDEP_BUFFER_PATH`                 | Emplacement du tampon SQLite (volume persistant)      |
| `SIGDEP_CENTRAL_API_URL`             | URL de base de `sigdep-hub` (ingestion-api)           |
| `SIGDEP_API_KEY`                     | Clé API du site (en-tête `X-API-Key`), via la console |
| `SIGDEP_SYNC_INTERVAL_MINUTES`       | Période entre deux cycles (défaut 15 min)             |
| `SIGDEP_BATCH_SIZE`                  | Max d'enregistrements par appel HTTP (défaut 500)     |
| `SIGDEP_MAX_REJECT_ATTEMPTS`         | Réessais avant passage en DEAD_LETTER (défaut 10)     |

L'unit systemd (`packaging/systemd/sigdep-sync.service`) lit le même
format de fichier via `EnvironmentFile=`. En production, déposer le
fichier dans `/etc/sigdep-sync/sigdep-sync.env` et adapter l'unit en
conséquence.

## Construire le projet

Vous n'avez besoin de compiler depuis les sources que pour contribuer
ou tester des modifications non publiées. Pour un déploiement,
préférez les artefacts pré-construits publiés par la CI sur chaque
tag `v*.*.*` :

- **Image Docker** : `ghcr.io/<owner>/sigdep-sync:<version>`
- **ZIP Windows** : `sigdep-sync-windows-<version>.zip` attaché à la
  Release GitHub du tag.

Pour compiler depuis les sources :

```bash
# Installer d'abord sigdep-contracts (projet voisin)
git clone https://github.com/ITECH-CI/sigdep-contracts
cd sigdep-contracts && mvn -DskipTests install && cd ..

# Construire l'agent
git clone https://github.com/ITECH-CI/sigdep-sync
cd sigdep-sync && mvn clean package
```

Produit un fat JAR exécutable dans `target/sigdep-sync-*.jar`.

## Lancement local

```bash
./run.sh           # exécute le JAR packagé (proche de la prod)
./run.sh --dev     # via maven (redémarrage plus rapide en itération)
```

Windows :

```bat
run.bat
run.bat --dev
```

Les deux formes lisent `.env` automatiquement. L'agent journalise
chaque cycle sur stdout — à rediriger vers un fichier en production.

Pour un test bout-en-bout local, pointer
`SIGDEP_CENTRAL_API_URL=http://localhost:9000` vers une stack
`sigdep-hub` de dev (voir le README de ce dépôt).

## Déployer sur un site

Trois modes de déploiement sont supportés, à choisir selon le poste
cible. Le guide complet est dans
[`sigdep-hub/docs/user-guide/deploiement/installer-agent.md`](https://github.com/ITECH-CI/sigdep-hub/blob/master/docs/user-guide/deploiement/installer-agent.md) ;
ce qui suit est une fiche d'une page par mode.

### Mode A — systemd (Linux)

1. Copier le JAR dans `/opt/sigdep-sync/sigdep-sync.jar`.
2. Copier `packaging/systemd/sigdep-sync.service` dans
   `/etc/systemd/system/`.
3. Copier `.env.example` vers `/etc/sigdep-sync/sigdep-sync.env` et
   renseigner les valeurs propres au site.
4. Créer le répertoire du tampon :
   `mkdir -p /var/lib/sigdep-agent && chown sigdep-agent:sigdep-agent /var/lib/sigdep-agent`.
5. `systemctl enable --now sigdep-sync`.
6. Suivre les logs : `journalctl -u sigdep-sync -f`.

### Mode B — Docker

Des images pré-construites sont publiées sur GHCR par le workflow
[`release.yml`](.github/workflows/release.yml) à chaque tag
`v*.*.*` :

```
ghcr.io/<owner>/sigdep-sync:<version>
ghcr.io/<owner>/sigdep-sync:latest
```

`<owner>` est `itech-ci` (le compte GitHub officiel d'I-TECH Côte
d'Ivoire qui publie les releases). Un fork peut surcharger la valeur
via la variable de repo `IMAGE_REGISTRY`. Un compose de référence avec
trois scénarios réseau (host gateway, réseau Docker existant,
machine LAN distante) vit dans
[`deploy/docker-compose.site.yml`](deploy/docker-compose.site.yml).

```bash
cp deploy/docker-compose.site.yml /opt/sigdep-sync/docker-compose.yml
cp deploy/.env.example /opt/sigdep-sync/.env
# éditer .env, puis :
docker compose -f /opt/sigdep-sync/docker-compose.yml up -d
```

#### Épingler l'image par digest (recommandé en production)

Un tag (`:2.1.2`, `:latest`) peut être re-poussé vers un contenu différent ;
un **digest** `sha256:` est immuable et garantit que le site fait tourner
exactement l'image validée. Récupérer le digest d'une version publiée :

```bash
docker buildx imagetools inspect ghcr.io/itech-ci/sigdep-sync:2.1.2 \
  --format '{{.Manifest.Digest}}'
# → sha256:abc123…
```

Puis le figer dans le `.env` du site (le compose lit `SIGDEP_SYNC_IMAGE` en
priorité sur `SIGDEP_SYNC_TAG`) :

```bash
# /opt/sigdep-sync/.env
SIGDEP_SYNC_IMAGE=ghcr.io/itech-ci/sigdep-sync@sha256:abc123…
```

#### Mettre à jour un site vers une nouvelle version

```bash
cd /opt/sigdep-sync
# 1. Récupérer le digest de la nouvelle version (cf. ci-dessus) et mettre à
#    jour SIGDEP_SYNC_IMAGE dans .env.
# 2. Tirer la nouvelle image et recréer le conteneur :
docker compose pull
docker compose up -d
# 3. Vérifier la version qui tourne — une ligne INFO au démarrage :
docker compose logs --since 2m sigdep-sync | grep "sigdep-sync démarré"
#    → sigdep-sync démarré — version=2.1.2 sha=<sha court> build=<date>
#    On peut aussi lire les labels de l'image :
docker inspect --format '{{json .Config.Labels}}' \
  "$(docker compose images -q sigdep-sync)"
```

Le buffer SQLite et l'état de synchronisation vivent dans le volume
`sigdep-buffer` — une mise à jour d'image les préserve. **Ne jamais**
`docker compose down -v` (cela réinitialiserait le curseur de sync).

### Mode C — Service Windows (WinSW)

Une archive ZIP autonome est attachée à chaque Release GitHub :
`sigdep-sync-windows-<version>.zip` (~80 Mo). Elle contient WinSW, le
fat-jar et un JRE Temurin 17 embarqué — aucune installation Java
requise sur le poste du site.

1. Télécharger le ZIP depuis la [page des releases](https://github.com/ITECH-CI/sigdep-sync/releases).
2. Extraire dans un chemin **sans espaces ni accents** (ex. `C:\sigdep-sync\`).
3. Copier `sigdep-sync.env.example` en `.env`, renseigner les valeurs
   du site (encodage UTF-8 — UTF-16 fait échouer le démarrage).
4. Clic droit sur `install-service.bat` → **Exécuter en tant
   qu'administrateur**.

Voir [`packaging/windows/README.md`](packaging/windows/README.md) pour
le runbook Windows complet (logs, mises à jour, dépannage).

## Modèle de robustesse

L'agent garantit qu'aucun enregistrement extrait d'OpenMRS n'est
silencieusement perdu en chemin vers le hub. Trois briques :

1. **Upserts idempotents côté hub** clés sur `(site_id, source_uuid)`.
   L'agent peut réémettre le même enregistrement n'importe combien de
   fois sans créer de doublons.
2. **L'outbox SQLite** comme file durable. Chaque extraction y passe ;
   l'agent n'avance son curseur persistant (`sync_state`) qu'une fois la
   page totalement acceptée par le hub. Un crash en milieu de cycle
   déclenche un nouveau renvoi à partir du dernier curseur persisté, pas
   du point où l'extraction s'était arrêtée. La pagination est un
   **keyset composite `(date_changed, id)`** : plus de `batchSize` lignes
   au même `date_changed` (fréquent après une migration de masse OpenMRS)
   ne sont jamais sautées. Voir [docs/keyset-indexes.md](docs/keyset-indexes.md)
   pour les index recommandés sur la base source.
3. **Réessai par enregistrement, distinguant dépendance et validation.**
   Quand le hub répond `accepted=N, rejected=M`, l'agent sépare la page
   par `sourceUuid`. Les lignes acceptées passent en `SENT`. Pour les
   rejetées, deux cas :
   - **rejet de dépendance** (`UNKNOWN_PATIENT` — le patient parent n'est
     pas encore ingéré, simple décalage d'ordonnancement) : la ligne passe
     en `REJECTED` **sans consommer de tentative** et est rejouée à chaque
     cycle jusqu'à ce que le parent existe. Un décalage d'ordre ne produit
     donc jamais de `DEAD_LETTER` définitif.
   - **rejet de validation** (`UPSERT_FAILED`… — donnée invalide) : la
     ligne consomme une tentative et bascule en `DEAD_LETTER` après
     `SIGDEP_MAX_REJECT_ATTEMPTS` (défaut 10).

L'extraction et le push sont **découplés** (pipeline borné par
`SIGDEP_PIPELINE_DEPTH`) : le lot suivant s'extrait pendant que le lot en
cours attend son ACK, sans jamais sur-extraire si le hub est lent.

Le hub enregistre chaque rejet dans `audit.rejected_record`, exposé sur la
page **Synchronisation →
Rejets** de la console : un admin voit l'UUID source exact, le
message d'erreur, et clique « Résoudre » une fois la donnée corrigée.

Transitions d'état :

```
   extraction ──► PENDING ──push──► ┌── accepté (hub) ──► SENT (terminal)
                                     │
                                     └── rejeté (hub) ──► REJECTED
                                                              │
                                                              │ réessai au
                                                              ▼ cycle suivant
                                                          (retour push)
                                                              │
                                                              ▼
                                          tentatives ≥ max ──► DEAD_LETTER
                                                              │
                                                              │ manuel
                                                              ▼ « Résoudre »
                                                          (reste dans l'outbox
                                                           mais l'agent l'ignore)
```

La watermark **n'avance que lorsqu'une page est totalement
acceptée**. Si quoi que ce soit dans la page est rejeté, elle reste
en place : l'extracteur ne dépasse pas la fenêtre courante, les mêmes
enregistrements sont ré-extraits, dédupliqués dans l'outbox (update
in-place sur `source_uuid`), et retentés.

## Opérations

### Surveiller le tampon

```bash
# Schéma et compteurs par statut
sqlite3 /var/lib/sigdep-agent/buffer.sqlite '.schema'
sqlite3 /var/lib/sigdep-agent/buffer.sqlite \
  'SELECT entity_type, status, COUNT(*) FROM outbox GROUP BY entity_type, status;'

# Watermarks
sqlite3 /var/lib/sigdep-agent/buffer.sqlite \
  'SELECT entity_type, last_watermark, last_status FROM sync_state;'

# Inspecter les lignes rejetées (seront retentées automatiquement)
sqlite3 /var/lib/sigdep-agent/buffer.sqlite \
  "SELECT id, entity_type, source_uuid, attempts, substr(last_error, 1, 80) AS err
   FROM outbox WHERE status='REJECTED' ORDER BY attempts DESC LIMIT 20;"

# Inspecter les lignes bloquées (DEAD_LETTER — action manuelle requise
# côté hub)
sqlite3 /var/lib/sigdep-agent/buffer.sqlite \
  "SELECT id, entity_type, source_uuid, attempts, substr(last_error, 1, 80) AS err
   FROM outbox WHERE status='DEAD_LETTER' ORDER BY id LIMIT 20;"
```

### Rapport de réconciliation

Pour repérer d'un coup d'œil une entité anormalement en retard ou jamais
synchronisée, la commande `--reconcile` affiche, par `entity_type` : un
ordre de grandeur des lignes en table source OpenMRS, les compteurs d'outbox
par statut (SENT / PENDING / REJECTED / DEAD_LETTER) et le watermark courant.

```bash
# systemd (l'agent peut rester en marche : lecture seule côté source)
java -jar /opt/sigdep-sync/sigdep-sync.jar --reconcile

# Docker (conteneur jetable, même volume + accès à la base source via .env)
docker run --rm --env-file /opt/sigdep-sync/.env \
  -v sigdep-sync_sigdep-buffer:/var/lib/sigdep-agent \
  "$SIGDEP_SYNC_IMAGE" --reconcile
```

> ⚠️ Le compteur **SOURCE est approximatif** : c'est un `COUNT(*)` brut de la
> table (`getSourceTable()`), sans les filtres propres à chaque extracteur
> (type d'encounter, jointures PTME…) et **sans exclure les lignes `voided`**.
> Il sert à détecter un écart grossier (entité à zéro alors qu'elle devrait
> être peuplée, SENT très en dessous de la source), pas à un rapprochement
> ligne à ligne. La commande ne démarre pas de cycle et s'arrête à la fin.

### Débloquer des lignes `DEAD_LETTER`

Une ligne passe en `DEAD_LETTER` après avoir épuisé ses tentatives sur un
rejet de **validation** (données refusées par le hub — schéma trop court,
contrainte, mapping manquant…). Elle sort alors du flux de retry automatique
et attend une action manuelle. Une fois la **cause corrigée côté hub**
(migration, colonne élargie, mapping ajouté…), remettre ces lignes en file
avec la commande intégrée — elle repart `status=PENDING`, `attempts=0`,
`last_error` conservé comme trace :

```bash
# systemd : arrêter l'agent, requeue, redémarrer
systemctl stop sigdep-sync
# toutes les entités
java -jar /opt/sigdep-sync/sigdep-sync.jar --requeue-dead-letter
# …ou une seule entité
java -jar /opt/sigdep-sync/sigdep-sync.jar --requeue-dead-letter=LAB_RESULTS
systemctl start sigdep-sync

# Docker : one-shot dans un conteneur jetable montant le même volume
docker run --rm --env-file /opt/sigdep-sync/.env \
  -v sigdep-sync_sigdep-buffer:/var/lib/sigdep-agent \
  "$SIGDEP_SYNC_IMAGE" --requeue-dead-letter=LAB_RESULTS
```

La commande **ne démarre pas** de cycle de synchronisation : elle requeue,
journalise le nombre de lignes traitées, puis s'arrête (le scheduler est
désactivé pour ce lancement, donc aucun cycle ne part en concurrence). Elle
passe par le même verrou d'écriture que l'agent : sûre même si le fichier de
tampon est partagé. Elle remplace l'ancien `UPDATE outbox SET status='PENDING'`
lancé à la main via un conteneur `alpine + sqlite3`.

### Forcer une resynchronisation complète

Arrêter l'agent, supprimer le fichier de tampon, redémarrer. L'agent
rejouera tout depuis le début (long sur un gros site — compter
plusieurs heures).

```bash
systemctl stop sigdep-sync
rm /var/lib/sigdep-agent/buffer.sqlite
systemctl start sigdep-sync
```

### Augmenter la taille de lot pour un backfill

Si le site est très en retard et que les 500 enregistrements / cycle
par défaut sont trop lents, monter temporairement `SIGDEP_BATCH_SIZE`
à 20000 et redémarrer. Le hub encaisse sans problème des lots de
plusieurs dizaines de milliers de lignes.

### Rejets « Site code not found »

Le code du site dans `SIGDEP_SITE_CODE` doit correspondre à une ligne
de `core.sites` côté hub. Soit le site n'a pas été initialisé, soit
la valeur est erronée. Ne pas faire créer la ligne par l'agent — les
sites sont des données de référence et vivent dans la migration de
seed du hub.

### Qualité des données (rejets liés à la donnée source)

Certains rejets (`UNKNOWN_PATIENT` persistants, `UPSERT_FAILED`) viennent de la
**qualité de la donnée saisie dans OpenMRS**, pas d'un bug de synchro. Les cas
rencontrés, leur diagnostic, les correctifs et les **requêtes de détection** à
passer sur une base avant déploiement sont recensés dans
[docs/data-quality.md](docs/data-quality.md). À consulter dès qu'un site présente
un pic de rejets, et à compléter (gabarit `DQ-NN`) quand un nouveau cas apparaît.

## Licence

À définir en session plénière avec le HMIS TWG ; aucun fichier de
licence n'est livré pour l'instant. En attendant, considérer le
contenu comme « tous droits réservés par I-TECH Côte d'Ivoire et le
programme PNLS ».
