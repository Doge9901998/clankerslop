# TP-7 player

Static TP-7-style audio player + a Cloudflare Worker audio proxy.

    tp7/
      public/index.html   the site
      src/worker.js       /api/proxy?url=...  (CORS-free audio relay, SSRF-guarded)
      wrangler.jsonc

## Deploy
    npm i -g wrangler        # or use npx
    wrangler login
    wrangler deploy          # run from this folder

## Local dev
    wrangler dev

## Notes
- Playback tries: direct URL -> /api/proxy -> direct without meter.
- The proxy only returns audio/video content types, blocks private IPs, and only
  accepts same-origin requests.
- SLOW/FAST step the speed one semitone (x1.059) per press, hold to repeat. **Speed is uncapped** (0.001x - 10000x; one turn of the wheel in SPEED mode = one octave). [ and ] keys, click the readout to reset.
- **PITCH** buttons shift pitch in semitones (+-240) **independently of speed** (, and . keys, click the readout to reset). TAPE makes pitch follow speed on top of that.
- **REV** (or R key) plays backwards at any speed. The lever rewinds/fast-forwards audibly (up to 16x either way), and in TAPE mode scrubbing the wheel backwards plays the audio in reverse like a tape machine (30 deg/s of wheel = 1x).
- Files, links and recordings are decoded into a Web Audio engine, which is what removes the browser's 0.0625-16x limit and enables reverse and pitch. If a link can't be fetched/decoded (or a file is too big to hold in memory: > 40 MB on desktop, > 10 MB on phones) it falls back to a plain audio element: speed limited to 0.06-16x, no reverse.
- Extreme pitch/speed combinations get gritty (the shifter is a granular delay-line design); moderate settings sound clean.
- Two buttons under the device switch EDITION (black / aluminum) and PAGE (light / dark); choices are remembered, page defaults to your system setting.
- Layout follows the TP-7 black edition: platter = wheel (SEEK or SPEED; toggle with the side M button, the hub, the grey button or M key), left lever = fast-forward / rewind (springs back), + - and the bottom knob = volume, bottom-right slits = level meters, keys = rec / play / stop, side buttons = prev / next.
- **LOOP** button (or L key) cycles LOOP: OFF (play the list once) -> LOOP: ALL (wrap around) -> LOOP: ONE (repeat the current track) -> SHUFFLE (random order, no repeats until every track has played; PREV steps back through what was played). NEXT/PREV still skip tracks in LOOP: ONE. The choice is remembered.
- **SCRATCH**: the ◎ button under the OLED (or the M key / tapping the hub) cycles the wheel through SEEK -> SPEED -> SCRATCH. In SCRATCH the platter works like a turntable: touch it and the record is held, move it and the audio moves with your finger (200 degrees of platter = 1 second of audio, i.e. 33 1/3 rpm), pitch follows speed, backwards gives reversed audio, and letting go resumes playback (or leaves it paused if it was paused). It needs a downloaded/decoded file, not a stream. The mode is remembered.
- **Background playback, memory and the notification.** Decoded audio is big (a 10 MB MP3 is 200+ MB in RAM) and Android kills the biggest background app first, so on phones:
  - **light by default on phones:** plain listening streams through an `<audio>` element that is routed through the same Web Audio graph, so the live meters, speed (the browser's own time-stretch) and the semitone pitch shifter all work, but nothing big is held in RAM (a few MB instead of 74-230 MB per track). The decoded engine is built only when a feature needs it: REV, SCRATCH mode, wheel scrubbing in TAPE mode, the lever, or a speed beyond 16x / below 0.06x. It is freed again when you pause for ~20 s, and the next track starts light again;
  - the switch to background playback is gapless: as soon as the app starts to leave (activity paused / window blur) a silent `<audio>` element is started in sync, and only when it is running does the engine stop and the element become audible. The full engine is rebuilt about 1.5 s after you come back (or immediately if you press REV / PITCH / touch the platter in SCRATCH), not right away, so switching back doesn't stutter;
  - on phones the audio context uses a larger buffer (fewer glitches while other apps are busy);
  - the moment the page is hidden the decoded copy is dropped and playback continues through a plain `<audio>` element (a few MB). When you come back the full engine is rebuilt at the current position and swapped in, so reverse / pitch / scratch / >16x are back. While in the background the element limits speed to 0.06-16x and has no reverse or semitone pitch (REV is restored when you return);
  - paused tracks are freed too, and pressing play (also from the notification) reloads them where they were;
  - reverse uses a ~30 s sliding window instead of a second full copy of the track;
  - files whose *decoded* size would exceed a per-device budget (60-160 MB, from `navigator.deviceMemory`) stream through `<audio>` instead, even if the file itself is small.
  - Browser / installed PWA: the standard Media Session API (prev / play-pause / next / stop / seek, title, artwork). Chrome shows its notification while the `<audio>` element plays, i.e. in the background.
  - Android app: a real media notification (foreground service) via `@capgo/capacitor-media-session`, with prev / play-pause / next / stop and a seek bar. Only these fit on a media notification; rec, volume, wheel and the other TP-7 controls can't go there. It only exists once you have pressed play, and ■ on it dismisses it.
  - Android app: `android-overrides/MainActivity.java` (copied over the generated one by the workflow) recreates the activity if Android kills the WebView, instead of the whole app dying, and asks for the notification permission on Android 13+. The workflow also adds `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (required on Android 14+ or the first play would crash) and `POST_NOTIFICATIONS`.
- YouTube support was removed.

## Offline apps (Windows / Android)
Local files and recordings are stored in the browser/app (IndexedDB), so the library works with no internet (remote links obviously need a connection).

- **Install from the site (PWA, Android/Chrome/Edge/desktop):** the INSTALL button on the page. It caches itself and works offline.
- **Windows .exe:** `npm install && npm run app` to run, `npm run dist:win` to build `tp7-setup.exe` + `tp7-portable.exe` in dist/.
  A ready-to-run folder (`TP-7.exe`) is in the separate apps zip.
- **Android APK:** needs the Android SDK, so build it in CI: push this folder to GitHub and run the "build-apps" workflow
  (Actions tab > Run workflow) to get `tp7.apk` (debug-signed; allow "install unknown apps"). Every successful run also publishes
  tp7-setup.exe / tp7-portable.exe / tp7.apk on the Releases page (a manual run makes a pre-release named `build-N`; pushing a tag like
  `v1.1` makes a normal release). If one platform fails, the other is still published, and a failed Android run uploads
  an `android-build-reports` artifact with the Gradle reports.
- **Download buttons on the site:** put the release URLs in `const DL={win:'',apk:''}` near the bottom of public/index.html
  (e.g. https://github.com/YOU/REPO/releases/latest/download/tp7.apk), or drop `tp7.apk` / `tp7-windows.zip` into public/downloads/
  (each file must be under Cloudflare's 25 MB asset limit). The buttons only appear when a file exists.
