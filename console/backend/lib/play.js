'use strict';
// Google Play Developer API client for subscription verification, with no extra dependency.
// Needs PLAY_SERVICE_ACCOUNT_JSON (a service account invited in Play Console with "View financial data" permission).
const crypto = require('node:crypto');

const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const b64u = (b) => Buffer.from(b).toString('base64url');

function makePlayVerifier({ serviceAccountJson, packageName, fetchImpl = fetch }) {
  if (!serviceAccountJson) return null;
  const sa = typeof serviceAccountJson === 'string' ? JSON.parse(serviceAccountJson) : serviceAccountJson;
  let token = null, tokenExp = 0;

  async function accessToken() {
    if (token && Date.now() < tokenExp - 60_000) return token;
    const now = Math.floor(Date.now() / 1000);
    const head = b64u(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
    const claim = b64u(JSON.stringify({ iss: sa.client_email, scope: SCOPE, aud: sa.token_uri || 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 }));
    const sig = crypto.sign('RSA-SHA256', Buffer.from(head + '.' + claim), sa.private_key).toString('base64url');
    const r = await fetchImpl(sa.token_uri || 'https://oauth2.googleapis.com/token', {
      method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: head + '.' + claim + '.' + sig }),
    });
    if (!r.ok) throw new Error('play_auth_' + r.status);
    const j = await r.json();
    token = j.access_token; tokenExp = Date.now() + (j.expires_in || 3600) * 1000;
    return token;
  }

  // Returns { active, expiresAt, state } for a subscription purchase token.
  return async function verify(purchaseToken) {
    const t = await accessToken();
    const url = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
    const r = await fetchImpl(url, { headers: { authorization: 'Bearer ' + t } });
    if (r.status === 404 || r.status === 400 || r.status === 410) return { active: false, expiresAt: null, state: 'invalid' };
    if (!r.ok) throw new Error('play_api_' + r.status);
    const j = await r.json();
    const state = j.subscriptionState || '';
    const line = (j.lineItems && j.lineItems[0]) || {};
    const expiresAt = line.expiryTime || null;
    const notExpired = expiresAt ? Date.parse(expiresAt) > Date.now() : false;
    // Entitled: paid and running, in the grace period, or CANCELED by the user but still inside the period they paid for.
    const active = (state === 'SUBSCRIPTION_STATE_ACTIVE' || state === 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' || state === 'SUBSCRIPTION_STATE_CANCELED') && notExpired;
    const ext = j.externalAccountIdentifiers || {};
    return { active, expiresAt, state, productId: line.productId || null, externalAccountId: ext.obfuscatedExternalAccountId || null };
  };
}

module.exports = { makePlayVerifier };
