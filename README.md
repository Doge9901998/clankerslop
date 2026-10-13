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
