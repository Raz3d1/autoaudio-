# Architecture

This document describes how AutoAudio is put together, why the boundaries are where they
are, and what to change when something breaks.

---

## 1. Goals and constraints

| Goal | Consequence |
|---|---|
| Turn a share link into a local audio file | One pipeline: resolve → fetch → demux → store |
| Work fully offline afterwards | All playback reads from app-private storage |
| Stay small | No native libraries, no ffmpeg, no Compose |
| Survive provider changes | All source-specific logic behind one interface |
| Run on any modern Android phone | minSdk 26, no vendor APIs |

---

## 2. Module layout

```
app/src/main/java/com/audiosleep/
├── MainActivity.kt            UI wiring, permissions, parse/download orchestration
├── data/
│   ├── AudioItem.kt           persisted record
│   ├── Library.kt             JSON index + file paths
│   └── HistoryStore.kt        parse/download history
├── net/
│   ├── Net.kt                 OkHttp helpers: expand, GET, download
│   ├── HlsDownloader.kt       m3u8 → single local file (TS concat, AES-128)
│   ├── Resolver.kt            ResolveResult + Resolver interface + registry
│   ├── PrimaryResolver.kt     source A adapter (long-form video)
│   ├── SecondaryResolver.kt   source B adapter (short video)
│   ├── AudioBookResolver.kt   source C adapter (audiobook / podcast)
│   └── WebPageResolver.kt     generic fallback: og:video / <video> / direct URL
│   └── SecondaryResolver.kt   source B adapter
├── media/
│   ├── Downloader.kt          orchestrates fetch → demux → persist
│   ├── AudioExtractor.kt      MediaExtractor + MediaMuxer remux
│   └── PlayerService.kt       foreground playback + sleep timer
└── ui/
    └── AudioAdapter.kt        library list rendering
```

Dependencies point in one direction:

```
UI ──► media ──► net
 │       │        │
 └───────┴────────┴──► data
```

`net` never touches `media`, `data` never touches `net`, and only `MainActivity`
knows about all of them.

---

## 3. Data flow

```
  user pastes link
        │
        ▼
  Resolvers.pick(url)  ──►  PrimaryResolver | SecondaryResolver
        │
        │  ResolveResult(title, durationMs, mediaUrl, audioOnly, referer)
        ▼
  Downloader.download()
        │
        ├─ Net.download(mediaUrl, tmp, referer)      progress 0..90 %
        │
        ├─ AudioExtractor.extract(tmp, out.m4a)      progress 90..100 %
        │
        └─ Library.add(item)  ──►  index JSON updated, temp file deleted
                                          │
                                          ▼
                         MainActivity.refreshList() ──► RecyclerView
```

Playback is independent of the download path: `PlayerService` reads the index and the
file path, nothing else.

---

## 4. The resolver contract

```kotlin
interface Resolver {
    fun canHandle(url: String): Boolean
    suspend fun resolve(url: String): ResolveResult
}
```

Two properties make this useful:

- **`canHandle` is pure.** It only pattern-matches the URL, so the registry can pick an
  implementation without doing network work.
- **`resolve` is the only place that knows provider details.** URLs, headers, response
  shapes and error strings for one source live in exactly one file.

`ResolveResult.audioOnly` encodes the only structural difference between sources:

| Value | Meaning | Downstream handling |
|---|---|---|
| `true` | The URL already points at an audio-only stream | Remux as-is |
| `false` | The URL is a muxed video container | Demux the audio track |

`ResolveResult.hls` marks playlists that must be merged before demuxing:

```kotlin
if (result.hls) HlsDownloader.download(...) else Net.download(...)
```

`ResolveResult.referer` exists because several CDNs reject requests without a matching
`Referer`. Carrying it in the result keeps header logic inside the resolver that knows
about it, instead of leaking into `Net` or `Downloader`.

### Adding a source

1. Implement `Resolver`.
2. Register it in `Resolvers.all`.
3. Nothing else changes — download, demux, storage, playback and UI are source-agnostic.

---

## 5. Audio extraction

`AudioExtractor` selects the first `audio/*` track from the source container and copies
its samples into a new MP4 muxer:

```kotlin
extractor.selectTrack(audioTrackIndex)
muxer.addTrack(trackFormat)
while (extractor.readSampleData(buf, 0) >= 0) {
    muxer.writeSampleData(outTrack, buf, info)
    extractor.advance()
}
```

Notes:

- The codec is **copied, not transcoded** — an AAC track stays AAC. No CPU cost, no
  quality loss, no ffmpeg.
- Fragmented MP4 and progressive MP4 are both accepted by `MediaExtractor`.
- If extraction fails but the source was already audio-only, the raw file is kept
  instead of failing the whole download.
- Failure leaves no partial file behind.

---

## 6. Playback service

```
Activity ──startForegroundService(PLAY)──► PlayerService
                                              │
                                              ├─ MediaPlayer (local file)
                                              ├─ Notification (transport controls)
                                              ├─ Handler: progress save every 5 s
                                              └─ Handler: sleep timer
                                              │
        ◄──broadcast(STATE)───────────────────┘
```

Design points:

- **One player, one service.** No queue management; playing an item replaces the current
  one. This keeps the service small and predictable.
- **Progress is persisted every five seconds and on pause/stop**, so an interrupted
  session resumes near where it stopped.
- **Control intents use `startService`, not `startForegroundService`.** Android 12+
  enforces a five-second window for a service started in the foreground mode to post a
  notification; control commands issued from a visible activity do not need that mode.
  As a second guard, the service posts a placeholder notification before handling any
  non-playback intent.
- **Sleep timer** is a single `Handler.postDelayed`: on expiry it pauses the player and
  stops the service.

---

## 7. Storage

| What | Where | Why |
|---|---|---|
| Audio files | `getExternalFilesDir("audio")` | App-private, no `WRITE_EXTERNAL_STORAGE` on any API level, removed on uninstall |
| Index | `SharedPreferences` → JSON array | A few hundred small records, written on download/delete only |

`Library.list()` drops entries whose file no longer exists, so a user clearing storage
manually cannot leave the list in a broken state.

---

## 8. Failure model

Failures are surfaced as user-readable text, never swallowed silently:

| Stage | Failure | User sees |
|---|---|---|
| Link expansion | unreachable, malformed | “解析失败：…” |
| Metadata | no data returned | “Metadata endpoint returned no data” |
| Play stream | empty / restricted | “No playable stream returned” |
| Download | HTTP error, empty body | “Download failed: HTTP …” |
| Demux | no audio track | “Audio track could not be extracted” |
| Page-parsing adapter | page structure changed | “Page structure changed — …” |

The last row is the expected failure mode for the adapter that scrapes a rendered page
rather than a documented API; it is confined to one class on purpose.

---

## 9. Constraints

- **No transcoding.** Output format follows the source codec (AAC in an MP4 container).
- **No DRM.** Protected streams are out of scope and fail with an explicit error.
- **No offline resolution.** A link must be resolved once while online.
- **Single-threaded downloads.** One item at a time; no queue scheduler.
---

## 10. Recommendation feed (primary screen)

The 发现 tab is the default screen and is independent from the caching pipeline.

```
FeedService.load(ctx, categoryId, force)
        │  PREF "feed", TTL 30 min
        ▼
ranking API ──► [FeedItem] ──► FeedAdapter ──► RecyclerView
                                     │
                                     ├─ tap card  → ACTION_VIEW (external app / browser)
                                     └─ tap 缓存   → 解析 tab (in-app resolver)
```

* **Categories** are declared in `FeedService.CATEGORIES` and mapped to sources in
  `sourcesOf()`. "精选" queries several rankings and interleaves their results so the feed
  does not look like a single chart; "听书" keeps only items longer than 20 minutes, which
  suits bedtime listening. Every category can mix several platforms.
* **Caching** is per category, 30 minutes, stored as JSON in `SharedPreferences`, so
  reopening the tab is instant and the last feed remains readable offline.
* **Images** go through `util/ImageLoader` (LRU + thread pool + tag check). Covers from
  video CDNs require a `Referer`, which the loader sends.
* **Deep links** use a plain `ACTION_VIEW` intent. On devices with the platform app
  installed the system opens it directly; otherwise a browser handles the URL. The
  manifest declares a `<queries>` entry so the intent can be resolved on Android 11+.
