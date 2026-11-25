#!/usr/bin/env node
'use strict';
// Generates the config-signing key pair and an admin token. Run ONCE; keep the private key secret.
//   node scripts/gen-keys.js
const fs = require('node:fs');
const crypto = require('node:crypto');
const { generateKeyPem, loadSigner } = require('../lib/sign');

const pem = generateKeyPem();
const signer = loadSigner(pem);
const adminToken = crypto.randomBytes(32).toString('base64url');

fs.mkdirSync('out', { recursive: true });
fs.writeFileSync('out/config-signing-key.pem', pem, { mode: 0o600 });
fs.writeFileSync('out/yeehaw_config_pubkey.txt', signer.publicKeyBase64 + '\n');

console.log('1) SERVER SECRET (never commit): out/config-signing-key.pem');
console.log('   Set it on the server as CONFIG_SIGNING_KEY_PEM (paste the file contents; \\n escapes are accepted).');
console.log('2) APP PUBLIC KEY: out/yeehaw_config_pubkey.txt');
console.log('   Copy it to the Android project: app/src/main/assets/yeehaw_config_pubkey.txt');
console.log('   key fingerprint:', signer.fingerprint);
console.log('3) ADMIN TOKEN (shown once, store it in a password manager):');
console.log('   ADMIN_TOKEN=' + adminToken);
