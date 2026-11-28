// Tests the start-page greeting logic (extracted from app/src/main/assets/startpage.html) without a browser.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
const html = readFileSync(new URL('../app/src/main/assets/startpage.html', import.meta.url), 'utf8');
// Every inline script must at least parse.
for (const m of html.matchAll(/<script>([\s\S]*?)<\/script>/g)) new vm.Script(m[1]);
const block = html.match(/\/\*GREET:BEGIN\*\/([\s\S]*?)\/\*GREET:END\*\//)[1];
const Y = vm.runInNewContext(block + ';YHGreet', { Intl, Object, String, RegExp });
const langs = Y.langs();
assert.ok(langs.length >= 25, 'expected 25 vetted languages, got ' + langs.length);

// Every vetted language, every part of the day, with and without a name.
for (const lang of langs) {
  for (const hour of [0, 4, 5, 8, 11, 12, 14, 15, 17, 18, 21, 22, 23]) {
    for (const name of ['', 'John']) {
      const g = Y.build(lang, name, new Date(2026, 11, 1, hour, 20));
      assert.ok(g && g.text.length > 10, `${lang} ${hour}h produced nothing`);
      assert.doesNotMatch(g.text, /\{|\}|undefined|NaN|null/, `${lang} ${hour}h has a placeholder/garbage: ${g.text}`);
      const count = g.text.split('John').length - 1;
      assert.equal(count, name ? 1 : 0, `${lang} ${hour}h name count ${count}: ${g.text}`);
      assert.ok(g.text.includes('YeeHaw'), `${lang} must introduce YeeHaw`);
      assert.ok(g.ph && g.ph.length > 1, `${lang} needs a name prompt`);
    }
  }
}
// Real-time: the clock and date come from the supplied moment (the device clock in the app).
const en = Y.build('en', 'John', new Date(2026, 11, 1, 8, 20));
assert.match(en.text, /^Good morning, John\. I'm YeeHaw\. It's 8:20.?AM, Tuesday, December 1\. Let's surf\.$/);
assert.match(Y.build('en', '', new Date(2026, 11, 2, 13, 5)).text, /^Good afternoon\. .*1:05.?PM, Wednesday, December 2/);
assert.match(Y.build('en', '', new Date(2026, 11, 2, 19, 0)).text, /^Good evening\./);
assert.match(Y.build('en', '', new Date(2026, 11, 2, 23, 0)).text, /^Hello\./);
assert.match(Y.build('en', '', new Date(2026, 11, 2, 4, 59)).text, /^Hello\./);
assert.match(Y.build('en', '', new Date(2026, 11, 2, 5, 0)).text, /^Good morning\./);
assert.match(Y.build('id', '', new Date(2026, 11, 2, 16, 0)).text, /^Selamat sore\./);
// Languages without vetted phrases: nothing is shown (never a wrong greeting).
assert.equal(Y.build('zu', 'John', new Date()), null);
assert.equal(Y.has('sw'), false);
// First name only, never anything else.
const cases = { 'John Smith': 'John', '  ana  ': 'ana', 'Dr. José María': 'José', 'Mr John': 'John', 'Mary-Jane Watson': 'Mary-Jane', "O'Neil": "O'Neil", '李明 先生': '李明', 'ジョン': 'ジョン', 'محمد علي': 'محمد',
  '<b>x</b>': '', '<script>alert(1)</script>': '', 'a"b': '', '1234': '', 'J.R.': '', 'mr': '', '': '', '   ': '', ['x'.repeat(30)]: '' };
for (const [raw, want] of Object.entries(cases)) assert.equal(Y.cleanName(raw), want, `cleanName(${JSON.stringify(raw)})`);
assert.equal(Y.cleanName(null), ''); assert.equal(Y.cleanName(undefined), '');
// Right-to-left languages are flagged.
for (const l of ['ar', 'ur', 'fa']) assert.equal(Y.build(l, '', new Date()).rtl, true);
assert.equal(Y.build('en', '', new Date()).rtl, false);
console.log(`Greeting tests passed (${langs.length} languages: ${langs.join(', ')}).`);
