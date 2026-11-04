# Photos

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

## Stack

| | |
|---|---|
| Language / UI | Kotlin 2.2, Jetpack Compose (BOM 2025.06.01), Material 3, navigation-compose |
| Build | AGP 8.11.1, Gradle 8.14.3, compileSdk/targetSdk 35, minSdk 29, JDK 17+ |
| Network | OkHttp 4.12 (one shared client), kotlinx-serialization |
| Images | Coil 3 (+ coil-video), telephoto zoomable-image-coil3 |
| Video / map | Media3 ExoPlayer + UI + OkHttp data source, MapLibre Android |
| Background | WorkManager, coroutines |
| DI | hand-written `AppContainer` |
| Tests | JUnit 4, MockWebServer, Robolectric, Compose UI test |

## Building

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35). Point `ANDROID_HOME`
at the SDK, or put `sdk.dir=...` in `local.properties`.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # minified; signed with the debug key, set up a real signingConfig to distribute
./gradlew testDebugUnitTest      # unit + Robolectric tests
./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'   # renders screens to app/build/screenshots
```

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
```

Fonts: Archivo by Omnibus-Type, SIL Open Font License 1.1.
