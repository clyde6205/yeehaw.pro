# Data-saver image proxy contract

The browser only talks to a proxy the operator configures (Settings → "Data saver proxy address…"), e.g.
`https://img.yehaw.app/i?u={url}&q={q}`. It must be HTTPS and contain `{url}`.

**Request** (images only, `GET`): `Via: 1.1 YeeHaw-Compressor`, `Save-Data: on`, `Accept: image/avif,image/webp,image/*;q=0.8`, Brotli/Gzip accepted. No cookies, no Referer.

**Response**: `200` with an `image/*` body (re-encoded to AVIF/WebP, quality `q`≈55, max width ≈ 720 px) and `Cache-Control`. Anything else makes the browser fall back to a direct load.

**Server requirements (do not skip)**: allow only `http(s)` targets; block private/loopback/link-local IPs and DNS-rebinding (SSRF); cap size (e.g. 10 MB) and time; refuse non-image upstream types; rate-limit per client; never log full URLs; no open-redirect following beyond 3 hops. Treat it as a privacy-sensitive service: it sees which image URLs users load.
