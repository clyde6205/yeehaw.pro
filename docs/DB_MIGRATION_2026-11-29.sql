-- Additive, idempotent. Safe to run more than once. Applies the Play-verification attempt log, one-device-per-purchase-token,
-- and append-only audit protection. Run on the production Neon database "yeehaw" (SQL Editor in the Neon console), then redeploy yeehaw-api.
CREATE TABLE IF NOT EXISTS verify_attempts (id bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now(), device_id uuid, outcome text NOT NULL);
CREATE INDEX IF NOT EXISTS verify_attempts_at ON verify_attempts (at);
CREATE UNIQUE INDEX IF NOT EXISTS entitlements_token_hash_uq ON entitlements (token_hash);
CREATE OR REPLACE FUNCTION admin_audit_append_only() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'admin_audit is append-only'; END; $$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS admin_audit_no_change ON admin_audit;
CREATE TRIGGER admin_audit_no_change BEFORE UPDATE OR DELETE ON admin_audit FOR EACH ROW EXECUTE FUNCTION admin_audit_append_only();
