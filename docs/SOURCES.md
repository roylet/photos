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
- The placeholder is `EmptyLocalTimelineSource`.
- Plug it in with `container.localTimeline = ...`. The container routes the registry to it
  whenever there's no server.

### `LocalLibrary : LibrarySource`

- Defaults already cover the server-only methods: they throw `UnsupportedCapabilityException` or
  return empty lists. `trashDays()` returns null.
- You implement these: `setFavorite`, `details`, `albums`/`album` (folders), `trash`, `restore`,
  `deleteForever`, `restoreAll`, `emptyTrash`, `assets`, `download` (or share the content URI
  directly) and `basicSearch` (file name, folder or date).
- The placeholder is `EmptyLocalLibrary`. Plug it in with `container.localLibrary = ...`.
- `container.library` returns the Immich library with a server, else the local one. Tests can
  pin a fake by assigning `container.library = ...`.
- **Agent C:** swap this choice in `AppContainer.library` and `sources()` for a merged library.

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
