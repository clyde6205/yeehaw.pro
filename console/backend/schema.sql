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
