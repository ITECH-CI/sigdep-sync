#!/usr/bin/env bash
#
# reset-agent.sh — purge l'outbox et le watermark de l'agent SIGDEP, puis
# le force à re-extraire l'historique complet depuis openmrs au prochain
# cycle.
#
# Conçu pour le déploiement systemd standard (service `sigdep-sync`,
# buffer SQLite dans /var/lib/sigdep-agent/buffer.sqlite). Détecte
# automatiquement si le service est actif et propose de le stopper /
# redémarrer pour éviter la course avec des écritures en cours.
#
# Usage :
#   sudo ./reset-agent.sh                  # interactif, demande confirmation
#   sudo ./reset-agent.sh --yes            # bypass la confirmation (CI/cron)
#   sudo ./reset-agent.sh --buffer /chemin # cible un fichier non-standard
#   sudo ./reset-agent.sh --no-restart     # ne relance pas le service en fin
#
# Sortie :
#   0  succès
#   1  erreur (fichier absent, sqlite3 manquant, refus utilisateur, ...)
#
set -euo pipefail

BUFFER="${SIGDEP_BUFFER_PATH:-/var/lib/sigdep-agent/buffer.sqlite}"
SERVICE="sigdep-sync"
ASSUME_YES=0
RESTART=1

usage() {
    cat <<EOF
Purge le buffer SQLite de l'agent SIGDEP et force une ré-extraction
complète au prochain cycle.

Options :
  --yes              ne demande pas confirmation
  --buffer <path>    chemin du fichier SQLite (défaut: \$SIGDEP_BUFFER_PATH ou /var/lib/sigdep-agent/buffer.sqlite)
  --no-restart       ne relance pas le service systemd à la fin
  -h, --help         affiche cette aide

Variables d'environnement :
  SIGDEP_BUFFER_PATH  chemin du buffer SQLite (équivalent --buffer)
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --yes)        ASSUME_YES=1 ; shift ;;
        --buffer)     BUFFER="$2"  ; shift 2 ;;
        --no-restart) RESTART=0    ; shift ;;
        -h|--help)    usage ; exit 0 ;;
        *) echo "Option inconnue: $1" >&2 ; usage >&2 ; exit 1 ;;
    esac
done

# --- Pré-requis -------------------------------------------------------------

if ! command -v sqlite3 >/dev/null 2>&1; then
    echo "ERREUR : sqlite3 n'est pas installé." >&2
    echo "  apt install sqlite3   (Debian/Ubuntu)" >&2
    echo "  dnf install sqlite    (Fedora/RHEL)" >&2
    exit 1
fi

if [[ ! -f "$BUFFER" ]]; then
    echo "ERREUR : fichier buffer introuvable : $BUFFER" >&2
    echo "  Vérifie SIGDEP_BUFFER_PATH ou utilise --buffer <chemin>." >&2
    exit 1
fi

# --- État courant ----------------------------------------------------------

echo "Buffer ciblé : $BUFFER"
echo
echo "État actuel :"
sqlite3 "$BUFFER" <<SQL
.headers on
.mode column
SELECT 'outbox' AS table_name, count(*) AS rows, count(*) FILTER (WHERE status = 'PENDING')      AS pending,
                                                  count(*) FILTER (WHERE status = 'REJECTED')     AS rejected,
                                                  count(*) FILTER (WHERE status = 'DEAD_LETTER')  AS dead_letter
FROM outbox
UNION ALL
SELECT 'sync_state', count(*), NULL, NULL, NULL FROM sync_state;
SQL
echo

# --- Détection du service --------------------------------------------------

SERVICE_WAS_ACTIVE=0
if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files | grep -q "^${SERVICE}.service"; then
    if systemctl is-active --quiet "$SERVICE"; then
        SERVICE_WAS_ACTIVE=1
        echo "Service systemd $SERVICE est actif — il sera stoppé pendant le reset."
    fi
fi

# --- Confirmation ----------------------------------------------------------

if [[ $ASSUME_YES -ne 1 ]]; then
    echo
    echo "Ce script va :"
    echo "  1. Stopper le service $SERVICE (si actif)"
    echo "  2. TRUNCATE outbox + sync_state dans $BUFFER"
    if [[ $RESTART -eq 1 && $SERVICE_WAS_ACTIVE -eq 1 ]]; then
        echo "  3. Redémarrer le service $SERVICE"
    fi
    echo
    echo "Au prochain cycle, l'agent re-extraira depuis la watermark initiale"
    echo "(sigdep.sync.watermark-initial dans application.yml) — toute la"
    echo "période sera re-poussée au hub."
    echo
    read -r -p "Confirmer ? [y/N] " ans
    case "$ans" in
        y|Y|yes|YES) ;;
        *) echo "Annulé." ; exit 1 ;;
    esac
fi

# --- Exécution -------------------------------------------------------------

if [[ $SERVICE_WAS_ACTIVE -eq 1 ]]; then
    echo "Stop $SERVICE..."
    systemctl stop "$SERVICE"
fi

echo "Purge outbox + sync_state..."
sqlite3 "$BUFFER" <<'SQL'
DELETE FROM outbox;
DELETE FROM sync_state;
VACUUM;
SQL

echo "Fait. État après reset :"
sqlite3 "$BUFFER" <<SQL
.headers on
.mode column
SELECT 'outbox'     AS table_name, count(*) AS rows FROM outbox
UNION ALL
SELECT 'sync_state', count(*) FROM sync_state;
SQL

if [[ $RESTART -eq 1 && $SERVICE_WAS_ACTIVE -eq 1 ]]; then
    echo "Restart $SERVICE..."
    systemctl start "$SERVICE"
    sleep 1
    systemctl status "$SERVICE" --no-pager --lines=5 || true
fi

echo
echo "OK. Le prochain cycle agent (toutes les ${SIGDEP_SYNC_INTERVAL_MIN:-15} min)"
echo "va re-extraire depuis la source openmrs et repousser au hub."
