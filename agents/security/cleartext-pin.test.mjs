import { test } from 'node:test';
import assert from 'node:assert/strict';
import { scanAndroidManifest } from './scan.mjs';

// scanAndroidManifest treats cleartext as "pinned" when ANY domain-config forbids cleartext and yeehaw.pro
// appears anywhere later in the file, even if yeehaw.pro sits in a different domain-config that PERMITS cleartext.
test('cleartext to yeehaw.pro is not reported as pinned when its own domain-config permits cleartext', () => {
  const manifest = '<application android:usesCleartextTraffic="true" android:networkSecurityConfig="@xml/network_security_config"></application>';
  const networkConfig = `<network-security-config>
  <domain-config cleartextTrafficPermitted="false"><domain>other.example</domain></domain-config>
  <domain-config cleartextTrafficPermitted="true"><domain>yeehaw.pro</domain></domain-config>
</network-security-config>`;
  const ids = scanAndroidManifest(manifest, networkConfig).map((f) => f.fingerprint);
  assert.deepEqual(ids, ['security:android:cleartext']);
});
