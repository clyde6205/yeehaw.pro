'use strict';
// Strict input validation. Everything the apps or the console send goes through here.

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const HOST_RE = /^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;
const VERSION_RE = /^[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}$/;
const TAG_RE = /^[A-Za-z0-9_.:=&%+-]{0,128}$/;

class ValidationError extends Error {
  constructor(message, field) { super(message); this.status = 400; this.field = field; }
}
const fail = (field, msg) => { throw new ValidationError(`${field}: ${msg}`, field); };
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

function str(v, field, { min = 1, max = 200 } = {}) {
  if (typeof v !== 'string') fail(field, 'must be a string');
  const s = v.trim();
  if (s.length < min || s.length > max) fail(field, `length must be ${min}-${max}`);
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/.test(s)) fail(field, 'contains control characters');
  return s;
}

function privateOrLocalHost(h) {
  return h === 'localhost' || /^\d+(\.\d+){3}$/.test(h) || h.includes(':') || h.endsWith('.local') || h.endsWith('.internal');
}

function httpsUrl(v, field, { max = 500 } = {}) {
  const s = str(v, field, { max });
  let u;
  try { u = new URL(s.replace(/\{[a-z_]+\}/gi, 'x')); } catch { fail(field, 'is not a valid URL'); }
  if (u.protocol !== 'https:') fail(field, 'must use https://');
  if (u.username || u.password) fail(field, 'must not contain credentials');
  if (privateOrLocalHost(u.hostname.toLowerCase())) fail(field, 'host must be a public domain name');
  return s;
}

function searchTemplate(v) {
  const s = httpsUrl(v, 'default_search_url');
  const q = s.split('{query}').length - 1;
  if (q !== 1) fail('default_search_url', 'must contain {query} exactly once');
  const leftover = s.replace('{query}', '').match(/\{[^}]*\}/g) || [];
  if (leftover.some((p) => p !== '{tag}')) fail('default_search_url', 'only {query} and {tag} placeholders are allowed');
  return s;
}

function domain(v, field) {
  const s = str(v, field, { max: 253 }).toLowerCase().replace(/^\*\./, '').replace(/\.$/, '');
  if (!HOST_RE.test(s)) fail(field, `"${s}" is not a valid domain`);
  return s;
}

function bootPayload(b) {
  if (!isObj(b)) fail('body', 'must be a JSON object');
  const device_id = str(b.device_id, 'device_id', { min: 36, max: 36 }).toLowerCase();
  if (!UUID_RE.test(device_id)) fail('device_id', 'must be a UUID');
  const app_version = str(b.app_version, 'app_version', { max: 32 });
  if (!VERSION_RE.test(app_version)) fail('app_version', 'invalid format');
  const android_os_ver = str(b.android_os_ver, 'android_os_ver', { max: 40 });
  const ram_mb = b.ram_mb;
  if (!Number.isInteger(ram_mb) || ram_mb < 256 || ram_mb > 131072) fail('ram_mb', 'must be an integer between 256 and 131072');
  let country = null;
  if (b.country_locale != null && b.country_locale !== '') {
    const c = str(b.country_locale, 'country_locale', { max: 8 }).toUpperCase();
    if (/^[A-Z]{2}$/.test(c)) country = c;
  }
  let version_code = null;
  if (b.version_code != null) {
    if (!Number.isInteger(b.version_code) || b.version_code < 1 || b.version_code > 100_000_000) fail('version_code', 'must be a positive integer');
    version_code = b.version_code;
  }
  return { device_id, app_version, android_os_ver, ram_mb, country, version_code };
}

const HEALTH_KINDS = new Set(['crash', 'freeze', 'render_rescue', 'store_repair', 'skipped_requests', 'est_bytes_avoided', 'low_signal_seconds', 'data_received_bytes']);
function healthPayload(b) {
  if (!isObj(b)) fail('body', 'must be a JSON object');
  const device_id = str(b.device_id, 'device_id', { min: 36, max: 36 }).toLowerCase();
  if (!UUID_RE.test(device_id)) fail('device_id', 'must be a UUID');
  if (!isObj(b.deltas)) fail('deltas', 'must be an object of kind -> count');
  const rows = [];
  for (const [k, val] of Object.entries(b.deltas)) {
    if (!HEALTH_KINDS.has(k)) fail('deltas.' + k, 'unknown kind');
    if (!Number.isSafeInteger(val) || val < 0 || val > 1e12) fail('deltas.' + k, 'must be a non-negative integer');
    if (val > 0) rows.push([k, val]);
  }
  if (rows.length > 8) fail('deltas', 'too many kinds');
  return { device_id, rows };
}

const AD_EVENTS = new Set(['impression', 'click', 'paid']);
const AD_FORMATS = new Set(['banner', 'native', 'interstitial', 'rewarded', 'app_open']);

function adEventPayload(b) {
  if (!isObj(b)) fail('body', 'must be a JSON object');
  const device_id = str(b.device_id, 'device_id', { min: 36, max: 36 }).toLowerCase();
  if (!UUID_RE.test(device_id)) fail('device_id', 'must be a UUID');
  const event = str(b.event, 'event', { max: 12 }).toLowerCase();
  if (!AD_EVENTS.has(event)) fail('event', 'must be impression, click or paid');
  const ad_format = str(b.ad_format, 'ad_format', { max: 16 }).toLowerCase();
  if (!AD_FORMATS.has(ad_format)) fail('ad_format', 'unsupported ad format');
  const ad_unit = b.ad_unit == null ? null : str(b.ad_unit, 'ad_unit', { max: 80 });
  const network = b.network == null ? null : str(b.network, 'network', { max: 60 });
  let revenue_micros = 0, currency = null;
  if (event === 'paid') {
    if (!Number.isSafeInteger(b.revenue_micros) || b.revenue_micros < 0 || b.revenue_micros > 100_000_000) fail('revenue_micros', 'must be an integer 0-100000000');
    revenue_micros = b.revenue_micros;
    currency = str(b.currency, 'currency', { min: 3, max: 3 }).toUpperCase();
    if (!/^[A-Z]{3}$/.test(currency)) fail('currency', 'must be an ISO 4217 code');
  }
  return { device_id, event, ad_format, ad_unit, network, revenue_micros, currency };
}

const SHARE_ACTIONS = new Set(['qr_shown', 'shared', 'apk_shared']);
const SHARE_CHANNELS = new Set(['qr', 'whatsapp', 'messenger', 'telegram', 'viber', 'sms', 'system', 'bluetooth', 'facebook', 'email']);

function shareEventPayload(b) {
  if (!isObj(b)) fail('body', 'must be a JSON object');
  const device_id = str(b.device_id, 'device_id', { min: 36, max: 36 }).toLowerCase();
  if (!UUID_RE.test(device_id)) fail('device_id', 'must be a UUID');
  const action = str(b.action, 'action', { max: 12 }).toLowerCase();
  if (!SHARE_ACTIONS.has(action)) fail('action', 'must be qr_shown, shared or apk_shared');
  const channel = str(b.channel, 'channel', { max: 16 }).toLowerCase();
  if (!SHARE_CHANNELS.has(channel)) fail('channel', 'unsupported channel');
  let ref_code = null;
  if (b.ref_code != null) {
    ref_code = str(b.ref_code, 'ref_code', { min: 8, max: 8 }).toUpperCase();
    if (!/^[A-Z2-7]{8}$/.test(ref_code)) fail('ref_code', 'invalid format');
  }
  return { device_id, action, channel, ref_code };
}

const CC_RE = /^[A-Z]{2}$/;
function countries(a, field) {
  if (a == null) return [];
  if (!Array.isArray(a) || a.length > 250) fail(field, 'must be an array of at most 250 ISO country codes');
  return [...new Set(a.map((c, i) => {
    const x = str(c, `${field}[${i}]`, { min: 2, max: 2 }).toUpperCase();
    if (!CC_RE.test(x)) fail(`${field}[${i}]`, 'must be a 2-letter ISO country code');
    return x;
  }))].sort();
}
function intIn(v, field, lo, hi) {
  if (!Number.isInteger(v) || v < lo || v > hi) fail(field, `must be an integer ${lo}-${hi}`);
  return v;
}
function bool(v, field) { if (typeof v !== 'boolean') fail(field, 'must be true or false'); return v; }
function isoOrNull(v, field) {
  if (v == null || v === '') return null;
  const s = str(v, field, { max: 40 });
  if (Number.isNaN(Date.parse(s))) fail(field, 'must be an ISO date-time');
  return new Date(s).toISOString();
}

function features(f) {
  if (!isObj(f)) fail('features', 'must be an object');
  const keys = Object.keys(f);
  if (keys.length > 50) fail('features', 'at most 50 flags');
  const out = {};
  for (const k of keys.sort()) {
    if (!/^[a-z][a-z0-9_]{1,40}$/.test(k)) fail(`features.${k}`, 'flag names are lowercase letters, digits and _');
    const val = f[k];
    if (typeof val === 'boolean' || (Number.isFinite(val) && Math.abs(val) < 1e9)) out[k] = val;
    else if (typeof val === 'string') out[k] = str(val, `features.${k}`, { min: 0, max: 100 });
    else fail(`features.${k}`, 'must be a boolean, number or short string');
  }
  return out;
}

function updatePolicy(p) {
  if (!isObj(p)) fail('update_policy', 'must be an object');
  const out = {
    latest_version_code: intIn(p.latest_version_code ?? 0, 'update_policy.latest_version_code', 0, 100_000_000),
    latest_version_name: p.latest_version_name ? str(p.latest_version_name, 'update_policy.latest_version_name', { max: 32 }) : '',
    min_version_code: intIn(p.min_version_code ?? 0, 'update_policy.min_version_code', 0, 100_000_000),
    rollout_percent: intIn(p.rollout_percent ?? 100, 'update_policy.rollout_percent', 0, 100),
    message: p.message ? str(p.message, 'update_policy.message', { max: 200 }) : '',
    update_url: p.update_url ? httpsUrl(p.update_url, 'update_policy.update_url') : '',
  };
  if (out.min_version_code > out.latest_version_code && out.latest_version_code > 0) fail('update_policy.min_version_code', 'cannot be higher than latest_version_code');
  return out;
}

const PLACEMENTS = ['banner', 'native', 'interstitial', 'rewarded', 'app_open'];
function adsPolicy(p) {
  if (!isObj(p)) fail('ads_policy', 'must be an object');
  const pl = isObj(p.placements) ? p.placements : {};
  const placements = {};
  for (const k of PLACEMENTS) placements[k] = k in pl ? bool(pl[k], `ads_policy.placements.${k}`) : (k === 'banner');
  return {
    disabled_countries: countries(p.disabled_countries, 'ads_policy.disabled_countries'),
    only_countries: countries(p.only_countries, 'ads_policy.only_countries'),
    placements,
    interstitial_min_interval_seconds: intIn(p.interstitial_min_interval_seconds ?? 180, 'ads_policy.interstitial_min_interval_seconds', 30, 3600),
    banner_refresh_seconds: intIn(p.banner_refresh_seconds ?? 60, 'ads_policy.banner_refresh_seconds', 30, 300),
    max_ads_per_session: intIn(p.max_ads_per_session ?? 6, 'ads_policy.max_ads_per_session', 0, 100),
    low_ram_no_heavy_ads: p.low_ram_no_heavy_ads == null ? true : bool(p.low_ram_no_heavy_ads, 'ads_policy.low_ram_no_heavy_ads'),
  };
}

function promos(a) {
  if (!Array.isArray(a) || a.length > 20) fail('promos', 'must be an array of at most 20 campaigns');
  const seen = new Set();
  return a.map((e, i) => {
    if (!isObj(e)) fail(`promos[${i}]`, 'must be an object');
    const id = str(e.id, `promos[${i}].id`, { min: 3, max: 32 }).toLowerCase();
    if (!/^[a-z0-9][a-z0-9_-]+$/.test(id)) fail(`promos[${i}].id`, 'lowercase letters, digits, - and _ only');
    if (seen.has(id)) fail(`promos[${i}].id`, 'duplicate campaign id');
    seen.add(id);
    const placement = e.placement == null ? 'start_page' : str(e.placement, `promos[${i}].placement`, { max: 16 });
    if (!['start_page', 'banner'].includes(placement)) fail(`promos[${i}].placement`, 'must be start_page or banner');
    return {
      id, title: str(e.title, `promos[${i}].title`, { max: 40 }), text: str(e.text ?? '', `promos[${i}].text`, { min: 0, max: 120 }),
      url: httpsUrl(e.url, `promos[${i}].url`, { max: 600 }), countries: countries(e.countries, `promos[${i}].countries`),
      start_at: isoOrNull(e.start_at, `promos[${i}].start_at`), end_at: isoOrNull(e.end_at, `promos[${i}].end_at`),
      weight: intIn(e.weight ?? 10, `promos[${i}].weight`, 1, 100), active: e.active == null ? true : bool(e.active, `promos[${i}].active`), placement,
    };
  });
}

function announcement(a) {
  if (a == null) return null;
  if (!isObj(a)) fail('announcement', 'must be an object or null');
  const level = a.level == null ? 'info' : str(a.level, 'announcement.level', { max: 8 });
  if (!['info', 'warning'].includes(level)) fail('announcement.level', 'must be info or warning');
  return {
    id: str(a.id, 'announcement.id', { min: 2, max: 32 }), text: str(a.text, 'announcement.text', { max: 200 }),
    url: a.url ? httpsUrl(a.url, 'announcement.url') : '', level, countries: countries(a.countries, 'announcement.countries'),
    expires_at: isoOrNull(a.expires_at, 'announcement.expires_at'),
  };
}

function configPatch(b) {
  if (!isObj(b)) fail('body', 'must be a JSON object');
  const out = {};
  if ('default_search_url' in b) out.default_search_url = searchTemplate(b.default_search_url);
  if ('affiliate_tag' in b) {
    if (typeof b.affiliate_tag !== 'string') fail('affiliate_tag', 'must be a string');
    const t = b.affiliate_tag.trim();
    if (!TAG_RE.test(t)) fail('affiliate_tag', 'contains unsupported characters or is too long');
    out.affiliate_tag = t;
  }
  if ('admob_enabled' in b) {
    if (typeof b.admob_enabled !== 'boolean') fail('admob_enabled', 'must be true or false');
    out.admob_enabled = b.admob_enabled;
  }
  if ('speed_dial' in b) {
    if (!Array.isArray(b.speed_dial) || b.speed_dial.length > 12) fail('speed_dial', 'must be an array of at most 12 entries');
    out.speed_dial = b.speed_dial.map((e, i) => {
      if (!isObj(e)) fail(`speed_dial[${i}]`, 'must be an object');
      return {
        title: str(e.title, `speed_dial[${i}].title`, { max: 24 }),
        url: httpsUrl(e.url, `speed_dial[${i}].url`, { max: 600 }),
        sponsored: e.sponsored === true,
      };
    });
  }
  if ('blacklisted_domains' in b) {
    if (!Array.isArray(b.blacklisted_domains) || b.blacklisted_domains.length > 5000) fail('blacklisted_domains', 'must be an array of at most 5000 domains');
    out.blacklisted_domains = [...new Set(b.blacklisted_domains.map((d, i) => domain(d, `blacklisted_domains[${i}]`)))].sort();
  }
  if ('features' in b) out.features = features(b.features);
  if ('update_policy' in b) out.update_policy = updatePolicy(b.update_policy);
  if ('ads_policy' in b) out.ads_policy = adsPolicy(b.ads_policy);
  if ('promos' in b) out.promos = promos(b.promos);
  if ('announcement' in b) out.announcement = announcement(b.announcement);
  if ('note' in b && b.note != null) out.note = str(b.note, 'note', { min: 0, max: 200 });
  if (!Object.keys(out).length) fail('body', 'no recognised fields to update');
  return out;
}

module.exports = { ValidationError, healthPayload, bootPayload, adEventPayload, shareEventPayload, configPatch, searchTemplate, domain, UUID_RE };
