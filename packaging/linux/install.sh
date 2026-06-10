#!/usr/bin/env bash
# Installeur de l'agent SIGDEP-3 (Linux, systemd). À lancer DEPUIS le dossier
# extrait de l'archive, en root (sudo). Aucun Java requis : le JRE est embarqué.
#
#   sudo ./install.sh
#
# Idempotent : relançable pour mettre à jour (réinstalle le service, conserve
# le .env existant).
set -euo pipefail

INSTALL_DIR="/opt/sigdep-sync"
RUN_USER="sigdep-sync"
SERVICE_NAME="sigdep-sync"
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"

if [[ $EUID -ne 0 ]]; then
  echo "ERREUR : lancez ce script en root (sudo ./install.sh)." >&2
  exit 1
fi

echo "==> Installation de l'agent SIGDEP-3 dans $INSTALL_DIR"

# 1. Utilisateur système dédié (sans login).
if ! id "$RUN_USER" &>/dev/null; then
  useradd --system --no-create-home --shell /usr/sbin/nologin "$RUN_USER"
  echo "    utilisateur système '$RUN_USER' créé"
fi

# 2. Copier les fichiers (jar + JRE), SANS écraser un .env déjà configuré.
mkdir -p "$INSTALL_DIR"
cp -f "$SRC_DIR/sigdep-sync.jar" "$INSTALL_DIR/"
rm -rf "$INSTALL_DIR/jre"
cp -r "$SRC_DIR/jre" "$INSTALL_DIR/"
if [[ ! -f "$INSTALL_DIR/.env" ]]; then
  cp "$SRC_DIR/sigdep-sync.env.example" "$INSTALL_DIR/.env"
  echo "    .env créé depuis l'exemple — À COMPLÉTER avant de démarrer"
else
  echo "    .env existant conservé"
fi

# 3. Dossier de buffer (SQLite) inscriptible par l'agent.
mkdir -p /var/lib/sigdep-sync
chown -R "$RUN_USER:$RUN_USER" /var/lib/sigdep-sync "$INSTALL_DIR"
chmod 640 "$INSTALL_DIR/.env"

# 4. Installer l'unité systemd (placeholders remplacés).
sed -e "s|@INSTALL_DIR@|$INSTALL_DIR|g" \
    -e "s|@RUN_USER@|$RUN_USER|g" \
    "$SRC_DIR/sigdep-sync.service.template" \
    > "/etc/systemd/system/${SERVICE_NAME}.service"

systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null

cat <<EOF

==> Agent installé.

Prochaines étapes :
  1. Éditez la configuration :   sudo nano $INSTALL_DIR/.env
       (au minimum : SIGDEP_SITE_CODE, SIGDEP_LOCAL_DB_*, SIGDEP_CENTRAL_API_URL,
        SIGDEP_API_KEY — la clé générée sur la console, page Sites)
  2. Démarrez le service :       sudo systemctl start $SERVICE_NAME
  3. Vérifiez :                  systemctl status $SERVICE_NAME
                                 journalctl -u $SERVICE_NAME -f

Pour désinstaller :              sudo ./uninstall.sh
EOF
