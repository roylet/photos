# Sources: phone, Immich, and the seams between them

Milestone 3a makes the app a phone gallery first, with Immich as an optional cloud. The
foundation (agent A) adds the source model, capabilities, permissions and the optional server.
The MediaStore source (agent B) and the merged timeline and editors (agent C) plug into the seams
below. Packages are under `app/src/main/java/com/roylet/photos/`.

## 1. Asset ids (`domain/source/Sources.kt`)

| Source | Tagged form | Id used in the app (`TimelineAsset.id`, nav args, URLs) |
|---|---|---|
| Phone (MediaStore) | `local:<_ID>` | `local:<_ID>` (always tagged) |
| Immich | `immich:<uuid>` | the bare `<uuid>` (unchanged since before 3a) |

- `AssetRef.parse(id)` reads either form. A bare id, or one with an unknown prefix, is Immich, so
  old deep links, the Immich code and tests keep working. It returns null only for `""` or `local:`.
- Helpers: `AssetRef.local(id)`, `AssetRef.immich(uuid)`, `.encode()`, `.id`, `.mediaStoreId`,
  `AssetRef.kindOf(id)`, `AssetRef.isLocal(id)`, `AssetRef.immichIdOrNull(id)`.
- **Merged items (agent C).** `TimelineAsset.localRef` and `remoteRef` hold both copies. Set `id`
  to the copy the viewer should open: the local one when it exists, because it's faster.
  `TimelineAsset.sourceBadge` gives `LocalOnly`, `RemoteOnly` or `Both` from the refs, or from
  `id` when no refs are set.
- **Folders (agent B).** Use album ids `folder:<BUCKET_ID>` in `TimelineFilter.albumId` and
  `Album.id`.

## 2. Capabilities

`Capability` has these flags: SHARE_LINKS, PEOPLE, MAP, SMART_SEARCH, ARCHIVE, STACKS,
SERVER_ALBUMS, FOLDERS, TRASH, FAVORITE, MEMORIES and EDIT_SAVE. `Capabilities` is a value-class
bit set. It supports `in`, `+`, `-`, `union` and `intersect`.

- **Presets.** `Capabilities.Immich` is every flag except FOLDERS. `Capabilities.Local` is
  FOLDERS, TRASH and FAVORITE. When agents B and C deliver memories or editor saves for phone
  photos, they override `LocalLibrary.capabilities`: they don't edit the preset.
- **Source.** Each `LibrarySource` reports `val capabilities`.
- **Active set.** `AppContainer.sources()` returns an `ActiveSources(kinds, capabilities)`.
  AppNav provides it as `LocalActiveSources` and its capabilities as `LocalCapabilities` (in
  `ui/sources/LocalSources.kt`). The merged library (agent C) should report
  `Capabilities.Local union Capabilities.Immich` and both kinds.
- **Per asset.** `Capabilities.forAsset(id, localRef, remoteRef)` drops `NeedsRemoteAsset` for
  phone-only items. That set is share links, archive, stacks, server albums and people. It also
  drops FOLDERS for items only on Immich.
  - `assetCapabilities(asset)` applies this in the viewer.
  - `forIds(ids)` applies it to selections and the share sheet. It only sees ids, so for merged
    items agent C should look up the refs, or keep the merged item's id tagged as local.
- **What's gated today:**
  - Tabs (`tabsFor`): Albums needs SERVER_ALBUMS or FOLDERS.
  - Library tiles.
  - Search: without SMART_SEARCH it calls `LibrarySource.basicSearch` and hides people, places
    and things.
  - Viewer: favourite, add to album, edit (EDIT_SAVE), delete, archive.
  - Selection bar and the share sheet's link mode.
  - Album menu and album selection actions: hidden for read-only folders.
  - "Group with original" in both save sheets (STACKS).
  - The Trash note: shown only when `trashDays()` returns a number.
  - The ConnectionChip: shown only when an Immich source is active.

## 3. Interfaces to implement (`domain/library/LocalLibrary.kt`)

### `LocalTimelineSource : TimelineSource` (agent B: MediaStore)

- Filters:
  - `Timeline`: everything not trashed. Ignore `visibility`.
  - `isFavorite`: IS_FAVORITE.
  - `isTrashed`: `QUERY_ARG_MATCH_TRASHED`.
  - `albumId`: `folder:<bucket>`.
- Bucket keys use Immich's format, `yyyy-MM-01T00:00:00.000Z`.
- Implemented by `local/MediaStoreTimelineSource` (section 8). `EmptyLocalTimelineSource` stays
  for tests. `container.localTimeline` defaults to `container.device.timeline`; assigning it
  pins another one.
- The container routes the registry to it whenever there's no server, and with a server for
  filters where `TimelineFilter.isLocal` holds: `source = MediaSourceKind.Local`
  (`TimelineFilter.Device`, `DeviceTrash`) or a `folder:` album.

### `LocalLibrary : LibrarySource`

- Defaults already cover the server-only methods: they throw `UnsupportedCapabilityException` or
  return empty lists. `trashDays()` returns null.
- You implement these: `setFavorite`, `details`, `albums`/`album` (folders), `trash`, `restore`,
  `deleteForever`, `restoreAll`, `emptyTrash`, `assets`, `download` (or share the content URI
  directly) and `basicSearch` (file name, folder or date).
- Implemented by `local/MediaStoreLibrary` (section 8). `EmptyLocalLibrary` stays for tests.
  `container.localLibrary` defaults to `container.device.library`.
- `container.library` returns, with a server, a `MergedLibrary` (section 9), else the local one.
  Tests can pin a fake by assigning `container.library = ...`.
- `LibrarySource.shareUri(id)` (default null) returns a `SharedUri` for items other apps can
  read directly; `LibraryActions.sendFiles` shares those without downloading.
- `ConsentDeniedException` is thrown when the user declines a MediaStore dialog; LibraryActions
  rolls back.

### Favourite

Favourite always goes through `LibrarySource.setFavorite`. The old `favoriteCall` bypass is gone.
`LibraryActions` does the optimistic update and rollback.

### Info sheet

`LibrarySource.details(id): AssetDetails?` returns the source-neutral fields: date, file, size,
dimensions, camera and EXIF, location, people and folder. Immich maps them with
`AssetDto.toDetails()` (`immich/ImmichDetails.kt`). Agent B fills them from MediaStore columns and
ExifInterface (it needs ACCESS_MEDIA_LOCATION and `MediaStore.setRequireOriginal` for GPS).

## 4. Permissions and consent (`ui/permissions/`)

- **Access level.** `MediaPermissions` returns `access(context)` as Full, Partial or Denied. It
  also provides `requested(sdk)`, `isPermanentlyDenied(...)`, `canManageMedia(context)`,
  `manageMediaIntent(context)` (ACTION_REQUEST_MANAGE_MEDIA, Android 12+) and
  `appSettingsIntent(context)`.
  - Never cache the level.
  - In the UI, `LocalMediaPermissions` gives a `MediaPermissionsState` that's re-checked on every
    ON_RESUME.
  - `state.request { access -> }` shows the system prompt. With partial access it shows the
    re-selection UI instead.
- **Screens.**
  - `MediaAccessExplainer`: the `media-access` route, reached from onboarding.
  - `MediaAccessDenied`: the timeline's GRANT ACCESS / OPEN SETTINGS state.
  - `LimitedAccessBanner`: "Limited access · MANAGE".
  - `DeviceLibraryEmpty`.
  - Settings, then Accounts, then This device: access status, plus "Skip confirmation dialogs".
- **Consent launcher.** `container.consent` is a `ConsentLauncher`; inside composition it's also
  `LocalConsentLauncher`.
  ```kotlin
  val ok: Boolean = container.consent.requestConsent(MediaStore.createTrashRequest(resolver, uris, true))
  ```
  - One app-wide `StartIntentSenderForResult`, attached by `ConsentHost` in AppNav.
  - Requests run one at a time. The call suspends until the user answers.
  - It returns false if no screen attaches within 5 s, so ask before enqueuing a worker, never
    from inside one.
  - Use it for `createFavoriteRequest`, `createTrashRequest`, `createDeleteRequest`,
    `createWriteRequest` and Android 10's `RecoverableSecurityException.userAction`.
  - With `MediaPermissions.canManageMedia()` the system skips these dialogs.
- **Manifest.**
  - READ_MEDIA_IMAGES, READ_MEDIA_VIDEO and READ_MEDIA_VISUAL_USER_SELECTED.
  - READ_EXTERNAL_STORAGE (maxSdk 32).
  - WRITE_EXTERNAL_STORAGE (maxSdk 29), for Android 10's app-private trash fallback.
  - ACCESS_MEDIA_LOCATION and MANAGE_MEDIA.

## 5. Pixels: MediaUrls, Coil and ExoPlayer

- **URLs.** `AppContainer.mediaUrls()` builds a `SourceMediaUrls` (in `ui/gallery/Media.kt`).
  It sends `local:` ids to `container.localMediaUrls` and everything else to `ImmichMediaUrls`,
  or to `FakeMediaUrls` without a server. Delegates get the bare key: the MediaStore id or the
  UUID. Agent B sets `container.localMediaUrls` to a provider of `content://` URIs. Screens read
  URLs through `LocalMediaUrls`.
- **Coil.** `AppContainer.imageLoader` has an OkHttp fetcher and a video frame decoder.
  - Add a fetcher for `content://` thumbnails (`ContentResolver.loadThumbnail`) in its
    `components {}`.
  - `AssetImage` uses the URL as the memory-cache key.
- **ExoPlayer.** `ui/viewer/VideoPlayer.kt` builds `DefaultMediaSourceFactory(OkHttpDataSource.Factory(client))`.
  - Wrap it in `DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client))` so
    `content://` plays. `video/preview/VideoPreviewController.kt` already does this.
  - `ui/viewer/VideoFrames.kt` (scrub strips) also uses `LocalMediaHttpClient`.
- **Editors (agent C).**
  - `EditSaveManager` and `VideoSaveManager` take `{ api() }`. `SourceLoader` and the
    video-originals cache download from Immich.
  - The `EditSource` and `SaveSink` seams go there.
  - The viewer hides Edit until the active capabilities contain EDIT_SAVE.

## 6. Optional server

- **Onboarding.** `Settings.onboarded` is set by either choice on the onboarding screen, or by
  saving a server, so existing users skip it. AppNav starts at `onboarding` until then, and
  otherwise at `timeline`. It never forces Connect.
- **Session key.** `sessionKey()` is `"device"` without a server, or `"immich:<userId>"`.
  Connecting or disconnecting drops the caches and returns to Photos.
- **Accounts.** Settings, then Accounts, then Immich offers CONNECT (to the `connect` route,
  which has a back button) or DISCONNECT (sign out). The server and connection sections only
  appear with a server.

## 7. Casting prerequisites (3b)

- **Why a session token.** Casting uses Immich's own approach. You create short-lived child
  sessions with `POST /api/sessions` and give the Chromecast `?sessionKey=` URLs. That endpoint
  refuses API keys: it needs a session (bearer) token.
- **Where it comes from.** The email+password sign-in keeps the `accessToken` from
  `/auth/login` as `ServerConfig.castSessionToken`.
- **How it's stored.**
  - It's encrypted with AES-256-GCM using a non-exportable Android Keystore key
    (`data/TokenCipher.kt`). The ciphertext goes in the separate `secrets` prefs file.
  - If the Keystore fails, the token is dropped rather than stored in plain text.
  - It's cleared on sign-out.
  - It's redacted from `toString()`. Never log it.
- **Reading it.** `AppContainer.serverSessionToken()` returns it. It returns null without a
  server and after an API-key sign-in (that path has no session). In that case the cast UI
  should explain that casting needs an email sign-in.

## 8. Phone index (agent B, `local/`)

### Room index (`MediaDatabase.kt`, `media-index.db`)

- `media`: one row per MediaStore image/video. Columns: `id` (`_ID`), `isVideo`, `takenAt`
  (UTC ms: DATE_TAKEN, else DATE_ADDED × 1000, else DATE_MODIFIED × 1000), `month`
  (`yyyy*100+MM` in the device zone), `offsetMinutes`, `width`/`height`/`orientation`
  (`ratio` applies it), `durationMs`, `mimeType`, `displayName`, `sizeBytes`, `bucketId`,
  `bucketName`, `relativePath`, `volume`, `favorite`, `trashed`, `expiresAt` (DATE_EXPIRES ms),
  `generation` (GENERATION_MODIFIED; DATE_MODIFIED on 10).
- **`sha1` / `sha1Generation` (agent C).** Nullable. A hash is valid only while
  `sha1Generation == generation`; the indexer keeps a valid one across syncs and drops it when
  the item changes. Use `MediaDao.needingSha1(limit)` for the queue (newest first) and
  `MediaDao.setSha1(id, hex, generation)`, which only writes if the row is still at that
  generation (returns 0 otherwise). Read the bytes with `mediaUri(id, isVideo)`.
- `index_meta`: version, access level, zone, per-volume generation.
- `immich_match` (v2): the matching cache (section 9).
- Android 10's `legacy_trash` / `legacy_favorite` moved to `photos-app.db` in v2 (section 9).
- Schemas are exported to `app/schemas/`. Every known version has a real migration
  (`Migration1To2`); only a downgrade rebuilds the index, which holds nothing that can't be
  recomputed.

### Sync (`MediaIndexer`)

- Full sync: first run, `MediaStore.getVersion()` changed, access level changed (partial grants
  show a different set), or Android 10 (no generations). It reads every row and rewrites only
  rows that differ.
- Incremental: rows with GENERATION_MODIFIED above the last stored generation, per volume.
- Deletions: `_ID` set diff, every sync.
- No access: nothing is queried and the index is wiped.
- Device zone change: months are re-bucketed.
- Writes are in batches of 500, one transaction each (50k rows take about a second in tests).
- Triggers: `DeviceMedia.start()` (from `PhotosApp`) syncs on every return to the foreground
  and registers a ContentObserver (debounced 800 ms) while there. `device.onAccessChanged()`
  forces a full sync (AppNav calls it when the permission level changes or the selection is
  edited).
- `MediaIndexer.sync()` returns a `SyncResult`; when it changed anything,
  `AppContainer.onDeviceChanged` refreshes the shown phone timelines and folder lists.
- `MediaStoreReader` is the seam for tests (`FakeMediaStore` in `src/test/.../local/`).

### Library behaviour (`MediaStoreLibrary`)

- Android 11+: `MediaRequest.Favorite/Trash/Delete`, chunked at 500 URIs, become
  `createFavoriteRequest` / `createTrashRequest` / `createDeleteRequest` in
  `ConsentRequestRunner`, through `container.consent`. The index is updated right after.
- After the first dialog, `ManageMediaOffer` shows a one-time "Skip dialogs" snackbar
  (`Settings.offeredManageMedia`) opening ACTION_REQUEST_MANAGE_MEDIA.
- Android 10: trash copies the original to `files/trash/` (`AndroidLegacyFiles`), deletes it
  (RecoverableSecurityException consent) and lists it with id `local:-<rowId>`. Restore inserts
  it back at its RELATIVE_PATH with IS_PENDING. Favourites live in `legacy_favorite`. Items are
  purged after 30 days on app start.
- Folders: `folder:<BUCKET_ID>`, read-only, newest first, cover = newest item.
- `basicSearch`: file name, folder or path (LIKE), and dates (`DateQuery`: "2024",
  "March 2023", "2023-03", "May").
- Details: MediaStore columns plus ExifInterface. It uses `setRequireOriginal` when
  ACCESS_MEDIA_LOCATION is granted, so GPS is included. Videos use MediaMetadataRetriever for
  location.
- `capabilities` = `Capabilities.Local + EDIT_SAVE` (edits save next to the original, section 9).

### Pixels

- `LocalMediaUrls` (bare MediaStore id in):
  - thumbnail: `localmedia://thumb/<id>?v=<0|1>&g=<generation>`;
  - preview: `localmedia://full/...` for images, `thumb` for videos;
  - fullsize, original and video: `content://media/external/{images|video}/media/<id>`;
  - Android 10 trash: `file://` of the private copy.
- The generation in the URL is the memory-cache key. MediaStore caches thumbnails on disk.
- `LocalAssetInfo` remembers kind and generation for every asset the index hands out.
- `LocalThumbnailFetcher` (in the image loader) serves `localmedia://`:
  - `thumb` uses `ContentResolver.loadThumbnail`, cancelled through a CancellationSignal when
    the request is cancelled;
  - `full` decodes the content URI with sampling and EXIF rotation.
- ExoPlayer (`rememberPlayer`) uses `DefaultDataSource.Factory(context, OkHttpDataSource...)`.
- Scrub frames use `extractFrames(context, client, url, ...)`: `content://` goes through
  `MediaMetadataRetriever.setDataSource(context, uri)`, http through Range requests.
- Share: `AndroidShareLauncher.send(files, uris)` grants read access to the content URIs.

### UI

- No server: the Photos tab, Albums (Folders), Favorites and Trash read the phone.
- Library → ON THIS DEVICE (with or without a server, `ui/library/DeviceFolders.kt`): a row of
  the phone's folders, VIEW ALL for the grid (`device/folders`), each folder on `album/folder:<id>`
  (`DeviceFolderScreen`: the folder's timeline, name, count and path, ⋯ menu). Without access a
  GRANT ACCESS card; with partial access a MANAGE card above the row. With a server the grid's
  header also opens `device` (the phone timeline) and `device/trash` (`container.deviceActions`).
  - `container.phoneFolders` (`DeviceFolderStore`, `domain/library/DeviceFolders.kt`) loads
    `MediaStoreLibrary.deviceFolders()`: one grouped query (`MediaDao.folders()`, the cover's
    kind and generation from the newest row), refreshed by `onDeviceChanged`.
  - Order: Camera (DCIM/Camera), Screenshots, then newest item first. Friendly names and icons
    for Camera, Screenshots, Screen recordings, Downloads, WhatsApp, Telegram, Instagram,
    Snapchat, Pictures, Movies and Edited (`Photos Edited`). Same-name folders get a path hint.
  - `FolderPrefs` (in `UiPrefs`): hidden from Library, and Show in Photos per folder over the
    Settings mode (All folders / Camera + selected). An item shows in Photos only if its folder is
    not hidden and Show in Photos is on (`FolderPrefs.inPhotos`); a hidden folder's toggle is
    disabled. The Photos timeline (`TimelineFilter.Timeline`, and the merged one through it) leaves
    out hidden folders and folders turned off (`MediaDao.monthsExcluding`); Favorites and the
    phone's `basicSearch` leave out hidden folders. Merged, a hidden item that Immich also has shows
    as its Immich copy (no phone badge). Hidden folders stay on VIEW ALL → Hidden and their folder
    page; backup ignores hiding. Every change reloads Timeline and Favorites
    (`AppContainer.PHONE_FOLDER_FILTERED`) and resets the merged counts.
  - With a server every timeline goes through `MergedTimelineSource`; phone-only ones (folders,
    `device`) get their cached Immich copies filled in, so the backup badges apply there too.
- The grid shows "N days" on trashed items with `TimelineAsset.expiresAt`.
- Screenshots are in `DeviceScreenshotTest` (`device-*.png`).

### Needs a device

- Consent dialogs and the MANAGE_MEDIA skip.
- Android 10's RecoverableSecurityException flow.
- loadThumbnail speed on a 50k library.
- SD-card volumes.
- Partial-access re-selection.

## 9. Merged timeline, matching and phone saves (agent C)

Packages: `merge/` (matching, merged timeline and library), `local/` (hashing, databases, the
phone save sink), `editor/save/` (the editor seams).

### Databases

| File | What | Rule |
|---|---|---|
| `media-index.db` (`MediaDatabase`, v2) | `media`, `index_meta`, `immich_match` | A cache. Every version has a migration (`Migration1To2`); only a downgrade rebuilds it. |
| `photos-app.db` (`PhotosDatabase`, v2) | `legacy_trash`, `legacy_favorite`, `local_edit`; v2 adds backup's `backup_folder`, `backup_failure`, `backup_exclusion` (§10) | Can't be recomputed. No destructive fallback, ever: each bump adds a `Migration` to `PhotosDatabase.MIGRATIONS` and a test in `DatabaseMigrationTest`. |

- The 1 → 2 migration reads v1's Android 10 rows and hands them to `PhotosDatabase.importLegacy`
  (keeping `rowId`, so `local:-<rowId>` still points at the same private copy) before dropping
  them. If the hand-over throws, the migration fails and the v1 rows stay.
- `AppDao` holds Android 10's trash and favourites (`MediaStoreLibrary`, `MediaStoreTimelineSource`
  and `MediaIndexer` take it) and the edit table.

### SHA-1 (`local/Sha1Index.kt`)

- `Sha1Hasher` takes `needingSha1` in batches of 25, newest first. It streams each original
  through SHA-1 (`setRequireOriginal` when ACCESS_MEDIA_LOCATION is granted, else GPS is
  redacted and the hash differs from Immich's) and stores it with `setSha1`.
  - A changed item (0 rows updated) comes round again at its new generation.
  - Unreadable items are skipped for the run.
- `Sha1Worker`: the unique work `sha1-index` (KEEP), with `setRequiresBatteryNotLow`, not
  expedited.
  - Resumable: every hash is stored as it's made. A stop returns `retry`.
  - Scheduled by `DeviceMedia.scheduleHashing()` on foreground, after syncs that changed
    something, and when the server changes. It's cancelled without a server or access.
- Progress: `MediaDao.sha1Counts()` → `DeviceMedia.matching`. It shows in Settings, Accounts,
  "Phone and Immich" as "Matching with Immich: 1,234 / 5,000". The same card has
  "Show backup badges" (`UiPrefs.showBackupBadges`).

### Matching (`merge/ImmichMatcher.kt`)

- The bucket DTO has no checksum. Two ways in:
  - Per month, `POST /search/metadata` with `takenAfter/takenBefore` (±1 day for time zones,
    paged) returns name, size and the base64 checksum. It's fetched once per session per month,
    only when a phone item needs an answer.
  - After each hashing batch, `POST /assets/bulk-upload-check` (batches of 500) confirms SHA-1 →
    asset id. A trashed Immich copy counts as no match.
- `Matching.pair` (pure): SHA-1 first (confirmed, or no match), then the guess for items not
  hashed yet: same name (case-insensitive), taken within ±2 s, same size when both know it. A
  guess is `tentative`.
- `immich_match` caches one row per phone item: `(localId, generation, remoteId?, tentative,
  checkedAt)`.
  - It's valid only at the item's current generation.
  - A cached Immich id that's no longer in the month is re-checked.
  - A tentative row is redone once the item has a hash.
  - "Immich doesn't have it" is re-asked after 6 h.
  - `forgetRemote` runs when Immich copies are trashed. `clear()` runs on a server or account
    change.

### Merged timeline (`merge/MergedTimelineSource.kt`)

- With a server, `container.mergedTimeline` serves every filter that isn't `isLocal`.
- **Timeline:** per month, the phone and Immich lists are fetched in parallel and matched
  (`MonthMatcher.matchMonth`).
  - A pair becomes one asset: `id` is the phone's (the viewer opens the local file), with
    `localRef` + `remoteRef`, favourite if either side is, and Immich's thumbhash, place and stack.
  - Unpaired items stay as they are. Months on one side only are just that side.
- **Counts:** before a month loads, `phone + Immich − known pairs` (`MatchDao.pairsPerMonth`).
  Once loaded, the merged size, which the snapshot uses anyway.
- **Favourites:** the union. A cached match fills in the other ref.
- **Trash:** both trashes, unmerged; badges tell them apart.
- **Archive, albums, people, tags:** Immich only.
- If Immich can't be reached, the phone's side shows alone.

### Merged library (`merge/MergedLibrary.kt`, replaces SourceRoutingLibrary)

- Capabilities: `Local union Immich`. Other copies come from the matcher's cache.
- **Favourite:** both copies, the phone first (a declined dialog leaves Immich alone).
- **Delete** (`TargetedTrash`):
  - `LibraryActions.delete` asks with `ActionSheet.DeleteChoice` ("Delete from: This device /
    Immich / Both") when a selection has paired items. The answer is remembered per session
    (`LibraryActions.deleteTarget`).
  - `MergedLibrary.plan` routes: one-sided items always go from their own side.
  - Each side goes to its own Trash. Undo restores the `TrashReceipt`. If Immich fails, the phone
    half is put back.
  - After a one-sided delete of a pair, the timelines reload so the other copy shows on its own.
- **Albums:** Immich's, then the folders. The Albums tab shows them under "On this device".
- **Server features** (albums, links, archive) use the Immich copy and skip phone-only items.
- **Search:** smart search plus phone file names not already shown.
- **Details:** the phone's EXIF wins; Immich adds people, place, description and camera when the
  phone has none.
- **Share and download:** the phone file when there is one.

### Grid and viewer

- `SourceMark` sits top-right on each cell: a crossed-out cloud for "only on this device" and a
  cloud for "only in Immich"; nothing for both. It shows only while both sources are active and
  the setting is on (`LocalShowSourceBadges`, provided by AppNav).
- In the viewer, a paired photo whose phone file fails to load falls back to the Immich copy
  (photos only; videos don't fall back yet).

### Editor saves (`editor/save/EditSource.kt`, `SaveSinks.kt`, `local/LocalSaveSink.kt`)

- **`EditSource`** (`info`, `metadata`, `obtain`): `ImmichEditSource` (as before) and
  `LocalEditSource` (index row → `EditAssetInfo`, the saved edit from `local_edit`, a copy of the
  content URI). `RoutingEditSource` picks by id.
  - Both editors use it, and so do `VideoOriginals` and `VideoSaveJob`.
  - The old decoded-photo class `EditSource` is now `LoadedEdit`.
- **`SaveDestination`:**
  - Phone-only items: DEVICE.
  - Immich items: IMMICH.
  - Paired items: BOTH (the default), DEVICE or IMMICH, picked in the save sheet's "Save to" row.
  - BOTH = the phone sink, then the existing Immich flow on the item's Immich copy.
    `SaveResult.companion` carries the second result.
- **`LocalSaveSink`:**
  - Inserts with IS_PENDING into the original's RELATIVE_PATH (else `Pictures/Photos Edited/` or
    `Movies/Photos Edited/`) as `<name>-edited.jpg|mp4`, with DATE_TAKEN and IS_FAVORITE.
  - Writes the file (EXIF already transplanted by JpegExporter), then sets IS_PENDING = 0. A
    failed write deletes the pending row.
  - **Replace:** trashes the original after the insert. If Android refuses, it saves as a copy
    with a warning.
  - Stores the recipe or video edit in `local_edit` under the new id.
  - Resumable through `LocalSaveCheckpoint`, kept in the job spec.
  - **Undo:** restores the original and trashes the edit.
- **Consent before enqueue** (`SaveStarter`): a phone Replace asks before the job is queued, with
  `MediaStore.createWriteRequest` for the original through the app-wide `ConsentLauncher`.
  MANAGE_MEDIA approves it without a dialog. A "no" queues nothing.
  - The worker then sets IS_TRASHED itself (`AndroidLocalTrasher`), which the write grant allows.
    A `createTrashRequest` from the UI would trash at once, before the edit exists.
  - Android 10 has no such request: its trash (the app-private copy) asks when the save reaches it.
- Photo saves to the phone alone need no network constraint.
- "Original moved to Trash · UNDO" works for phone, Immich and both (`SaveResult.parts`).

### Needs a device

- `createWriteRequest` before Replace, the MANAGE_MEDIA skip, and IS_TRASHED from the worker
  after the grant (including after a process restart).
- Android 10's Replace (the RecoverableSecurityException dialog mid-save).
- `bulk-upload-check` and `search/metadata` checksums against the real server.
- Hashing speed and battery use on a 50k library, and the low-battery stop and resume.
- Badges after a real backup.
- The viewer's fallback when a phone file is deleted elsewhere.
- Migrating a real v1 index that has Android 10 trash rows.
- Inserting into unusual RELATIVE_PATHs (SD cards, Download/).

## 10. Backup (`backup/`, `ui/settings/BackupScreen.kt`)

Phone folders → Immich, in the background, Google Photos style. Backup only adds to Immich:
nothing is ever deleted on either side.

### API (`container.backup: BackupManager`)

| Call | What |
|---|---|
| `state: StateFlow<BackupState>` | `Off`, `NoServer`, `Idle(pending)`, `Scanning`, `Uploading(current, total, name, fraction)`, `WaitingForWifi(n)`, `WaitingForCharging(n)`, `Paused`, `Error(message, signIn)` |
| `options: StateFlow<BackupOptions>` | `enabled` (off), `wifiOnly` (on), `whileChargingOnly` (off), `includeVideos` (on), `newerThan` (null = everything) |
| `setFolderEnabled(bucketId, on)` | Per-folder toggle (turning one on starts a run). |
| `isFolderEnabled(bucketId): Flow<Boolean>` | A stored choice, else the default: Camera (`FolderKind.Camera`) on, the rest off. |
| `folderStatus(bucketId): Flow<FolderBackupStatus>` | `enabled`, `eligible`, `backedUp`, `pending`, `failed`, `excluded`. |
| `folders: StateFlow<List<BackupFolder>?>` | Every folder with cover and status, in Library's order. |
| `backUpNow()`, `pause()`, `resume()`, `cancelRun()` | Runs; pause persists until resume. |
| `setEnabled/setWifiOnly/setWhileChargingOnly/setIncludeVideos/setFromNowOn` | Options. |
| `failures`, `exclusions`, `retryFailed()`, `allowBackupAgain(localId)` | The two lists on the Backup screen. |
| `uploadingId: StateFlow<Long?>` | The item uploading now (the grid's up-arrow cloud via `LocalBackupUploading`). |

- **UI wiring.** `ProvideFolderBackup(container, onOpenBackup)` provides `LocalFolderBackup`
  (`FolderBackupUi`: statuses, `active`, `onToggle`, `onOpenBackup`) on the Library, VIEW ALL and
  folder routes. `ui/library/DeviceFolders.kt` reads it: BACKUP in the ON THIS DEVICE header, a
  cloud mark on enabled folder cards (check = done, up arrow = pending, crossed = backup off),
  "Back up" in the ⋯ menu and on the folder page header. Null hides all of it.
- **Routes.** `backup` (Settings → Backup card, and Library's BACKUP).

### Storage

- `photos-app.db` v2 (`AppMigration1To2`): `backup_folder` (choices), `backup_failure`
  (permanent per-item failures, at a generation), `backup_exclusion` (never re-upload: id + SHA-1).
- Options in the `backup` prefs (plus the auth pause and "asked for notifications").
- What's backed up = a confirmed (`tentative = 0`) `immich_match` row at the item's current
  generation. Uploads write that row, so the merged timeline shows "Both" at once
  (`ImmichMatcher.noteUploaded()` drops the month cache, then `onDeviceChanged` refreshes).

### Engine (`BackupEngine`, `BackupWorker`)

- Jobs: `backup-periodic` (hourly, UPDATE), `backup-run` (one-off, KEEP: Back up now, index
  changes, a folder switched on), `backup-trigger` (content-URI trigger on MediaStore images and
  video, re-armed each time, so new photos start a run with the app closed). Constraints:
  UNMETERED for Wi-Fi only, `setRequiresCharging`, battery not low. One run at a time
  (`BackupManager.runLock`).
- A run syncs the index, then per batch of 50 (newest first, `BackupMediaDao.pending`: never
  trashed items, only selected folders, videos and `newerThan` applied): hash missing SHA-1s
  (stored like the hasher's) → `bulk-upload-check` → stream-upload the rest one at a time with
  `ImmichApi.upload` on the shared client (LAN when home; the connection manager's one-shot
  fallback applies) with `x-immich-checksum`, `fileCreatedAt` = taken, `fileModifiedAt` =
  DATE_MODIFIED, the original file name and `isFavorite`. "created" and "duplicate" both count.
- **Device ids.** Immich's current `AssetMediaCreateDto` has no `deviceAssetId`/`deviceId`;
  backup leaves them out (`UploadSpec.deviceAssetId = null`) and keeps MediaStore id → asset id
  in the match cache. `BackupPrefs.installId` exists for diagnostics only.
- **Errors.** IO, 408/429/5xx → retry with backoff (30 s exponential, 8 attempts, then the hourly
  job). 401/403 → paused with a "Sign in to keep backing up" notification until the next sign-in.
  Other 4xx (415, 400, 413) and unreadable files → `backup_failure`, skipped until the item changes.
  A file that changes size mid-upload is skipped for the run.
- Resumable: each result is stored as it happens; a stopped run returns `retry` and the next
  starts at the next item.
- Foreground with "Backing up 3 of 120" and Pause / Cancel (`BackupActionReceiver`) while
  uploading. A refused foreground start (Android 12+ background) runs as normal work.
- Live/motion photos go up as the still image only.

### Deletions

- The merged library tells `RemoteDeletionListener` (the manager) when a confirmed pair loses
  only its Immich copy ("Immich" in the delete sheet): the phone item becomes an exclusion
  (`Deleted from Immich`), so it isn't re-uploaded even after Immich empties its Trash. Undo or a
  restore from Immich's Trash removes it. "Allow" on the Backup screen does too.
- `bulk-upload-check` finding the file in Immich's Trash (deleted on the web) also excludes it
  (`In Immich's Trash`). Restore All on Immich's Trash doesn't clear exclusions (no ids).
- The delete sheet: for items in a backed-up folder, "Immich" asks once more ("It won't be backed
  up again. Delete from phone too?", Both highlighted); a remembered "Immich" asks again there.

### Needs a device

- Foreground start limits (Android 12+, 14's dataSync quota), the content-URI trigger with the
  app closed, Doze, and the notification's Pause / Cancel.
- Real uploads over LAN and public, the LAN→public switch mid-upload, multi-GB videos.
- `bulk-upload-check` on a trashed asset, and 401 after the key is revoked.
- Notification permission on first enable (Android 13+).

## 11. Casting (`cast/`, `ui/cast/`)

Chromecast to Google's **Default Media Receiver** (`CC1AD845`), modelled on Immich's own apps
(mobile `gcast.service.dart`, web `cast-manager.svelte.ts`). No custom receiver, nothing to register.

### Pieces

- `PhotosCastOptionsProvider` (manifest `OPTIONS_PROVIDER_CLASS_NAME`, kept by R8): the receiver
  id, the Cast SDK's own media session and notification off, Output Switcher (remote-to-local) on,
  the receiver closed when the session ends.
- `Media3CastDevice` (`container.castDevice`, `init()` from MainActivity): Media3 1.9's
  `RemoteCastPlayer` plus the Cast SDK's session and device-state listeners, behind the
  `CastDevice` seam. **CastPlayer, not raw RemoteMediaClient:** Media3's
  `DefaultMediaItemConverter` has no photo type (an `image/*` item goes out as a movie), so
  `PhotoMediaItemConverter` sends image items as `MEDIA_TYPE_PHOTO` (BUFFERED, as Immich does)
  and leaves videos to the default. One item is loaded at a time; the phone's timeline is the queue.
- `CastService` (`MediaSessionService`, `mediaPlayback`): runs only while a session is connected.
  Its Media3 `MediaSession` around the remote player gives the lock screen, notification and
  Output Switcher; `MediaTransferReceiver` is in the manifest. It starts the relay on create.
- `CastController` (`container.cast`, main thread): `show(asset)` (the viewer calls it whenever a
  page settles while casting), transport (`togglePlay`, `seekTo`, `setVolume`), `stop()`,
  slideshows, and the clean-up when the session ends: the Immich child session deleted, the relay
  and the service stopped. `state: StateFlow<CastState>` drives every cast UI. Receiver errors are
  worded ("TV couldn't load this video (HEVC not supported)", same Wi-Fi, ...).

### Where the TV fetches from (`CastResolver`, `CastRouting`, `CastUrls`)

| Item | Route |
|---|---|
| Immich (or merged, with `remoteRef`) and a public HTTPS address | **Direct**: the TV fetches from the server |
| Phone-only (`local:`) | **Relay**: the phone's `LocalMediaServer` |
| Merged, but no login session | Relay of the phone copy (no sign-in prompt) |
| Immich-only, no public HTTPS address or the setting off | Relay of the Immich copy, with a one-time note |
| Immich-only, no login session | "Sign in once to cast" |

- **Direct URLs** always use the saved public URL, and only if it's HTTPS on a public DNS name
  (`CastUrls.directBase`: not an IP literal, `.local`/private, or a bare name), since the
  Chromecast resolves with public DNS and wants a valid certificate:
  photos `/api/assets/{id}/thumbnail?size=fullsize&edited=true&sessionKey=…` (the server converts
  HEIC), videos `/api/assets/{id}/video/playback?sessionKey=…`.
- **Content type**: a HEAD through the shared client; photos announce what it says if the TV can
  show it, else `image/jpeg`; a HEIC answer (fullsize generation off → redirect to the original)
  switches to `size=preview`. Videos announce the HEAD's `video/*`, QuickTime as `video/mp4`.
  Never the original's mime (Immich mobile's bug).
- Settings → Casting → "Use public address for casting" (`UiPrefs.castUsePublic`, default on).

### Immich child sessions (`CastSessionManager`)

- `POST /api/sessions` with `Authorization: Bearer <container.serverSessionToken()>` and
  `{duration: 1800, deviceType: "Cast", deviceOS: "Google Cast"}`; the response's `token` is the
  `sessionKey`. Reused until fewer than 10 s remain, then a new one (the old one expires on its
  own, so a video still reading with it isn't cut off early). `DELETE /api/sessions/{id}` when the
  cast session ends. The token is a full-account credential while it lives; never logged.
- A 401/403, or no stored token (API-key sign-in), → `NeedsSignIn`. `CastSignIn` asks for the
  password once (email prefilled), calls `/api/auth/login`, checks it's the same user id and
  stores the token through `Settings.updateServer` (Keystore-encrypted). No API key is created and
  the password isn't kept. The sheet appears in the viewer when needed and in Settings → Casting.

### The relay (`LocalMediaServer`, `PhoneMediaProxy`)

- NanoHTTPD 2.3.1 (BSD-3, ~50 KB; Ktor CIO would pull several MB of Ktor). Bound to the Wi-Fi /
  ethernet IPv4 address only (`lanInfo()`), random port, re-bound if the address changes.
- URLs: `http://<wifi-ip>:<port>/<token>/m/<key>`, `key` like `local-123.jpg`, `immich-<uuid>.mp4`.
  The token is 24 random bytes (base64url) per session, compared in constant time (403 otherwise).
  Only items `register`ed for this session are served (404 otherwise).
- GET/HEAD, `Accept-Ranges: bytes`, 206 with `Content-Range` (`a-b`, `a-`, suffix `-n`; first
  range only), 416 with `bytes */len`.
- Phone photos: JPEG ≤ 1920 px as is; anything else (HEIC, PNG, big JPEG) transcoded once to a
  ≤ 1920 px JPEG (`AndroidJpegTranscoder`: ImageDecoder, EXIF applied; BitmapFactory fallback),
  six kept in memory. Phone videos: the original, ranged; HEVC is detected (MediaExtractor) for
  the error message. Relayed Immich items: the `preview` JPEG, or `video/playback` ranged through
  the shared client (the API key stays on the phone).
- Permissions: INTERNET is enough at targetSdk 35. **TODO(targetSdk 37):** Android 17's
  `ACCESS_LOCAL_NETWORK` must be declared and requested before casting phone photos.

### UI

- `CastButton`: Media3's Compose `MediaRouteButton` in our square frame (orange while casting);
  in the viewer's top bar whenever Play services are there, and in the Photos header only while
  devices are around (`CastState.devicesAvailable`).
- Viewer while casting: `CastingBanner` ("Casting to <TV>", STOP, errors and notes), the settled
  page goes to the TV, video pages are `CastVideoPage` (poster, play/pause, the local `SeekBar`,
  TV volume, Stop casting); the local ExoPlayer is never created.
- ⋯ (`ViewerMoreSheet`): Slideshow on TV / Slideshow, Archive (moved here from the top bar), Stop
  casting. `SlideshowSheet`: 3/5/10 s, shuffle. `SlideshowRunner` loops the timeline
  (`TimelineQueue` loads months on demand); videos play to their end. The TV's show belongs to the
  controller (keeps going with the viewer closed; the pager follows); the phone's own show turns
  pages with the chrome hidden. A swipe by hand stops either.
- `CastMiniController` above the tab bar while casting and the viewer is closed.
- Screenshots: `CastScreenshotTest` (`cast-*.png`). Tests: `cast/` (session manager, routing and
  URLs, the relay on localhost, the transcoder, the controller and slideshow, sign-in).

### Needs a device

- Discovery, the device picker, Output Switcher and the lock-screen controls.
- The Default Media Receiver with photo items, `sessionKey` URLs from photos.royle.au, and HEVC
  on the user's Chromecast model.
- The relay across real Wi-Fi (client isolation, VPNs), and with the app in the background.
