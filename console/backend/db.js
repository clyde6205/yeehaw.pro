'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { Pool } = require('pg');

function makePool(url = process.env.DATABASE_URL) {
  if (!url) throw new Error('DATABASE_URL is not set');
  const local = /@(localhost|127\.0\.0\.1)(:|\/)/.test(url);
  // TLS is configured explicitly (verified certificates). Provider query flags such as sslmode and
  // channel_binding are removed so they cannot silently weaken or change that.
  const u = new URL(url);
  ['sslmode', 'channel_binding', 'sslrootcert', 'sslcert', 'sslkey'].forEach((k) => u.searchParams.delete(k));
  const ssl = process.env.PGSSL === 'disable' || local ? false : { rejectUnauthorized: true };
  return new Pool({
    connectionString: u.toString(), ssl, max: Number(process.env.PG_POOL_MAX || 10),
    idleTimeoutMillis: 10000, connectionTimeoutMillis: 8000,
  });
}

const DEFAULT_CONFIG = {
  default_search_url: 'https://duckduckgo.com/?q={query}',
  affiliate_tag: '',
  speed_dial: [],
  blacklisted_domains: [],
  admob_enabled: false,
};

async function migrate(pool) {
  await pool.query(fs.readFileSync(path.join(__dirname, 'schema.sql'), 'utf8'));
  const { rows } = await pool.query('SELECT 1 FROM remote_configs LIMIT 1');
  if (!rows.length) {
    await pool.query(
      `INSERT INTO remote_configs (version, is_active, default_search_url, affiliate_tag, speed_dial, blacklisted_domains, admob_enabled, note, created_by)
       VALUES (1, true, $1, $2, $3::jsonb, $4, $5, 'initial default', 'system')`,
      [DEFAULT_CONFIG.default_search_url, DEFAULT_CONFIG.affiliate_tag, JSON.stringify(DEFAULT_CONFIG.speed_dial),
        DEFAULT_CONFIG.blacklisted_domains, DEFAULT_CONFIG.admob_enabled]
    );
  }
}

module.exports = { makePool, migrate };
