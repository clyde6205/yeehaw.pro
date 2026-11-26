#!/usr/bin/env python3
"""Generates Android string resources from tools/i18n/<lang>.json (key -> text).

  python3 tools/i18n/generate.py        # writes app/src/main/res/values*/strings.xml, validates every language

Source of truth for English is tools/i18n/en.json. Other languages are in tools/i18n/<code>.json.
Checks: no missing/extra keys, identical format placeholders per key, keys actually used in code.
"""
import json, re, sys, pathlib
ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / 'app/src/main/res'
SRC = ROOT / 'app/src/main/java'
# android resource folder per language file
FOLDERS = {'en': 'values', 'hi': 'values-hi', 'zh-CN': 'values-zh-rCN', 'es': 'values-es', 'ar': 'values-ar',
           'bn': 'values-bn', 'pt': 'values-pt', 'ru': 'values-ru', 'ur': 'values-ur', 'id': 'values-in', 'fil': 'values-fil'}
MACHINE = {k for k in FOLDERS if k != 'en'}
PH = re.compile(r'%(\d+\$)?[sdf]|%%')
UNTRANSLATABLE = {'app_name', 'private_window_name'}  # defined in values/app_strings.xml (brand)

def esc(t):
    t = t.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    t = t.replace('\n', '\\n')
    if t.startswith(('@', '?')): t = '\\' + t
    return t

def load(code):
    return json.loads((ROOT / 'tools/i18n' / f'{code}.json').read_text(encoding='utf-8'))

def used_keys():
    keys = set()
    for p in SRC.rglob('*.kt'):
        keys |= set(re.findall(r'R\.string\.([a-z0-9_]+)', p.read_text(encoding='utf-8')))
    return keys

def main():
    en = load('en')
    used = used_keys()
    missing_in_en = sorted(k for k in used if k not in en and k not in UNTRANSLATABLE)
    unused = sorted(k for k in en if k not in used)
    errors = []
    if missing_in_en: errors.append(f'keys used in code but not in en.json: {missing_in_en}')
    if unused: errors.append(f'keys in en.json not used in code: {unused}')
    for code, folder in FOLDERS.items():
        f = ROOT / 'tools/i18n' / f'{code}.json'
        if not f.exists():
            if code != 'en': print(f'-- {code}: no file yet (skipped)')
            continue
        tr = load(code)
        miss = sorted(set(en) - set(tr)); extra = sorted(set(tr) - set(en))
        if miss: errors.append(f'{code}: missing {len(miss)} keys, e.g. {miss[:5]}')
        if extra: errors.append(f'{code}: extra keys {extra[:5]}')
        for k, v in tr.items():
            if k in en and sorted(PH.findall(v)) != sorted(PH.findall(en[k])):
                errors.append(f'{code}:{k}: placeholders differ  en={PH.findall(en[k])} tr={PH.findall(v)}')
        out = ['<?xml version="1.0" encoding="utf-8"?>']
        if code in MACHINE:
            out.append('<!-- MACHINE TRANSLATION by Claude (Anthropic AI). NOT reviewed by a native speaker. See docs/LOCALIZATION.md. -->')
        else:
            out.append('<!-- English source strings. Generated from tools/i18n/en.json - edit that file, then run tools/i18n/generate.py. -->')
        out.append('<resources>')
        for k in sorted(en):
            if k not in tr: continue
            v = tr[k]
            fmt = ' formatted="false"' if (not PH.search(v) and '%' in v) else ''
            out.append(f'    <string name="{k}"{fmt}>{esc(v)}</string>')
        out.append('</resources>')
        d = RES / folder; d.mkdir(parents=True, exist_ok=True)
        (d / 'strings.xml').write_text('\n'.join(out) + '\n', encoding='utf-8')
        print(f'{code}: wrote {folder}/strings.xml ({len(tr)} strings)')
    if errors:
        print('\nERRORS:'); [print(' -', e) for e in errors]; sys.exit(1)

main()
