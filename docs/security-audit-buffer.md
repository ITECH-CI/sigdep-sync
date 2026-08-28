# Audit de sécurité — données au repos sur les postes de site

**Date :** 27 août 2026
**Périmètre :** agent `sigdep-sync` déployé sur les sites (Linux systemd, Docker, Windows/WinSW)
**Objet :** données de santé persistées hors du hub, sur des postes non maîtrisés par l'équipe centrale

> Statut : constats vérifiés sur le code et sur une copie réelle de buffer de production
> (`buffer/buffer.sqlite.bak-20260708-195033`, 80 Mo). Les correctifs ne sont pas encore appliqués.

---

## 1. Résumé

L'agent de synchronisation conserve sur chaque poste de site une copie des données
de santé qu'il transmet au hub. Cette copie est **en clair**, **sans restriction
d'accès sur Windows**, et **sans restriction explicite sur Linux**. Elle croît
indéfiniment : aucun mécanisme de purge n'existe.

Sur la copie de production examinée : **45,6 Mo de payloads déjà acceptés par le hub**,
donc sans aucune utilité opérationnelle, toujours présents sur le poste.

| Sévérité | Constat | Plateforme |
|---|---|---|
| **Élevée** | Buffer et `.env` sous `C:\sigdep-sync\` — ACL héritées de la racine, lisibles par tout utilisateur | Windows |
| **Élevée** | Service Windows sous `LocalSystem` (privilèges maximaux) | Windows |
| **Moyenne** | Aucun `chmod` sur le répertoire buffer à l'installation | Linux |
| **Moyenne** | Aucun chiffrement au repos | Toutes |
| **Moyenne** | Backups `.bak` sans rétention, sans protection, qui circulent | Toutes |
| **Faible** | `logging.level` à `DEBUG` en configuration par défaut | Toutes |

**Point positif :** les journaux applicatifs ne contiennent aucune donnée nominative —
uniquement des UUID et des compteurs. Vérifié sur l'ensemble des appels `log.*`.

---

## 2. Nature des données exposées

Le buffer stocke le **payload canonique** de chaque enregistrement synchronisé.
Extrait réel (identifiants tronqués) :

```json
{"sourceUuid":"06841d5e-…","sex":"F","birthDate":[1963,1,1],
 "identifiers":[{"typeCode":"CODE_ARV","value":"00812/GM/16/…"}]}
```

Sexe, date de naissance et **code ARV** : dans le contexte d'une file active VIH,
la seule présence d'un patient dans ce fichier est en soi une information de santé.

Répartition sur la copie examinée (60 773 lignes) :

| Entité | Lignes |
|---|---|
| `LAB_RESULTS` | 41 608 |
| `VISITS` | 12 301 |
| `SCREENINGS` | 1 344 |
| `TREATMENT_INITIATIONS` | 1 029 |
| `PATIENTS` | 1 007 |
| autres (PTME, clôtures) | 3 484 |

---

## 3. Constats détaillés

### 3.1 — Windows : dossier à la racine de `C:` — **élevé**

`packaging/windows/sigdep-sync.env.example:23` place le buffer dans `C:\sigdep-sync\`.
Le `README.md` d'installation demande de créer ce dossier à la racine.

Un dossier créé à la racine de `C:` hérite des ACL de la racine : le groupe `Users`
y a un accès en lecture. Sont donc lisibles par **tout compte du poste** :

- `buffer.sqlite` — les données de santé ;
- `.env` — la **clé API du site** et le **mot de passe MySQL** ;
- `logs\` — les journaux.

Aucun `icacls` n'est posé nulle part dans le packaging Windows.

> Sur les sites, le poste hébergeant l'agent est souvent un poste partagé
> (saisie, pharmacie). L'hypothèse « un seul utilisateur » ne tient pas.

### 3.2 — Windows : service sous `LocalSystem` — **élevé**

`packaging/windows/sigdep-sync.xml` laisse le service tourner sous `LocalSystem`,
le compte le plus privilégié de Windows. Le bloc `<serviceaccount>` existe mais
est commenté. L'agent n'a besoin que de : lire MySQL local, écrire son buffer,
sortir en HTTPS. `LocalSystem` est très au-delà.

### 3.3 — Linux : permissions du buffer non posées — **moyen**

`packaging/linux/install.sh:42-44` :

```bash
mkdir -p /var/lib/sigdep-sync
chown -R "$RUN_USER:$RUN_USER" /var/lib/sigdep-sync "$INSTALL_DIR"
chmod 640 "$INSTALL_DIR/.env"
```

Le `.env` est protégé, **le buffer non**. Le répertoire hérite du `umask` du shell
root (typiquement `022` → `755`) : le fichier SQLite est lisible par tout compte
du serveur. L'asymétrie est révélatrice — on protège les mots de passe, pas les
dossiers patients.

L'unité systemd (`packaging/systemd/sigdep-sync.service`) ne pose ni `UMask`,
ni aucune directive de durcissement (`ProtectSystem`, `ProtectHome`, `PrivateTmp`).

### 3.4 — Aucun chiffrement au repos — **moyen**

Le SQLite est en clair. `strings buffer.sqlite | grep -i arv` suffit à extraire
les identifiants. Vol du disque, sauvegarde système, copie du fichier : aucune
barrière.

### 3.5 — Backups sans cycle de vie — **moyen**

Un fichier `buffer.sqlite.bak-20260708-195033` de 80 Mo, daté du 8 juillet 2026,
a été retrouvé **sur un poste de développement**. Il contient des données de
production en clair, en `rw-r--r--`.

Ce seul fait démontre le risque : ces copies sont créées par les procédures
d'exploitation, ne sont jamais purgées, et **circulent hors du site**.

### 3.6 — `logging.level` à `DEBUG` — **faible**

`src/main/resources/application.yml:88` fixe `ci.itechciv.sigdep.sync: DEBUG`
par défaut. Aucun log ne trace de payload aujourd'hui — c'est vérifié — mais
la marge est nulle : un futur `log.debug("payload={}", …)` fuiterait directement
dans `logs\` (lisible par tous sur Windows, cf. 3.1).

---

## 4. Ce qui réduit déjà la surface (correctifs prêts, non déployés)

Trois modifications sont écrites et testées (51 tests verts), en attente de décision :

1. **Effacement du payload à l'acceptation** — `markSent` vide `payload_json`.
   Sur la copie examinée : **45,6 Mo sur 45,6 Mo** de payloads `SENT` disparaissent.
2. **Index unique `(entity_type, source_uuid)`** — supprime l'accumulation de copies
   (490 doublons constatés) et empêche la reconstitution d'un historique.
3. **Déduplication au démarrage** — migre les buffers existants, avec `VACUUM`
   (l'espace disque est réellement rendu, la donnée réellement effacée du fichier).

**Limite à retenir :** les lignes `PENDING`/`REJECTED`/`DEAD_LETTER` conservent
nécessairement leur payload — c'est leur raison d'être. Une ligne bloquée en
`DEAD_LETTER` garde donc de la donnée nominative indéfiniment. Ces correctifs
réduisent le volume ; ils ne remplacent pas le contrôle d'accès.

---

## 5. Recommandations

### Priorité 1 — Contrôle d'accès (sans dépendance, applicable immédiatement)

**Windows :**
- Déplacer l'installation de `C:\sigdep-sync\` vers `C:\ProgramData\sigdep-sync\`
  (`.env.example:32` documente déjà ce chemin — le packaging Windows ne le suit pas).
- Poser des ACL explicites à l'installation :
  ```bat
  icacls "%INSTALL_DIR%" /inheritance:r ^
      /grant:r "SYSTEM:(OI)(CI)F" ^
      /grant:r "Administrators:(OI)(CI)F"
  ```
- Activer le compte de service dédié (`<serviceaccount>` de `sigdep-sync.xml`)
  au lieu de `LocalSystem`, et lui accorder l'accès au dossier.

**Linux :**
- `chmod 700` sur le répertoire buffer, `600` sur le fichier.
- `UMask=0077` dans l'unité systemd, plus `ProtectSystem=strict`,
  `ProtectHome=yes`, `PrivateTmp=yes`, `ReadWritePaths=/var/lib/sigdep-sync`.

**Docker :** vérifier les permissions dans le volume nommé et l'utilisateur du conteneur.

### Priorité 2 — Hygiène

- Purger les `.bak` existants, à commencer par celui du poste de développement.
- Documenter une procédure de backup : chiffré, avec rétention, sans sortie du site.
- Passer le niveau de log par défaut à `INFO`, `DEBUG` sur activation explicite.
- Ajouter au `.gitignore` un garde-fou sur `*.bak` (aujourd'hui `*.sqlite` couvre
  le fichier nu, pas ses copies suffixées).

### Priorité 3 — À arbitrer

- **Chiffrement au repos** : SQLCipher (applicatif, portable) ou chiffrement de
  volume (LUKS / BitLocker, transparent). Le coût réel est la **gestion de la clé
  sur chaque site** — à évaluer avant de s'engager.
- **Purge des `DEAD_LETTER` anciennes** : aujourd'hui elles gardent leur payload
  sans limite de durée. Une rétention (avec remontée d'alerte) serait cohérente.

---

## 6. Vérifications à faire sur le terrain

Ces constats viennent du code et d'une copie de buffer. Ils demandent confirmation
sur les sites réels :

```bash
# Linux — permissions effectives
ls -la /var/lib/sigdep-sync/ /opt/sigdep-sync/.env
stat -c '%a %U:%G %n' /var/lib/sigdep-sync/buffer.sqlite

# Volume Docker
docker volume inspect sigdep-buffer
```

```bat
REM Windows — ACL effectives
icacls C:\sigdep-sync
icacls C:\sigdep-sync\.env
sc qc sigdep-sync | findstr SERVICE_START_NAME
```

```bash
# Backups oubliés (tous OS, adapter le point de départ)
find / -name "*.sqlite*bak*" -o -name "buffer.sqlite.*" 2>/dev/null
```

Volumétrie et doublons d'un buffer donné :

```sql
SELECT status, COUNT(*), ROUND(SUM(LENGTH(payload_json))/1024.0/1024,1) AS mb
FROM outbox GROUP BY status;

SELECT COUNT(*) - COUNT(DISTINCT entity_type || source_uuid) AS doublons FROM outbox;
```
