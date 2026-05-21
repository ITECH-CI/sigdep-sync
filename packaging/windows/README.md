# SIGDEP-3 Edge Sync Agent — Installation Windows

Ce dossier contient tout ce qu'il faut pour exécuter `sigdep-sync`
comme service Windows natif. Il est destiné à être assemblé en archive
ZIP par la CI à chaque tag de version (voir
`.github/workflows/release.yml`) ; l'archive téléchargée par le site
contient en plus :

- `sigdep-sync-service.exe` — binaire WinSW renommé
- `sigdep-sync.jar` — fat jar de l'agent
- `jre/` — Eclipse Temurin 17 JRE embarqué (aucun Java à installer sur le poste)

## Contenu

| Fichier                       | Rôle                                               |
| ----------------------------- | -------------------------------------------------- |
| `sigdep-sync.xml`             | Configuration WinSW : nom du service, ligne de commande Java, logs, restart policy |
| `sigdep-sync.env.example`     | Variables d'environnement à compléter (à renommer en `.env`) |
| `install-service.bat`         | Installe et démarre le service Windows (admin requis) |
| `uninstall-service.bat`       | Arrête et désinstalle le service                   |

## Prérequis

- Windows 10 / 11 / Server 2016+ (64-bit).
- **Droits administrateur** sur le poste (pour installer le service).
- Accès réseau sortant vers le hub SIGDEP et son Keycloak.
- MySQL OpenMRS accessible localement ou via le LAN.

Aucun Java n'est à installer : le JRE est embarqué dans l'archive.

## Procédure

1. **Extraire l'archive** dans un chemin permanent, par exemple :

   ```
   C:\sigdep-sync\
   ```

   Évitez `Program Files` (écriture restreinte, complique les logs et
   le buffer SQLite).

2. **Copier le fichier `.env`** :

   ```
   copy sigdep-sync.env.example .env
   ```

3. **Éditer le `.env`** avec Notepad ou un éditeur. Renseignez au
   minimum :

   - `SIGDEP_SITE_CODE` — code du site, fourni par l'équipe SIGDEP
   - `SIGDEP_LOCAL_DB_PASSWORD` — mot de passe du compte MySQL lecture seule
   - `SIGDEP_CENTRAL_API_URL` — URL publique du hub
   - `SIGDEP_KEYCLOAK_CLIENT_SECRET` — secret OIDC fourni par l'équipe SIGDEP

4. **Lancer l'installation** (clic droit sur le fichier
   → « Exécuter en tant qu'administrateur ») :

   ```
   install-service.bat
   ```

   Le script vérifie la présence du `.env`, installe le service
   nommé `sigdep-sync`, le démarre, et affiche son statut.

5. **Vérifier les logs** :

   ```
   type logs\sigdep-sync.out.log
   ```

   Vous devez voir, dans les premières secondes :

   ```
   INFO  Sync cycle started. N extractor(s) registered.
   INFO  Enqueued X records (...)
   INFO  Flushed Y batch(es) for ENTITY ...
   ```

6. **Côté hub**, demander à un admin de vérifier que le site apparaît
   dans `/app/sites` avec un statut « En ligne », et que `/app/sync`
   liste des batches récents.

## Gestion du service

Toutes les commandes ci-dessous se lancent dans un terminal cmd
ouvert **en administrateur**, depuis le dossier d'installation :

| Action                  | Commande                                  |
| ----------------------- | ----------------------------------------- |
| Démarrer                | `sigdep-sync-service.exe start`           |
| Arrêter                 | `sigdep-sync-service.exe stop`            |
| Redémarrer              | `sigdep-sync-service.exe restart`         |
| Statut                  | `sigdep-sync-service.exe status`          |
| Désinstaller            | `uninstall-service.bat`                   |
| Suivre les logs (live)  | `Get-Content -Wait logs\sigdep-sync.out.log` (PowerShell) |

Le service apparaît aussi dans **services.msc** sous le nom
« SIGDEP-3 Edge Sync Agent ».

## Mise à jour

1. Télécharger la nouvelle archive ZIP depuis la page Releases
   du dépôt `sigdep-sync`.
2. Arrêter le service :
   ```
   sigdep-sync-service.exe stop
   ```
3. Remplacer **uniquement** `sigdep-sync.jar` et le dossier `jre\` par
   ceux de la nouvelle archive. **Garder** votre `.env` et `buffer.sqlite`.
4. Redémarrer :
   ```
   sigdep-sync-service.exe start
   ```

Le buffer SQLite et la watermark sont préservés : l'agent reprend
exactement où il en était.

## En cas de problème

### Le service ne démarre pas

- Consultez `logs\sigdep-sync.err.log` (stderr Java) et
  `logs\sigdep-sync.out.log` (stdout).
- Le service ne supporte pas un `.env` en encodage UTF-16 (par défaut
  de Notepad « Save as Unicode »). Sauvegardez en **UTF-8** ou en
  ANSI ; pas d'accents non plus dans les valeurs.

### Pas de batch reçu côté hub

- Tester la connectivité depuis le poste :
  ```
  curl -fsSL %SIGDEP_CENTRAL_API_URL%/actuator/health
  curl -fsSL %SIGDEP_KEYCLOAK_URL%/realms/sigdep
  ```
- Tester MySQL avec le client en ligne (s'il est installé sur le poste) :
  ```
  mysql.exe -h <host> -u sigdep_reader -p openmrs -e "SELECT 1"
  ```
  Si `mysql.exe` n'est pas disponible, lancer l'agent à la main pour
  voir la stacktrace de connexion :
  ```
  jre\bin\java.exe -jar sigdep-sync.jar
  ```
  (Ctrl+C pour arrêter ; les erreurs JDBC sont explicites.)

### Beaucoup de rejets

Voir [`docs/user-guide/admin/investiguer-rejets.md`](../../../sigdep-hub/docs/user-guide/admin/investiguer-rejets.md)
(côté hub).

### Reset complet de l'agent

```
sigdep-sync-service.exe stop
del buffer.sqlite
sigdep-sync-service.exe start
```

L'agent re-extraira depuis la watermark initiale au prochain cycle.

## Voir aussi

- `sigdep-sync/README.md` — documentation générale de l'agent
- `sigdep-hub/docs/user-guide/deploiement/installer-agent.md` — guide
  d'installation pour les 3 modes (systemd, Docker, Windows)
