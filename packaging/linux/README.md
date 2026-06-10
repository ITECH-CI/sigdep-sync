# SIGDEP-3 — Agent de synchronisation (installation Linux)

Cette archive contient **tout le nécessaire** pour faire tourner l'agent
`sigdep-sync` comme **service systemd**, sur un serveur de site Linux (à côté
de la base OpenMRS, ou sur une machine ayant accès à cette base).

**Aucun Java n'est à installer** : un JRE Eclipse Temurin 17 est embarqué.

## Contenu de l'archive

| Fichier                       | Rôle                                                  |
| ----------------------------- | ----------------------------------------------------- |
| `sigdep-sync.jar`             | Agent (fat jar)                                       |
| `jre/`                        | JRE Temurin 17 embarqué (Linux x64)                   |
| `sigdep-sync.service.template`| Unité systemd (chemins complétés par `install.sh`)    |
| `sigdep-sync.env.example`     | Configuration à compléter (devient `.env`)            |
| `install.sh`                  | Installe + active le service (root)                   |
| `uninstall.sh`                | Désinstalle (`--purge` pour tout supprimer)           |

## Prérequis

- Linux avec **systemd** (Ubuntu/Debian/RHEL…), 64-bit.
- Droits **root** (sudo) pour installer le service.
- Accès réseau sortant **HTTPS** vers le hub SIGDEP.
- Base **MySQL OpenMRS** accessible (locale ou LAN), avec un compte lecture seule.

## Installation

```bash
# 1. Extraire l'archive
tar -xzf sigdep-sync-linux-<version>.tar.gz
cd sigdep-sync-linux-<version>

# 2. Installer (crée l'utilisateur système, le service, /opt/sigdep-sync)
sudo ./install.sh

# 3. Configurer
sudo nano /opt/sigdep-sync/.env
#   Au minimum :
#     SIGDEP_SITE_CODE        identifiant du site (core.sites du hub)
#     SIGDEP_LOCAL_DB_*       connexion MySQL OpenMRS (lecture seule)
#     SIGDEP_CENTRAL_API_URL  URL du hub (https://…)
#     SIGDEP_API_KEY          clé générée sur la console (page Sites)

# 4. Démarrer
sudo systemctl start sigdep-sync
```

## Vérification

```bash
systemctl status sigdep-sync          # actif (running)
journalctl -u sigdep-sync -f          # logs en direct
#   → "Agent configured for site '<code>'" puis "Sync cycle started"
```

## Mise à jour

Extraire la nouvelle archive et relancer `sudo ./install.sh` : le jar et le JRE
sont remplacés, le `.env` existant est **conservé**, le service redémarre.

```bash
sudo systemctl restart sigdep-sync
```

## Désinstallation

```bash
sudo ./uninstall.sh           # retire le service ; garde .env + buffer
sudo ./uninstall.sh --purge   # supprime TOUT (config, buffer, utilisateur)
```

## Dépannage

| Symptôme | Piste |
| --- | --- |
| `status` = failed au démarrage | `journalctl -u sigdep-sync -n 50` ; souvent un `.env` incomplet |
| `Access denied` MySQL | vérifier `SIGDEP_LOCAL_DB_*` et les droits du compte lecture |
| 401 vers le hub | `SIGDEP_API_KEY` invalide/expirée → en régénérer une sur la console |
| Rien ne remonte | vérifier `SIGDEP_CENTRAL_API_URL` (HTTPS) et l'accès réseau sortant |
