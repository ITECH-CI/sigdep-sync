-- SQLite schema for the edge agent local buffer.
-- Applied on startup if tables do not exist.

CREATE TABLE IF NOT EXISTS sync_state (
  entity_type     TEXT PRIMARY KEY,
  last_watermark  TIMESTAMP,
  -- Tie-breaker de keyset pour les entités dont le watermark temporel n'a
  -- qu'une granularité JOUR (screening : pas de date_changed en amont).
  -- Couple (last_watermark, last_id) → curseur strictement progressif, pas
  -- de ré-extraction du jour courant à chaque cycle. NULL / ignoré pour les
  -- entités à watermark fin (patients, visites… qui ont date_changed).
  last_id         INTEGER,
  last_run_at     TIMESTAMP,
  last_status     TEXT,
  records_sent    INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS outbox (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  entity_type   TEXT NOT NULL,
  source_uuid   TEXT NOT NULL,
  -- Clé numérique de la ligne source (tie-breaker de keyset ; NULL si non
  -- applicable). Sert au flusher à avancer sync_state.last_id sur les seules
  -- lignes confirmées par le hub.
  source_id     INTEGER,
  watermark     TIMESTAMP NOT NULL,
  payload_json  TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'PENDING',
  attempts      INTEGER DEFAULT 0,
  last_error    TEXT,
  created_at    TEXT DEFAULT (datetime('now')),
  sent_at       TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_entity ON outbox(status, entity_type, id);
