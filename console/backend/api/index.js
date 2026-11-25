'use strict';
// Vercel serverless entry: one lazily-created Express app per warm instance.
const { makePool } = require('../db');
const { createApp } = require('../server');

let app;
module.exports = (req, res) => {
  if (!app) {
    app = createApp({
      pool: makePool(),
      adminToken: process.env.ADMIN_TOKEN,
      signingKeyPem: process.env.CONFIG_SIGNING_KEY_PEM,
      trustProxy: Number(process.env.TRUST_PROXY ?? 1),
    });
  }
  return app(req, res);
};
