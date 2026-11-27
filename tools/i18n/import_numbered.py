#!/usr/bin/env python3
"""Turns a numbered translation file ("N|text" per line, N = 1-based position in en.json) into tools/i18n/<code>.json."""
import json, sys, pathlib
root = pathlib.Path(__file__).resolve().parent
code, src = sys.argv[1], sys.argv[2]
en = json.loads((root / 'en.json').read_text(encoding='utf-8'))
keys = list(en)
out = {}
for line in pathlib.Path(src).read_text(encoding='utf-8').splitlines():
    if not line.strip(): continue
    n, _, text = line.partition('|')
    i = int(n)
    if not 1 <= i <= len(keys): sys.exit(f'bad index {i}')
    if keys[i - 1] in out: sys.exit(f'duplicate index {i}')
    out[keys[i - 1]] = text.replace('\\n', '\n')
missing = [i + 1 for i, k in enumerate(keys) if k not in out]
if missing: sys.exit(f'{code}: missing indexes {missing}')
(root / f'{code}.json').write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')
print(code, len(out))
