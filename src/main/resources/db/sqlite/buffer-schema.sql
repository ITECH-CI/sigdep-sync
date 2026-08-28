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
  -- Clé numérique de la ligne source (tie-breaker de keyset, NULL si non
  -- applicable). Sert au flusher à avancer sync_state.last_id sur les seules
  -- lignes confirmées par le hub.
  source_id     INTEGER,
  watermark     TIMESTAMP NOT NULL,
  payload_json  TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'PENDING',
  attempts      INTEGER DEFAULT 0,
  last_error    TEXT,
  created_at    TEXT DEFAULT (datetime('now')),
  sent_at       TEXT,
  -- Date de bascule en DEAD_LETTER (NULL sinon). Sert à la purge par
  -- rétention : une ligne DEAD_LETTER porte encore son payload (données de
  -- santé, jamais envoyé) et doit être supprimée après N jours. Distincte de
  -- created_at, que l'UPSERT ne remet pas à jour lors d'une ré-extraction.
  dead_lettered_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_entity ON outbox(status, entity_type, id);

-- Une SEULE ligne par enregistrement source, quel que soit le nombre de
-- ré-extractions. Sans cet index, enqueueBatch INSERT une nouvelle ligne
-- chaque fois que la précédente est déjà SENT : le buffer accumulait
-- indéfiniment des copies du même payload (donnée de santé) sur le poste.
-- Voir BufferSchemaInitializer#dedupeOutbox pour la migration des bases
-- existantes, qui doit tourner AVANT la création de cet index.
CREATE UNIQUE INDEX IF NOT EXISTS ux_outbox_entity_uuid ON outbox(entity_type, source_uuid);

-- Purge des payloads : sent_at porte la date d'acceptation par le hub et
-- payload_json est vidé dès markSent (plus aucune donnée de santé au repos
-- pour une ligne déjà transmise).
CREATE INDEX IF NOT EXISTS idx_outbox_sent_at ON outbox(status, sent_at);

-- Purge par rétention des DEAD_LETTER : une ligne rejetée en validation
-- maxRejectAttempts fois garde son payload (donnée de santé) indéfiniment.
-- On la supprime après N jours en DEAD_LETTER (cf. DeadLetterPurgeRunner).
CREATE INDEX IF NOT EXISTS idx_outbox_dead_lettered_at ON outbox(status, dead_lettered_at);
