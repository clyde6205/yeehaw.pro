# Android cloud Lite API integration

The native browser now starts in Yehaw Lite mode. External HTTP/HTTPS navigation is wrapped through the deployed service origin:

`https://yehawapp.vercel.app/api/render?url=<encoded-destination>`

The native toolbar exposes a Lite ON / Direct toggle:
- **Lite ON**: external HTTP/HTTPS page navigations use the cloud renderer. Rewritten internal `/api/render` and `/api/resource` requests remain on the Yehaw service origin and are not double-wrapped.
- **Direct**: external pages load directly in Android System WebView.
- The Yehaw home/PWA origin remains direct so the existing home experience is preserved.
- When toggling modes, the browser unwraps the current destination and reloads it using the newly selected mode.

The service origin is currently configured in `MainActivity.kt` as `https://yehawapp.vercel.app`. Change this constant when the production domain and deployment are confirmed.

## Security and compatibility

The cloud proxy does not forward browser cookies or authorization headers. Banking, payment and sensitive account workflows should use Direct mode. Forms retain their declared HTTP(S) action and submit directly; the server does not receive the form body.

This is an initial client integration. It still requires a successful Android CI build, real-device navigation tests, redirect/download checks, and confirmation that the configured Vercel deployment has the new API routes before enabling Lite mode for end users.
