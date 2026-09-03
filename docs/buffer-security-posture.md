# Posture de sécurité — données au repos dans le buffer de l'agent

**Statut :** décision arrêtée (28/08/2026).
**Portée :** buffer local de `sigdep-sync` sur les postes de site (Linux/systemd, Docker, Windows/WinSW).
**Complète :** `security-audit-buffer.md` (constats et priorités).

Ce document acte ce qui est protégé, ce qui reste exposé **et assumé**, et
pourquoi on ne va pas plus loin sur le chiffrement au repos pour l'instant.

---

## 1. Décision

Le buffer reste en **SQLite**, protégé par le **contrôle d'accès du système de
fichiers** (permissions/ACL) et par la **réduction du volume au repos** (purges).
On **ne chiffre pas** le contenu au niveau applicatif, et on **ne migre pas** le
buffer vers un serveur (MySQL). On documente la posture et on avance.

Cette décision est révisable si une **exigence réglementaire explicite** impose
le chiffrement au repos, ou si le parc devient homogène (matériel garantissant
un chiffrement de volume transparent).

---

## 2. Ce qui est protégé (fait)

| Mesure | Effet | Réf. |
|---|---|---|
| **Purge des payloads acceptés** | Dès que le hub accepte une ligne, son `payload_json` est vidé : plus aucune donnée de santé au repos pour un enregistrement transmis. | #19 |
| **Purge des DEAD_LETTER par rétention** | Une ligne rejetée en validation est supprimée après N jours (défaut 60) : sa donnée de santé, jamais transmise, ne reste pas indéfiniment. | #21 |
| **Contrôle d'accès (P1)** | Buffer + `.env` non lisibles par les autres comptes du poste : Linux `700`/`600` + durcissement systemd, Windows `icacls` (SYSTEM/Admins), Docker `700`, garde applicative `rwx------`. | #20 |
| **Log par défaut `INFO`** | Évite qu'un futur log de payload fuite dans `logs/`. | #20 |

Effet combiné : à un instant donné, **seules les lignes `PENDING`/`REJECTED`
récentes portent un payload**, et le fichier n'est lisible que par le compte de
service. Le gros de l'exposition historique (copies accumulées, SENT conservés,
DEAD_LETTER éternelles, fichier world-readable) est **fermé**.

---

## 3. Ce qui reste exposé — et assumé

**Le buffer SQLite est en clair au repos.** `strings buffer.sqlite` sur les
lignes encore porteuses d'un payload révèle sexe, date de naissance, code ARV.

Risque résiduel réel, après §2 :

- **Vol du disque / de la machine, à froid** (éteinte) : les fichiers sont
  lisibles hors de tout contrôle d'accès de l'OS.
- **Copies/backups du buffer** qui sortent du site (cf. l'audit : un `.bak` de
  production retrouvé en clair sur un poste de développement).

**N'est PAS un risque couvert par un quelconque chiffrement** : l'accès à une
**machine allumée** avec les droits du service. Là, seul le contrôle d'accès
(P1) joue — le service doit pouvoir lire son buffer, donc tout ce qui a ses
droits le peut aussi.

---

## 4. Pourquoi pas de chiffrement au repos (pour l'instant)

### Le modèle de SQLite

SQLite n'est **pas un serveur** : la base est un **fichier** ouvert directement
par le processus, sans gardien. Un « utilisateur/mot de passe » vérifié par la
bibliothèque serait contournable en ouvrant le fichier avec un autre outil
(`sqlite3`, éditeur hexa). C'est pourquoi SQLite n'offre **que** deux protections
réelles : les **permissions du système de fichiers** (← P1) et le **chiffrement
du contenu** (SQLCipher, ou chiffrement de volume). Ce n'est pas un oubli, c'est
cohérent avec ce qu'est SQLite.

### Le mur commun à toutes les options : la clé

Tout chiffrement (SQLCipher, AES applicatif, volume) bute sur la même question :
**où stocker le secret de déchiffrement sur un poste non maîtrisé, sans
opérateur ?**

- **Secret sur le poste** (`.env`, registre en clair) → ne protège que le vol à
  froid, et l'accès machine-allumée reste ouvert. Gain marginal sur P1.
- **Secret fourni au démarrage par le hub** → la clé n'est jamais au repos, mais
  chantier lourd (protocole, rotation, comportement si le hub est injoignable au
  boot) et fragile. Non justifié aujourd'hui.

### Options écartées et raisons

| Option | Raison de l'écart |
|---|---|
| **Chiffrement de volume** (BitLocker/LUKS) | BitLocker exige Windows Pro/Enterprise + TPM ; **le parc n'est pas garanti** (Windows Home possible). LUKS/TPM2 = dépendances matérielles et fragilité au boot. Non uniforme sur un parc hétérogène. |
| **SQLCipher** | Driver JDBC natif à packager par OS/arch (Windows + Linux + Docker) ; + gestion de clé. Coût élevé, dépendance native. |
| **Chiffrement applicatif du payload (AES-GCM/JCE)** | Portable et sans dépendance native, mais la clé retombe dans `.env` (protège le vol à froid seulement) → gain limité sur P1 pour un coût de dev non nul. |
| **Registre Windows / DPAPI** | Bon sur Windows, mais **Windows-only** : rien d'équivalent gratuit sur Linux/Docker → fragmente la solution. Ne couvre pas la machine allumée. |
| **Buffer sur MySQL local** (compte + mot de passe) | Apporte le contrôle d'accès par compte, portable — mais les identifiants restent dans `.env` (mur inchangé), **ajoute une dépendance** (perte du découplage : le buffer SQLite survit aujourd'hui à une panne MySQL et est reconstructible), et impose une **réécriture** de la couche buffer (dialecte SQL, UPSERT, pas de VACUUM). Coût le plus élevé pour un gain net modéré. |

Conclusion : après P1 + purges, le risque résiduel est le **vol à froid** ; aucun
mécanisme de chiffrement **simple, portable et sans gestion de clé** ne le couvre
sur ce parc. Le rapport coût/gain ne justifie pas de l'implémenter maintenant.

---

## 5. Recommandations (non contraignantes)

À appliquer **là où c'est possible**, en défense supplémentaire — sans en faire
un prérequis bloquant :

1. **Chiffrement de volume** sur les postes qui le permettent : BitLocker (TPM)
   sur Windows Pro/Enterprise, LUKS sur les hôtes Linux/Docker. Transparent pour
   l'agent, couvre buffer + `.env` + logs. Prévoir l'**archivage des clés de
   récupération** (sinon perte de données au moindre reset TPM).
2. **Hygiène des backups** — le risque concret le moins cher à réduire :
   - ne jamais sortir un `.bak` de buffer du site en clair ;
   - purger les `.bak` existants (à commencer par celui du poste de dev cité
     dans l'audit) ;
   - le `.gitignore` couvre déjà `*.bak`, `*.sqlite.*`, `-wal`, `-shm` (#19).
3. **MySQL InnoDB encryption** si le buffer devait un jour passer sur MySQL
   (hors décision actuelle) : chiffrement au repos côté serveur, au prix du
   keyring MySQL à gérer.

---

## 6. Résumé pour un décideur

- Les données de santé ne sont **plus accumulées** ni **conservées après
  transmission** ; le fichier n'est **lisible que par le compte de service**.
- Le buffer reste **en clair au repos** : un disque/backup volé à froid est
  lisible. C'est **assumé**, faute d'un mécanisme de chiffrement simple et
  portable sur un parc hétérogène sans opérateur sur site.
- La voie recommandée pour aller plus loin est le **chiffrement de volume là où
  le matériel le permet** et l'**hygiène des backups**, pas un chiffrement
  applicatif dans l'agent.
