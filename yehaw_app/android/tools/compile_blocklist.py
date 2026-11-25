#!/usr/bin/env python3
"""Compile hosts / domain / EasyList-style (||domain^) lists into hosts.bin.

Format (big-endian): int32 magic 0x59484231 ("YHB1"), int32 count, count x int64
FNV-1a-64 hashes of lowercase domains, sorted ascending as SIGNED longs so the
Kotlin side can binary-search a LongArray. No String objects live in RAM.
Usage: compile_blocklist.py out.bin list1 [list2 ...]
"""
import re, struct, sys

def fnv1a64(s: str) -> int:
    h = 0xcbf29ce484222325
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return h

def signed(v: int) -> int:
    return v - (1 << 64) if v >= (1 << 63) else v

DOMAIN = re.compile(r"^[a-z0-9]([a-z0-9._-]*[a-z0-9])?\.[a-z]{2,}$")
SKIP = {"localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost"}
# Never block these even if a list contains them (login / payments / core PH services).
NEVER = {"gcash.com", "maya.ph", "paymaya.com", "facebook.com", "messenger.com", "shopee.ph",
         "lazada.com.ph", "google.com", "gstatic.com", "googleapis.com", "tiktok.com"}

def parse(path):
    for raw in open(path, encoding="utf-8", errors="ignore"):
        line = raw.split("#", 1)[0].strip().lower()
        if not line:
            continue
        if line.startswith("||"):
            line = line[2:].split("^", 1)[0].split("/", 1)[0]
        else:
            parts = line.split()
            line = parts[-1] if parts else ""
        if line in SKIP or not DOMAIN.match(line) or line in NEVER:
            continue
        yield line

def main():
    out, lists = sys.argv[1], sys.argv[2:]
    hashes = {signed(fnv1a64(d)) for p in lists for d in parse(p)}
    arr = sorted(hashes)
    with open(out, "wb") as f:
        f.write(struct.pack(">ii", 0x59484231, len(arr)))
        f.write(struct.pack(">%dq" % len(arr), *arr))
    print(f"wrote {len(arr)} domains -> {out} ({8*len(arr)/1024:.0f} KiB)")

if __name__ == "__main__":
    main()
