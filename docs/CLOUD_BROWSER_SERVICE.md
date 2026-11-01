# Yehaw Cloud Browser Service v0.2

## API endpoints

- `GET /api/browser-health` — service capability/health response.
- `GET /api/render?url=https%3A%2F%2Fexample.com` — fetches and transforms a public HTML page.
- `GET /api/resource?url=...` — converts supported images to WebP, rewrites CSS assets, and passes through supported web fonts.

Use this only for an explicit Yehaw Lite browsing mode. Banking, payment, authentication and other sensitive sessions must use direct browsing. The proxy does not forward browser cookies or authorization headers and is not an authenticated-session proxy.

## Security controls

- HTTP/HTTPS only; embedded credentials and nonstandard ports are rejected.
- DNS resolution occurs before each outbound request. Every resolved address must be public unicast.
- The selected validated address is pinned into the outbound request lookup callback to reduce DNS-rebinding exposure.
- Redirects are manual, revalidated on every hop, limited to five, and HTTPS-to-HTTP downgrade redirects are blocked.
- Outbound response limits: 4 MiB HTML, 8 MiB resources; request timeout: 10 seconds.
- Render endpoint accepts HTML only. Resource endpoint allows supported image, CSS and web-font MIME types only.
- No inbound cookies, authorization headers or arbitrary client headers are forwarded upstream.
- Scripts, embedded active content, inline event handlers, known tracker hosts and refresh redirects are removed; a restrictive CSP is applied.
- Only GET and HEAD are accepted. No application data writes are exposed.

## Optimization and caching

- Images are auto-oriented, resized inside a 1280×1280 bound and converted to WebP quality 72.
- Stylesheet URLs and CSS `url()` / `@import` references are proxied through the resource endpoint.
- Navigation links are routed back through the render endpoint.
- Response byte counts and cache HIT/MISS indicators are provided.
- A bounded 100-entry process cache is used with CDN cache headers. The process cache is per serverless instance and is not a shared global cache.

## Deployment

Runtime dependencies are pinned in `package.json`: Cheerio, ipaddr.js and Sharp. Use Node.js 20.18.1+ (Node 22 recommended) and run:

```bash
npm install
npm test
npm run check
```

Deploy on Vercel or a compatible Node serverless host. Confirm the Sharp native binary matches the deployment architecture.

## Limitations before public launch

1. This is a compatibility-limited Lite mode. Scripts, video, iframe embeds and some interactive features are removed. Do not claim full modern-site compatibility.
2. Authentication, banking and payment sessions are not supported through the proxy; provide direct-mode navigation in the Android client.
3. Process-local caching is best-effort. Production scale needs shared cache/CDN policy, cache-key review, purge controls and regional routing.
4. Add edge-level per-user/IP rate limiting, quotas, abuse detection, request-cost controls and monitoring before public exposure.
5. Add live integration tests, load tests, penetration testing, accessibility and real low-memory device testing before production release.
6. Review third-party site terms, privacy disclosures and data-retention rules before enabling cloud fetches globally.

## Local smoke test

```bash
npm install
npm test
npm run check
```

With a local serverless dev host configured, request:

```bash
curl -i 'http://localhost:3000/api/render?url=https%3A%2F%2Fexample.com'
curl -i 'http://localhost:3000/api/render?url=http%3A%2F%2F169.254.169.254'
```

The metadata-address request must be rejected.
