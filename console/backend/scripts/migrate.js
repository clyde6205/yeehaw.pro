#!/usr/bin/env node
'use strict';
const { migrate, makePool } = require('../db');
(async () => {
  const pool = makePool();
  try { await migrate(pool); console.log('Schema is up to date.'); }
  finally { await pool.end(); }
})().catch((e) => { console.error(e); process.exit(1); });
