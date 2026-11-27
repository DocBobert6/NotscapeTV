# Notscape TV

A simple, lightweight, general Android TV browser. The start page is a
dashboard with three sections, all from free sources with no API keys:

- **News** — free RSS feeds (BBC News, AP Top Stories via Flipboard,
  Google News top stories as fallback). Headlines open in the browser.
- **Stocks** — defaults only: S&P 500, Dow Jones, Nasdaq. Yahoo Finance
  chart API (no key), with a Stooq daily-CSV fallback for the S&P 500
  and the Dow.
- **Weather** — Open-Meteo (free, no key). No seeded default location:
  first launch offers current location or adding a place. Works anywhere.

The browser itself is a single-tab WebView shell with:

- Filter-list adblocking (EasyList, EasyPrivacy, AdGuard, uBlock lists,
  ported from Notscape Mariner's FilterEngine), on by default.
- A trimmed Media3/ExoPlayer video player with TV-sized D-pad controls.
- An onscreen cursor driven by the TV remote (D-pad moves, OK clicks).
- A strict popup blocker (strict by default, per-site allow/block,
  popunder guard), ported from Notscape Mariner.

No account, no sync, no vault, no reader, no downloads, no tabs in v1.

## Sideload on an Onn TV

1. On the TV: Settings → Device Preferences → About → press OK on
   **Build** seven times → Developer options → **USB debugging** on
   (or use the Downloader app to install the APK file directly).
2. From a computer on the same Wi-Fi: `adb connect <tv-ip>:5555`,
   then `adb install -r app-debug.apk`.
3. Accept the install prompt on the TV. Notscape TV appears in the
   apps row.
