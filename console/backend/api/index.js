'use strict';
// Vercel serverless entry: one lazily-created Express app per warm instance.
const { makePool } = require('../db');
const { createApp } = require('../server');
const { makePlayVerifier } = require('../lib/play');

let app;
module.exports = (req, res) => {
  if (!app) {
    app = createApp({
      pool: makePool(),
      adminToken: process.env.ADMIN_TOKEN,
      signingKeyPem: process.env.CONFIG_SIGNING_KEY_PEM,
      trustProxy: Number(process.env.TRUST_PROXY ?? 1),
      playVerify: makePlayVerifier({ serviceAccountJson: process.env.PLAY_SERVICE_ACCOUNT_JSON, packageName: process.env.PLAY_PACKAGE_NAME || 'app.yehaw.browser' }),
    });
  }
  return app(req, res);
};
