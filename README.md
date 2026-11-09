# Photos

[![CI](https://github.com/roylet/photos/actions/workflows/ci.yml/badge.svg)](https://github.com/roylet/photos/actions/workflows/ci.yml)

A native Android gallery **and photo editor** for a self-hosted [Immich](https://immich.app)
server. Kotlin + Jetpack Compose, with the neo-brutalist look of
[immichup](https://github.com/roylet/immichup): cream paper, ink outlines, hard shadows,
safety orange and the Archivo typeface. The viewer and editor use a dark variant
(ink canvas, light outlines, orange accents).

What it's for (see [docs/PLAN.md](docs/PLAN.md) for the full plan):

- A fast timeline, albums, search, people, map, memories and trash for your Immich library.
- A real photo editor (light, colour, curves, HSL, crop, filters) that saves back to Immich,
  either as a copy stacked with the original or replacing it (the original goes to Immich trash).
- Automatic switching between your server's home-network address and its public URL.

**Status:** scaffold. Connect/sign-in, settings and the navigation shell work; the tabs are placeholders.

## Install

1. On your phone, open the [latest release](https://github.com/roylet/photos/releases/latest) and
   download `Photos-<version>-arm64-v8a.apk` (almost every phone since 2017 is arm64; use
   `Photos-<version>-universal.apk` if unsure, or for an emulator).
2. Open the downloaded file. Android asks you to **allow installing unknown apps** for your
   browser or file manager: tap **Settings**, turn on **Allow from this source**, go back and tap **Install**.
3. Updates install over the previous version (every release is signed with the same key).
   `SHA256SUMS` on each release lets you verify a download.

### Automatic updates with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs and updates apps straight from
GitHub Releases. Install it, tap **Add app**, enter `https://github.com/roylet/photos` and add it.
If it asks which APK to use, pick the one matching `arm64-v8a`. It then notifies you of
new releases and installs them.

## Stack

| | |
|---|---|
| Language / UI | Kotlin 2.4 (AGP built-in Kotlin), Jetpack Compose (BOM 2025.06.01), Material 3, navigation-compose |
| Build | AGP 9.5.0, Gradle 9.8.2, compileSdk/targetSdk 35, minSdk 29, JDK 21 |
| Network | OkHttp 4.12 (one shared client), kotlinx-serialization |
| Images | Coil 3 (+ coil-video), telephoto zoomable-image-coil3 |
| Video / map | Media3 ExoPlayer + UI + OkHttp data source, MapLibre Android |
| Background | WorkManager, coroutines |
| DI | hand-written `AppContainer` |
| Tests | JUnit 4, MockWebServer, Robolectric, Compose UI test |

## Building

Requires JDK 21 and the Android SDK (platform 35, build-tools 36). Point `ANDROID_HOME`
at the SDK, or put `sdk.dir=...` in `local.properties`.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-<abi>-debug.apk (per-ABI + universal)
./gradlew assembleRelease        # minified, per-ABI + universal APKs; release key if configured, else debug key
./gradlew testDebugUnitTest      # unit + Robolectric tests
./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'   # renders screens to app/build/screenshots
```

Release signing reads `RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`
and `RELEASE_KEY_PASSWORD` from the environment, or `storeFile`/`storePassword`/`keyAlias`/`keyPassword`
from a gitignored `keystore.properties` in the repo root. Without either, release builds are
signed with the debug key (Gradle prints a warning). `-PversionName=` and `-PversionCode=` override
the version.

### Baseline Profile

Release builds ship a Baseline Profile (ahead-of-time compiled hot paths; `profileinstaller`
installs it on sideloaded installs) from
`app/src/main/generated/baselineProfiles/baseline-prof.txt`. The committed one is a hand-written
starter covering the app's own timeline, viewer and editor classes. To record a real one, connect
a device or emulator (API 29+; a userdebug/rooted image or API 33+ works best), open Photos once
and sign in to your server, then:

```bash
./gradlew :app:generateBaselineProfile
```

It runs the `:baselineprofile` module's generator (cold start, timeline scroll, opening the viewer
and paging, opening the editor) and overwrites the file above; commit the result. Ordinary builds
(`assembleRelease`, CI) never run it and need no device. Avoid `./gradlew build`, which also
assembles `:baselineprofile` and so asks for a device.

## Releasing

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The [Release workflow](.github/workflows/release.yml) runs the unit tests, builds a signed,
minified release (`versionName` from the tag, `versionCode` from the run number + 1000) and
publishes a GitHub Release with generated notes, `Photos-<version>-arm64-v8a.apk`,
`Photos-<version>-universal.apk` and `SHA256SUMS`. It can also be started by hand from
Actions → Release → Run workflow with a version. It needs four repository secrets
(Settings → Secrets and variables → Actions): `RELEASE_KEYSTORE_BASE64` (the keystore file,
base64-encoded), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`.

Every push and pull request runs [CI](.github/workflows/ci.yml): lint, unit tests, a debug APK
and the Robolectric screenshots, uploaded as workflow artifacts.

## Project layout

```
app/src/main/java/com/roylet/photos/
  immich/        Immich REST client + DTOs
  net/           connection manager (LAN / public URL switching)
  data/          settings (SharedPreferences -> StateFlow)
  domain/        timeline model (pure Kotlin)
  editor/        recipe (pure Kotlin), gl (GPU renderer), cpu (reference renderer), ui
  ui/            screens by feature (connect, gallery, viewer, albums, search, library, settings),
                 components/ (the brutalist kit) and theme/
  upload/        network monitor (and later the save/upload worker)
  util/          formatting helpers
baselineprofile/ Baseline Profile generator (UiAutomator journeys; needs a device)
```

Fonts: Archivo by Omnibus-Type, SIL Open Font License 1.1.
