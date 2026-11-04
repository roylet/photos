# Photos: native Android Immich gallery + editor (plan)

## Context
The user loves the UI of **immichup**. Its current version (branch `origin/claude/youthful-turing-ccj6l5`) is a **native Kotlin + Jetpack Compose** app with a neo-brutalist design system: cream paper, orange, ink borders, hard shadows, Archivo font. The default branch still has an older Vue/Capacitor version, which is not what we build on.

The new app goes in the empty `roylet/photos` repo, on branch `claude/sleepy-carson-yvl7u0`. It should be a real gallery for their Immich server that can also **edit photos (and later videos)**, which the view-only official Immich app can't.

- **Saving an edit:** the user chooses **Replace original** or **Save as copy**. A replaced original goes to Immich trash so it can be recovered.
- **Connection:** switch automatically between the LAN IP (fast at home) and public `https://photos.royle.au`. After the user logs in via the public URL, the app **finds the LAN address by itself**. immichup only has a manual optional `lanUrl` field.

**Decisions:**
- Native Android now, iOS later.
- Milestone 1 is gallery + photo editor. Milestone 2 is video editing. AI (Gemini Nano Banana 2.1) is a stretch goal.
- I coordinate and hand implementation to sub-agents.

## Research takeaways
- **Gallery UX** (Google/Apple Photos, Immich, Aves, Fossify):
  - justified grid; pinch to change density (3/5/7 columns + month view);
  - always-visible date scrubber with year/month labels;
  - long-press + drag range select;
  - pager viewer with zoom, swipe-down dismiss, filmstrip and info sheet;
  - memories, map, people, smart search.
  - Lesson from Immich: lay out each month bucket lazily (the scrubber stalls at ~2M assets).
- **Editor** (Lightroom Mobile, Snapseed, Apple Photos, PhotoCraft):
  - edits stored as a pure-data *recipe*, with an undo/redo history (PhotoCraft's document-model + command-registry idea);
  - light/color/effects sliders, tone curve, 8-band HSL, crop/straighten/perspective, LUT filters with intensity;
  - hold-to-compare before/after; double-tap a slider to reset;
  - a CPU reference renderer to test the GPU output against (PhotoCraft's dual compositor);
  - masks and healing come later.
- **Immich API v3.4** covers everything:
  - `PUT /assets/copy` (albums/favourite/sharedLinks/stack/sidecar), `DELETE /assets` without `force` (sends to trash), `POST /trash/restore/assets`, `POST /stacks`;
  - columnar `/timeline/bucket`, `/search/smart`, `/map/markers`, `/memories`, `/people`;
  - no server-id endpoint, so the same-server check compares `GET /users/me` `id`;
  - when `asset.isEdited`, pass `edited=true` on thumbnail/original (Immich's own crop/rotate edits).
- **Video (M2):** Media3 Transformer (hardware MediaCodec + GL effects, trim/crop/rotate/speed/mute) is the clear choice for native.

## Stack (mirror immichup, Kotlin branch)
Kotlin 2.2, AGP 8.11, Compose BOM + Material3, navigation-compose, OkHttp 4.12 + kotlinx-serialization, Coil 3 (+ coil-video), SharedPreferences→StateFlow settings, coroutines, WorkManager, exifinterface, hand-written `AppContainer` DI. compileSdk/targetSdk 35, minSdk 29. App id `com.roylet.photos`.

New dependencies:
- `me.saket.telephoto:zoomable-image-coil3` (zoom + subsampling for huge images)
- Media3 ExoPlayer (+ OkHttp data source for auth headers)
- MapLibre Android (map)
- (M2) Media3 Transformer/effect
- (M3) ML Kit Subject Segmentation

**iOS-later:** keep `api/`, `domain/`, `editor/recipe` free of Android imports so they can move to a Kotlin Multiplatform module (Compose Multiplatform for UI) later.

### Copy from immichup (`app/src/main/java/com/roylet/immichup/`)
Extract with `git show origin/claude/youthful-turing-ccj6l5:<path>` and rename the package.
- `ui/theme/Theme.kt`: `Brutal` palette, Archivo `Type` scale, `res/font/archivo_*`. Add a **dark brutalist variant** for the viewer and editor (ink canvas, orange accents).
- `ui/components/Brutal.kt`: `Modifier.brutal`, `Pressable`, `BrutalButton/Card/Chip/Tag/Check/Progress/TextField`, `Notice`, `ScreenHeader`, `BottomBar`, `BrutalSpinner`.
- `immich/ImmichApi.kt`:
  - `login`, `createApiKey`, `normalizeOrigin`, `discoverOrigin` (https-first, redirect-aware);
  - `httpError`/`ImmichException` with friendly messages; `Call.await()`;
  - streaming multipart `upload()` with `StreamBody` + `x-immich-checksum`.
- `data/Settings.kt` pattern, `upload/NetworkMonitor.kt`, `upload/UploadWorker.kt` (foreground-service pattern, used for saving big edits), `util/Format.kt`.
- `ui/screens/ConnectScreen.kt` flow with `step(label){}` errors; `AppNav.kt` patterns and transitions.
- `res/xml/network_security_config.xml` (cleartext allowed, so LAN http works).
- Tests: `ImmichApiTest.kt` (MockWebServer), `ScreenshotTest.kt` (Robolectric, `-Pscreenshots`).

## Design

### Connection manager (`net/ConnectionManager.kt`)
1. Log in with any URL (normally `https://photos.royle.au`). Save `publicUrl`, apiKey, and `userId` from `/users/me`.
2. **Auto LAN discovery** on unmetered WiFi:
   - read the device's IPv4 address and prefix from `ConnectivityManager.getLinkProperties` (no location permission);
   - scan the /24 on port 2283 with coroutines (~32 in parallel, ~300 ms timeout, `/api/server/ping` → `pong`);
   - for each hit, call `/users/me` with our key and accept it only if `id` matches;
   - save `localUrl`, plus the network's gateway as a hint.
   - If the user logged in via a LAN IP instead, learn `publicUrl` from `GET /server/config` → `externalDomain`, falling back to a manual field.
3. **Choosing the URL:**
   - `activeUrl: StateFlow` is re-evaluated on a `NetworkCallback` change and on app foreground: probe `localUrl` (~800 ms), otherwise use `publicUrl`.
   - An OkHttp interceptor rewrites every request's scheme, host and port to `activeUrl`. Coil, ExoPlayer and uploads all share that one client, so a switch is instant everywhere.
   - On a connect failure to the LAN URL: switch to public, retry once, and re-probe later.
4. A brutal chip in the header shows **HOME** (mint) or **REMOTE** (blue). In Settings the user can view or edit either URL, rescan, or force a mode.

### Media loading
- Coil 3 uses the shared OkHttp client, so auth goes in the `x-api-key` header and keys never appear in URLs.
- Placeholders: decode the bucket's `thumbhash` in Kotlin.
- Sizes: `thumbnail` in the grid, `preview` in the viewer (telephoto subsamples `original` when zoomed deep), `original` streamed to cache only for the editor.
- Video plays with ExoPlayer from `/video/playback` using the same client.

### Gallery (M1): `ui/gallery/`, `domain/timeline/`
- **Timeline:**
  - get the bucket list from `/timeline/buckets`, then fetch each `/timeline/bucket` lazily and map its columnar arrays to `TimelineAsset`;
  - lay out each bucket on demand into justified rows using `ratio`, inside a `LazyColumn` of month headers + rows, with estimated heights before layout;
  - custom scrubber with year/month labels;
  - pinch-to-zoom density;
  - long-press drag-select;
  - selection bar: favourite, archive, add to album, share, delete → trash.
- **Viewer:** `HorizontalPager` + telephoto zoom, swipe-down dismiss with a shared-element return, filmstrip, info bottom sheet (EXIF, mini map, people), video and motion photos, **EDIT** button.
- **Other screens:**
  - Albums (list, detail, create, add)
  - Favorites, Archive
  - Search (smart + suggestions)
  - People
  - Map (MapLibre + `/map/markers`)
  - Memories carousel at the top of the timeline
  - Trash (timeline with `isTrashed=true`; restore; empty)
  - Settings
- **Navigation:** bottom bar on phones (Photos · Albums · Search · Library), navigation rail on tablets.

### Actions in the viewer and on selections (photos and videos)
The same set of actions appears in the viewer's bottom bar and in the multi-select bar:

| Action | Behaviour |
|---|---|
| **Favourite** | Heart toggles instantly with an optimistic update (`PUT /assets/{id}`). It plays a pop + burst animation, and the change rolls back with a snackbar if the request fails. In the viewer, a double-tap on the info area or the heart button also toggles it. |
| **Add to album** | A bottom sheet lists albums: recent first, then searchable, each with a cover thumbnail. Assets already in an album show a check, and tapping it removes them. **+ New album** is inline: type a name and the album is created with these assets in one go (`POST /albums {albumName, assetIds}`). |
| **Share** | A sheet with two choices: *Send file* and *Share link*. The sheet remembers the last choice. |
| **Edit** | Opens the photo editor (or, in M2, the video editor). |
| **Info** | Opens the info sheet. |
| **Archive** | Moves the asset to the archive. |
| **Delete** | Moves the asset to trash, with an undo snackbar. |

The Share sheet's two choices:
- **Send file:** downloads the original (or the edited version) to cache, then opens the Android share sheet through a `FileProvider`. This works for photos and videos, with progress shown for large videos. Selecting several assets shares them all.
- **Share link:** creates an Immich shared link (`POST /shared-links {type: INDIVIDUAL, assetIds}`, or an ALBUM link from an album page). Options are expiry, allow download, show metadata and password. The public URL goes into the share sheet or is copied to the clipboard.

Albums get the full set of actions: create, rename, cover photo, remove assets, share link, and delete album (with a confirmation). Extra API permissions: `sharedLink.create`, `sharedLink.read`, `album.update`, `album.delete`.

### Motion & touch (professional, smooth)
- **Performance:**
  - Target 120 Hz with no jank.
  - Stable `key`s and `contentType` on lazy rows.
  - Image size hints so Coil decodes at the cell size.
  - Prefetch neighbouring buckets and pager pages.
  - Baseline Profiles (`androidx.baselineprofile`) for cold start and timeline scroll.
  - Compose compiler stability configured.
- **Grid:**
  - Pinch changes density continuously: rows scale around the focal point, then snap to 3/5/7 columns or the month view with a spring.
  - Long-press selects with haptic feedback; dragging extends the range, and the grid auto-scrolls near the edges.
  - Selected cells shrink slightly and show an animated check.
- **Grid ↔ viewer:**
  - Opening and closing use a shared-element transition (`SharedTransitionLayout` + `sharedElement`) from the thumbnail to full screen.
  - Swipe-down dismiss follows the finger, shrinking and fading the background, then springs back or completes based on velocity.
- **Viewer:**
  - Telephoto handles pinch, double-tap zoom to the tapped point, fling and overscroll bounce.
  - The pager moves with momentum, and the filmstrip stays in sync with the pager.
  - Chrome (top and bottom bars) auto-hides on a single tap with a fade + slide.
  - A pinch-in gesture on a photo returns to the grid.
- **Scrubber:** appears while scrolling; a year/month bubble follows the thumb, with a haptic tick at each month boundary.
- **Editor:**
  - Slider values animate.
  - Rendering is GPU-live while dragging: proxy resolution during the drag, full proxy on release.
  - A light haptic tick when a slider crosses zero.
  - Hold-to-compare crossfades to the original.
  - Crop handles snap to edges and thirds with haptics, and the straighten dial has detents.
- **Brutal motion language:** buttons sink into their shadow (immichup `Pressable`); sheets slide up with a slight overshoot; snackbars bounce in. Every animation respects the system "remove animations" setting.

### Photo editor (M1): `editor/`
- **`recipe/Recipe.kt`** (pure Kotlin, serializable):
  - Geometry: crop, rotate90, straighten, flip, perspective.
  - Light: exposure, contrast, highlights, shadows, whites, blacks.
  - Color: temp, tint, vibrance, saturation, hsl[8].
  - Curve: rgb, r, g, b.
  - Effects: clarity, dehaze, vignette, grain, sharpen.
  - Filter: lut(id, amount).
  - `History` holds an undo/redo stack; slider drags merge into one step.
- **`gl/`** OpenGL ES 3.0 renderer:
  - Pass 1, geometry + color: curves as a 1D texture, LUT as a 3D texture.
  - Pass 2, local contrast: clarity, sharpen and dehaze from a downsampled blur.
  - Preview runs on a `GLSurfaceView` (or a `TextureView` in Compose `AndroidView`) using a ~2048 px proxy.
  - Export renders full resolution in offscreen FBO tiles when the image exceeds `GL_MAX_TEXTURE_SIZE`.
  - The shader code is written so M2 can wrap it as a Media3 `GlEffect`, giving one colour pipeline for photos and videos.
- **`cpu/ReferenceRenderer.kt`:** a small Kotlin port of the same math, used in tests.
- **Decode / export:**
  - Decode with `ImageDecoder` (handles HEIC; for RAW, edit the server preview/fullsize and tell the user).
  - Encode JPEG at q95.
  - Copy EXIF with `ExifInterface`: copy the original's tags, set orientation=1 and the new width/height, keep DateTimeOriginal and GPS.
- **UI:**
  - Dark brutal editor.
  - Tool rail: Crop · Light · Color · Curve · HSL · Effects · Filters.
  - Big sliders with values; double-tap a slider to reset it.
  - Hold the image to see the original.
  - Crop overlay with aspect presets and a straighten dial.
  - Undo/redo in the header.
  - Tablet: side panel. Built-in `.cube` filters in assets.
- **Save dialog** (`editor/SaveEdit.kt`, runs as a WorkManager foreground job). There are two big brutal choices:
  - **Save as copy:**
    1. Upload with the original's `fileCreatedAt`.
    2. `PUT /assets/copy {albums:true, favorite:false, sharedLinks:false, stack:false}`.
    3. "Group with original" (on by default) runs `POST /stacks [new, old]`.
  - **Replace original:**
    1. Upload.
    2. `PUT /assets/copy` (all flags true).
    3. `DELETE /assets {ids:[old]}`, which moves the original to trash.
    4. Show a snackbar "Original moved to Trash · UNDO". Undo restores the old asset and trashes the new one.
  - Either way, `PUT /assets/{newId}/metadata` stores key `photos.recipe` = {sourceId, recipe}, so an edit can be reopened and re-edited later.
- **API key permissions:**
  - Assets: asset.read, asset.view, asset.download, asset.upload, asset.update, asset.delete, asset.copy, asset.edit.get.
  - Albums: album.read, album.create, albumAsset.create, albumAsset.delete.
  - Stacks: stack.read, stack.create, stack.update, stack.delete.
  - Other: person.read, map.read, memory.read, memory.update, user.read, server.about, tag.read.

### Milestone 2: video editor
- Trim strip with frame thumbnails, split, crop/rotate, speed, mute/volume.
- The same colour recipe and filters, previewed live in ExoPlayer with a GL effect.
- Export with Media3 Transformer in a foreground worker with progress.
- Same Replace / Copy save flow.

### Milestone 3 (stretch): masks + AI
- Brush, radial and linear masks; healing brush; on-device subject/sky masks with ML Kit Subject Segmentation.
- "Edit with a prompt" with **Gemini `gemini-nano-banana-2.1`**:
  - uses a user-supplied API key kept in encrypted prefs;
  - the image plus a mask-guided prompt are sent, with `store=false`;
  - the result is labelled AI (SynthID watermark).
- iOS: move `api/domain/recipe` into a KMP module and use Compose Multiplatform for the UI.

## Execution (me coordinating sub-agents)
1. **Scaffold** (1 agent):
   - Gradle project matching immichup's toolchain; install the Android cmdline-tools/SDK 35 into the session (Java 17 and Gradle 8.14.3 are present; the SDK is missing).
   - Port the theme, components and API base; `AppContainer`; nav shell.
   - Get `./gradlew assembleDebug testDebugUnitTest` green; commit + push.
2. **In parallel** (3 agents, separate packages):
   - (a) connection manager, connect/settings screens and tests;
   - (b) timeline domain, grid, scrubber, selection, and the viewer;
   - (c) editor `recipe` + `gl` + `cpu` reference + EXIF, with unit tests.
3. **In parallel** (2 agents):
   - (d) albums/search/people/map/memories/trash, plus the viewer and selection actions (favourite, add to album or new album, share file and share link);
   - (e) editor UI + save flow.
4. A `/code-review` agent, then fixes. I integrate, commit and push to `claude/sleepy-carson-yvl7u0` after each step.

## Verification
- **Unit tests (JUnit + MockWebServer):**
  - subnet math and the same-server `/users/me` match;
  - URL selection/fallback state machine and the interceptor rewrite;
  - columnar bucket mapping and justified layout;
  - recipe history merging;
  - CPU reference vs known pixel values;
  - EXIF transplant;
  - save-flow request order: upload → copy → (stack | trash), and the undo path.
- **Robolectric screenshots** (`-Pscreenshots`, as in immichup): connect, timeline, viewer, editor tools, save dialog, trash. These are sent to the user for UI review.
- **Release APK** (`assembleRelease`, debug-signed like immichup) sent to the user.
- **On-device checklist:**
  - log in via photos.royle.au at home → the LAN server is found automatically and the chip shows HOME;
  - leave WiFi → REMOTE with no errors;
  - edit → Replace → the original is in Immich trash → Undo restores it;
  - Save as copy → it is stacked with the original.
