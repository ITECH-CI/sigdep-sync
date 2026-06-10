#!/usr/bin/env bash
# Désinstalle l'agent SIGDEP-3 (Linux/systemd). En root.
#   sudo ./uninstall.sh            # garde /opt/sigdep-sync/.env et le buffer
#   sudo ./uninstall.sh --purge    # supprime TOUT (config + buffer)
set -euo pipefail

INSTALL_DIR="/opt/sigdep-sync"
RUN_USER="sigdep-sync"
SERVICE_NAME="sigdep-sync"

if [[ $EUID -ne 0 ]]; then
  echo "ERREUR : lancez ce script en root (sudo ./uninstall.sh)." >&2
  exit 1
fi

echo "==> Arrêt et désactivation du service $SERVICE_NAME"
systemctl stop "$SERVICE_NAME" 2>/dev/null || true
systemctl disable "$SERVICE_NAME" 2>/dev/null || true
rm -f "/etc/systemd/system/${SERVICE_NAME}.service"
systemctl daemon-reload

if [[ "${1:-}" == "--purge" ]]; then
  echo "==> Purge complète (config + buffer + utilisateur)"
  rm -rf "$INSTALL_DIR" /var/lib/sigdep-sync
  userdel "$RUN_USER" 2>/dev/null || true
else
  echo "==> Binaire/JRE retirés ; .env et buffer CONSERVÉS"
  rm -f "$INSTALL_DIR/sigdep-sync.jar"
  rm -rf "$INSTALL_DIR/jre"
fi

echo "==> Désinstallation terminée."
