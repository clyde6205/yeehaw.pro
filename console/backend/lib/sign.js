'use strict';
const crypto = require('node:crypto');

// ECDSA P-256 / SHA-256 with DER signatures: verifiable on every Android version with
// java.security.Signature "SHA256withECDSA" and no extra libraries.
function loadSigner(pem) {
  const privateKey = crypto.createPrivateKey(pem);
  const publicKey = crypto.createPublicKey(privateKey);
  const spki = publicKey.export({ type: 'spki', format: 'der' });
  return {
    sign(bodyBuffer) {
      return crypto.sign('sha256', bodyBuffer, { key: privateKey, dsaEncoding: 'der' }).toString('base64');
    },
    publicKeyBase64: spki.toString('base64'),
    fingerprint: crypto.createHash('sha256').update(spki).digest('hex').slice(0, 16),
  };
}

function generateKeyPem() {
  const { privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return privateKey.export({ type: 'pkcs8', format: 'pem' });
}

function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(String(a)).digest();
  const hb = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(ha, hb);
}

module.exports = { loadSigner, generateKeyPem, safeEqual };
