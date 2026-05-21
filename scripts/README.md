# Scripts opérationnels — agent SIGDEP

## `reset-agent.sh`

Purge le buffer SQLite (outbox + sync_state) de l'agent et le force à
re-extraire l'historique complet depuis openmrs au prochain cycle.

### Quand l'utiliser

- Après un reset du hub (les watermarks de l'agent référencent des IDs
  côté hub qui n'existent plus).
- Si l'outbox est dans un état incohérent (rejets DEAD_LETTER nombreux,
  données corrompues localement).
- En phase d'intégration / de test, pour rejouer un cycle propre.

### Quand **ne pas** l'utiliser

- Pour rattraper un site simplement bloqué : si l'outbox a juste pris du
  retard, l'agent rattrape de lui-même au cycle suivant.
- En production, sans avoir prévenu les équipes : un reset complet peut
  saturer le hub avec plusieurs milliers de records repoussés d'un coup.

### Usage

```bash
# Mode interactif (recommandé) — affiche l'état avant et demande confirmation
sudo ./reset-agent.sh

# Non-interactif (cron, CI, ansible)
sudo ./reset-agent.sh --yes

# Buffer à un chemin non standard
sudo ./reset-agent.sh --buffer /opt/sigdep/buffer.sqlite

# Ne pas relancer systemd à la fin (debug)
sudo ./reset-agent.sh --no-restart
```

### Ce que le script fait

1. Vérifie que `sqlite3` est installé et que le fichier buffer existe.
2. Affiche l'état courant (rows par status : PENDING / REJECTED /
   DEAD_LETTER).
3. Détecte si le service `sigdep-sync` est actif via systemd.
4. Demande confirmation (sauf `--yes`).
5. Stoppe le service `sigdep-sync` si actif.
6. `DELETE FROM outbox; DELETE FROM sync_state; VACUUM;`
7. Affiche l'état après reset.
8. Redémarre le service (sauf `--no-restart`).

Le script est idempotent : le re-lancer sur un buffer déjà purgé ne fait
qu'afficher des zéros et redémarrer le service.

### Variables d'environnement reconnues

| Variable                | Défaut                                       | Rôle                          |
| ----------------------- | -------------------------------------------- | ----------------------------- |
| `SIGDEP_BUFFER_PATH`    | `/var/lib/sigdep-agent/buffer.sqlite`        | Chemin du fichier SQLite      |

### Que se passe-t-il après ?

L'agent reprend au cycle suivant (par défaut toutes les 15 min,
configurable via `sigdep.sync.interval-ms`). Il re-extrait depuis la
watermark initiale (`sigdep.sync.watermark-initial`, généralement
`1970-01-01T00:00:00`) et ré-envoie tous les records éligibles au hub.

Le hub upsert sur `(site_id, source_uuid)` : ré-envoyer le même record
deux fois ne crée pas de doublon, ça remet juste à jour la ligne
existante.

### Surveillance pendant le rattrapage

Côté hub, suivre `audit.sync_batch` pour voir les batches arriver :

```sql
SELECT entity_type, count(*) AS batches, sum(accepted) AS records
FROM audit.sync_batch
WHERE site_id = <l'id du site>
  AND started_at >= NOW() - INTERVAL '1 hour'
GROUP BY entity_type;
```

Côté agent, `journalctl -u sigdep-sync -f` montre les logs en direct.

### Voir aussi

- `sigdep-hub/infra/scripts/reset-hub.sh` — équivalent côté hub
  (TRUNCATE des tables `core.*` et `audit.*` sans toucher aux
  référentiels).
- `sigdep-sync/README.md` — documentation générale de l'agent.
