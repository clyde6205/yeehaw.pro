const entries = new Map();
const MAX_ENTRIES = 100;

export function cacheGet(key) {
  const item = entries.get(key);
  if (!item) return null;
  if (item.expiresAt <= Date.now()) { entries.delete(key); return null; }
  entries.delete(key);
  entries.set(key, item);
  return item.value;
}
export function cacheSet(key, value, ttlMs = 60000) {
  entries.delete(key);
  entries.set(key, { value, expiresAt: Date.now() + ttlMs });
  while (entries.size > MAX_ENTRIES) entries.delete(entries.keys().next().value);
}
export function cacheClear() { entries.clear(); }
