-- YeeHaw back-office schema (PostgreSQL 13+). Safe to run repeatedly.

CREATE TABLE IF NOT EXISTS devices (
  device_id     uuid PRIMARY KEY,
  first_seen    timestamptz NOT NULL DEFAULT now(),
  last_seen     timestamptz NOT NULL DEFAULT now(),
  boot_count    integer     NOT NULL DEFAULT 1,
  app_version   text        NOT NULL,
  android_os_ver text       NOT NULL,
  ram_mb        integer     NOT NULL,
  country       char(2)
);
CREATE INDEX IF NOT EXISTS devices_last_seen_idx ON devices (last_seen);
CREATE INDEX IF NOT EXISTS devices_country_idx   ON devices (country);

-- One row per device per UTC day: the source for DAU / MAU.
CREATE TABLE IF NOT EXISTS sessions (
  id            bigserial PRIMARY KEY,
  device_id     uuid NOT NULL REFERENCES devices (device_id) ON DELETE CASCADE,
  day           date NOT NULL,
  session_count integer NOT NULL DEFAULT 1,
  first_at      timestamptz NOT NULL DEFAULT now(),
  last_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (device_id, day)
);
CREATE INDEX IF NOT EXISTS sessions_day_idx     ON sessions (day);
CREATE INDEX IF NOT EXISTS sessions_last_at_idx ON sessions (last_at DESC);

-- Every publish creates a new immutable version; exactly one is active.
CREATE TABLE IF NOT EXISTS remote_configs (
  id                  bigserial PRIMARY KEY,
  version             integer NOT NULL UNIQUE,
  is_active           boolean NOT NULL DEFAULT false,
  default_search_url  text    NOT NULL,
  affiliate_tag       text    NOT NULL DEFAULT '',
  speed_dial          jsonb   NOT NULL DEFAULT '[]'::jsonb,
  blacklisted_domains text[]  NOT NULL DEFAULT '{}',
  admob_enabled       boolean NOT NULL DEFAULT true,
  note                text,
  created_by          text,
  created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS remote_configs_single_active ON remote_configs (is_active) WHERE is_active;

CREATE TABLE IF NOT EXISTS ad_events (
  id             bigserial PRIMARY KEY,
  device_id      uuid NOT NULL REFERENCES devices (device_id) ON DELETE CASCADE,
  event_type     text NOT NULL CHECK (event_type IN ('impression', 'click', 'paid')),
  ad_format      text NOT NULL,
  ad_unit        text,
  network        text,
  revenue_micros bigint NOT NULL DEFAULT 0 CHECK (revenue_micros >= 0),
  currency       char(3),
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ad_events_created_idx ON ad_events (created_at);
CREATE INDEX IF NOT EXISTS ad_events_type_idx    ON ad_events (event_type, created_at);

CREATE TABLE IF NOT EXISTS admin_audit (
  id     bigserial PRIMARY KEY,
  at     timestamptz NOT NULL DEFAULT now(),
  actor  text NOT NULL,
  action text NOT NULL,
  detail jsonb NOT NULL DEFAULT '{}'::jsonb
);

-- Growth: how the app spreads (QR shown, link shared per channel, APK handed over). No contacts, no message content.
CREATE TABLE IF NOT EXISTS share_events (
  id         bigserial PRIMARY KEY,
  device_id  uuid NOT NULL REFERENCES devices (device_id) ON DELETE CASCADE,
  action     text NOT NULL CHECK (action IN ('qr_shown', 'shared', 'apk_shared')),
  channel    text NOT NULL,
  ref_code   char(8),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS share_events_created_idx ON share_events (created_at);

-- v2 founder console: user control, update policy, ad policy, promos, announcements, health.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS version_code   integer;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS blocked        boolean NOT NULL DEFAULT false;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS blocked_reason text;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS blocked_at     timestamptz;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS notes          text NOT NULL DEFAULT '';
ALTER TABLE devices ADD COLUMN IF NOT EXISTS tags           text[] NOT NULL DEFAULT '{}';
CREATE INDEX IF NOT EXISTS devices_blocked_idx ON devices (blocked) WHERE blocked;
CREATE INDEX IF NOT EXISTS devices_first_seen_idx ON devices (first_seen);

ALTER TABLE remote_configs ADD COLUMN IF NOT EXISTS features      jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE remote_configs ADD COLUMN IF NOT EXISTS update_policy jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE remote_configs ADD COLUMN IF NOT EXISTS ads_policy    jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE remote_configs ADD COLUMN IF NOT EXISTS promos        jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE remote_configs ADD COLUMN IF NOT EXISTS announcement  jsonb;

-- Device-reported operational health and measured savings (deltas since the last report). No URLs, no content.
CREATE TABLE IF NOT EXISTS health_events (
  id         bigserial PRIMARY KEY,
  device_id  uuid NOT NULL REFERENCES devices (device_id) ON DELETE CASCADE,
  kind       text NOT NULL CHECK (kind IN ('crash','freeze','render_rescue','store_repair','skipped_requests','est_bytes_avoided','low_signal_seconds','data_received_bytes')),
  value      bigint NOT NULL CHECK (value >= 0),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS health_events_kind_idx ON health_events (kind, created_at);

CREATE TABLE IF NOT EXISTS entitlements (
  device_id     uuid PRIMARY KEY,
  product_id    text NOT NULL,
  token_hash    text NOT NULL,
  state         text NOT NULL,
  active        boolean NOT NULL,
  expires_at    timestamptz,
  verified_at   timestamptz NOT NULL DEFAULT now()
);

-- Reports from the automated maintenance agents (agents/*.js and any scheduled agent). Written only through
-- POST /v1/agent/report with the separate AGENT_TOKEN; read by the founder console.
CREATE TABLE IF NOT EXISTS agent_reports (
  id         bigserial PRIMARY KEY,
  agent      text NOT NULL CHECK (agent ~ '^[a-z0-9_-]{2,40}$'),
  status     text NOT NULL CHECK (status IN ('ok','warn','fail','fixed')),
  title      text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
  details    jsonb NOT NULL DEFAULT '{}'::jsonb,
  ref        text CHECK (ref IS NULL OR char_length(ref) <= 80),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS agent_reports_agent_idx ON agent_reports (agent, created_at DESC);

-- Agent orchestration: findings (detected problems with a proposed fix), actions (what was done and how it was verified),
-- runs (every orchestrator pass) and a lease so overlapping or duplicate scheduled runs cannot execute together.
CREATE TABLE IF NOT EXISTS agent_findings (
  id          bigserial PRIMARY KEY,
  fingerprint text NOT NULL,
  agent       text NOT NULL,
  severity    text NOT NULL CHECK (severity IN ('info','low','medium','high','critical')),
  title       text NOT NULL,
  evidence    jsonb NOT NULL DEFAULT '{}'::jsonb,
  root_cause  text,
  proposal    text,
  risk        text NOT NULL DEFAULT 'medium' CHECK (risk IN ('none','low','medium','high')),
  repair      text,
  status      text NOT NULL DEFAULT 'open' CHECK (status IN ('open','approved','applied','verified','failed','rejected','dismissed','resolved')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  resolved_at timestamptz
);
CREATE INDEX IF NOT EXISTS agent_findings_status_idx ON agent_findings (status, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS agent_findings_live_idx ON agent_findings (fingerprint) WHERE status IN ('open','approved','applied','failed');

CREATE TABLE IF NOT EXISTS agent_actions (
  id           bigserial PRIMARY KEY,
  finding_id   bigint REFERENCES agent_findings (id) ON DELETE CASCADE,
  actor        text NOT NULL,
  action       text NOT NULL,
  result       text,
  verification text,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS agent_actions_finding_idx ON agent_actions (finding_id, created_at);

CREATE TABLE IF NOT EXISTS agent_runs (
  id          bigserial PRIMARY KEY,
  started_at  timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  ok          boolean,
  summary     jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS agent_lease (
  id    int PRIMARY KEY CHECK (id = 1),
  until timestamptz NOT NULL DEFAULT 'epoch'
);
INSERT INTO agent_lease (id) VALUES (1) ON CONFLICT DO NOTHING;

-- Founder controls for the autonomous engine (one row). Defaults: engine on, every agent on, only the two safe repairs permitted.
CREATE TABLE IF NOT EXISTS engine_settings (
  id               int PRIMARY KEY CHECK (id = 1),
  enabled          boolean NOT NULL DEFAULT true,
  disabled_agents  text[] NOT NULL DEFAULT '{}',
  interval_minutes int NOT NULL DEFAULT 5 CHECK (interval_minutes BETWEEN 1 AND 1440),
  auto_repairs     text[] NOT NULL DEFAULT '{prune_agent_reports,analyze}',
  updated_at       timestamptz NOT NULL DEFAULT now()
);
INSERT INTO engine_settings (id) VALUES (1) ON CONFLICT DO NOTHING;

ALTER TABLE agent_actions ADD COLUMN IF NOT EXISTS evidence jsonb;

-- Every purchase verification attempt and its outcome (verified, pending, invalid, product_mismatch, token_reused, ...).
CREATE TABLE IF NOT EXISTS verify_attempts (
  id         bigserial PRIMARY KEY,
  at         timestamptz NOT NULL DEFAULT now(),
  device_id  uuid,
  outcome    text NOT NULL
);
CREATE INDEX IF NOT EXISTS verify_attempts_at ON verify_attempts (at);
-- A purchase token entitles exactly one device (blocks replay of someone else's token).
CREATE UNIQUE INDEX IF NOT EXISTS entitlements_token_hash_uq ON entitlements (token_hash);

-- The audit trail is append-only: rows cannot be edited or deleted through SQL UPDATE/DELETE, by the app or an ordinary admin session.
CREATE OR REPLACE FUNCTION admin_audit_append_only() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'admin_audit is append-only'; END; $$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS admin_audit_no_change ON admin_audit;
CREATE TRIGGER admin_audit_no_change BEFORE UPDATE OR DELETE ON admin_audit FOR EACH ROW EXECUTE FUNCTION admin_audit_append_only();
