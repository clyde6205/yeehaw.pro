-- Yehaw rewards foundation.
-- Apply with the production migration runner after DATABASE_URL is configured.
-- No token is minted and no private keys are stored by this schema.

CREATE TABLE IF NOT EXISTS yehaw_reward_events (
  id BIGSERIAL PRIMARY KEY,
  user_id TEXT NOT NULL,
  event_type TEXT NOT NULL CHECK (event_type IN ('verified_reading_session')),
  event_id TEXT NOT NULL,
  idempotency_key CHAR(64) NOT NULL UNIQUE,
  points INTEGER NOT NULL CHECK (points > 0 AND points <= 10),
  policy_version INTEGER NOT NULL CHECK (policy_version > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, event_type, event_id)
);

CREATE INDEX IF NOT EXISTS yehaw_reward_events_user_day_idx
  ON yehaw_reward_events (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS yehaw_wallet_links (
  id BIGSERIAL PRIMARY KEY,
  user_id TEXT NOT NULL,
  chain TEXT NOT NULL CHECK (chain IN ('solana', 'base')),
  wallet_address TEXT NOT NULL,
  challenge_nonce_hash CHAR(64) NOT NULL,
  challenge_expires_at TIMESTAMPTZ NOT NULL,
  verified_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (chain, wallet_address),
  UNIQUE (user_id, chain)
);

CREATE TABLE IF NOT EXISTS yehaw_chain_transactions (
  id BIGSERIAL PRIMARY KEY,
  user_id TEXT NOT NULL,
  chain TEXT NOT NULL CHECK (chain IN ('solana', 'base')),
  transaction_id TEXT NOT NULL,
  transaction_type TEXT NOT NULL CHECK (transaction_type IN ('claim', 'transfer', 'bridge')),
  status TEXT NOT NULL CHECK (status IN ('submitted', 'confirmed', 'failed', 'reversed')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  confirmed_at TIMESTAMPTZ,
  UNIQUE (chain, transaction_id)
);
