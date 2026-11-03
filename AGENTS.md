# Briefkasten contributor notes

## Scope and build

This directory is the publishable Android project. The parent directory contains local research, downloaded third-party code and private reference material; do not copy it into this project. The public README is maintained by the owner.

Native Kotlin/Compose app, Android 9+ (minSdk 28), compile/target SDK 36. Use JDK 17 or 21, Android SDK 36 and the checked-in Gradle wrapper (8.14.3). AGP is 8.12.0, Kotlin 2.2.10. Configure `ANDROID_HOME` or an ignored `local.properties` containing `sdk.dir`.

- `./build.sh --no-parallel --max-workers=1` assembles debug, runs JVM tests and lint. The helper uses an available local JDK 17 and keeps Gradle caches under ignored `.tooling/`.
- Portable equivalent: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-parallel --max-workers=1`.
- Compile device tests: `./gradlew :app:assembleDebugAndroidTest --no-parallel --max-workers=1`.
- Release: `./gradlew :app:assembleRelease`. Configure signing locally; never commit signing keys or passwords.
- APK output: `app/build/outputs/apk/debug/app-debug.apk`. Update with `adb install -r`; never uninstall or clear storage to update an existing archive. Preserve the signing key across updates.

## Architecture and invariants

- `BriefkastenApp` owns the vault, Room database, preferences, sync lock, indexer and WorkManager jobs. `MainActivity` contains archive/detail/settings UI and system picker/share integration.
- `Auth` uses AppAuth browser login, PKCE, random OAuth state and refresh rotation; pending login state is persisted. Chrome is the default, with a system-browser fallback. Passwords are never collected. The recovered DHL client ID and `dhllogin://de.dhl.paket/login` callback belong to the official registration; no independent client registration exists. Callback routing may conflict with the official app. The private protocol can change.
- `DhlApi`, `Protocol` and `SyncEngine` handle current/history responses, image grants, decryption and deduplication. Require HTTPS port 443 on the explicit DHL/Deutsche Post domain allowlist; do not forward credentials through redirects. Missing/unknown account state is an error, not an empty mailbox. Keep bounded downloads.
- First complete sync silently imports history; subsequent current arrivals may notify. Partial failures retain successful downloads and retry missing images. Identities include account/date/content/occurrence with URL aliases. Identical images without stable server IDs remain inherently ambiguous.
- Polling supports 15/30/60/180 minutes, uses connectivity constraints and backoff, and may be delayed by Android. Check Now queues connected work. Disconnect keeps the archive and removes local authentication; it does not revoke browser sessions.
- `Vault` stores originals and caches with device-bound AES-GCM encryption. Room metadata is app-private. Android backup/transfer is disabled. Uninstalling or clearing storage destroys the local archive/key; portable backups are the recovery route. Screen lock re-engages in the background but deliberately does not gate the storage key, allowing background sync. Notification previews and permission state are separate controls.
- Original image bytes must remain unchanged. Full view uses the original; `CutoutImages` crops at original resolution, deskews along printed text only, trims again and caches a PNG. Uncertain angles stay unchanged. Do not downsample source images for OCR or exports; thumbnail decoding is for UI only.
- `GroupIndexer` stores separate Full/Cutout OCR caches. Native, exact filtered 2× and supplemental 3× readings are used; scaling is bounded to 16 MP/6000 pixels per side. Large images also use overlapping native tiles. Display one preferred reading, not concatenated passes. Sort recognized lines by geometry, and match groups against independent readings from both variants; longest matching literal name wins.
- The text viewer supports native text selection and Copy. Images fit their content and support zoom/pan. Date display follows device region; Germany uses `dd.MM.yyyy`. Parsing/export identity uses stored announcement dates, falling back to first-saved timestamps in the device timezone.
- ZIP/PDF export periods are calendar months (This month, Last month, Past 2–12 months, All time). Filenames contain selected `MM-YYYY` ranges, not export dates. All time uses actual oldest/newest months.
- Ordinary ZIPs contain selected Originals/Cutouts/Both, no manifest. PDF defaults to Both and one combined file; per-envelope PDFs are opt-in. Both image variants share a page with clear labels and matching OCR columns; long text continues without losing lines. Embed searchable Unicode text, received date, vector SVG logo and app-style wordmark. Show the first-saved explanation only when the announcement date is unavailable.
- Export dialogs provide Cancel, Share and Save. Share uses temporary read-only FileProvider grants. Save uses Create Document for one file or a selected folder for multiple PDFs, without broad storage permission. Preserve pending exports across recreation and respect lock state on picker return. Exports are plaintext; cache folders older than a day are cleaned on subsequent export.
- Backup selects All time, disables/clears image selection, and restores previous choices when disabled. Import merges records, skips matching envelopes, rejects conflicts and preserves current edits to matching groups. No demo/screenshot bypass or bundled sample images belong in the app.

## Tests

Keep the eight JVM suites: protocol/decryption/credential boundaries, calendar exports and ZIP contents, crop bounds, deskew, OCR scale/tile/reading order, and group matching. These cover failure modes that have caused real regressions.

Device suites cover synthetic OCR and migration, cutout/original integrity, PDF pagination and both-view layout, backup checksums, import deduplication/corruption/path validation, and save intents/bytes. Backup tests use in-memory databases, isolated vaults/preferences and generated images; they must not depend on login or a user's archive. Do not reintroduce one-off diagnostics that export personal archives into test artifacts. PDF visual layout still needs rendered-page inspection after changes.

Run instrumentation on a disposable test device. Gradle connected-test tasks may uninstall the target app on teardown. On a device whose data must be preserved, compile APKs, install both with `adb install -r`, then use the direct runner:

```sh
adb -s DEVICE shell am instrument -w de.skyslycer.briefkasten.test/androidx.test.runner.AndroidJUnitRunner
```

Do not run account-dependent network tests or send notifications to real users as routine validation.

## Portable backup format (version 1)

Backup is an **unencrypted ZIP** named `Briefkasten_backup_MM-YYYY[_to_MM-YYYY].zip`, always All time. Empty archives use `Briefkasten_backup_empty.zip`; they can still contain groups/settings. Ordinary image ZIPs are not importable backups.

`manifest.json` is UTF-8 JSON:

- `format`: `de.skyslycer.briefkasten.backup`; `version`: integer `1`.
- Metadata: `appVersion`, UTC `createdAt`, IANA `timeZone`, `scope: "all_time"`, `encrypted: false`, `authenticationIncluded: false`.
- `letters`: `id`, `account`, announcement `date`, epoch-ms `firstSeen`, `fileName`, `contentHash`, `occurrence`, `notificationPending`, nullable `groupId`, `received` (optional, defaults to false when absent), `original` ZIP path. Optional `cutout` with `cutoutVersion: 1`; `ocr` object with optional `full`/`cutout` paths and `ocrVersion: 8`.
- `groups`: `id`, `name`, palette index `color`.
- `sources`: source-key `source`, referenced `letterId` (URL aliases/deduplication).
- `accounts`: `id`, `initialized` baseline flag.
- `settings`: `polling`, `lock`, `pictures`, `interval`, `lastCheck`, `status`, `notifications`.
- `assets`: relative ZIP `path`, byte count `bytes`, lowercase hexadecimal `sha256` for each payload.

Originals are decrypted without recompression into `originals/<fileName>`; their hashes must match letter `contentHash`. Optional caches: `cutouts/<id>.png`, `ocr/<id>-full.json`, `ocr/<id>-cutout.json`. OCR JSON contains `original`, optional `enlarged` and optional `detail` readings. Missing caches can be regenerated. `README.txt` explains exclusions. No SQLite blobs, Keystore keys, OAuth tokens or browser sessions are included; sign in on the destination device.

Export holds the sync lock and snapshots database tables in a Room transaction; settings/cache reads are separate and vault writes atomic. Import validates safe unique paths, supported format/version, declared sizes and hashes, images, identities and references before writing. Bounds: 64 MiB per entry, 2 GiB expanded total, 100,000 entries, 16 MiB manifest, 100 MP per image. Re-encrypt assets with the destination key; commit new records transactionally and remove newly written assets if that fails. Never replay historical notifications. Restore picture/interval/notification/polling preferences, leaving device lock, OS permissions, login and last-check state specific to the destination.

## Publishing hygiene

Only commit source, resources, build configuration, wrapper files, public documentation and contributor notes. Curated README screenshots belong in `docs/`; generated review captures remain ignored. Keep local SDK paths, tooling/caches, emulator helper, generated builds, exports/backups, screenshots, databases, logs, signing material and credentials ignored. The Gradle wrapper JAR is intentional source-control content. Never add downloaded/decompiled DHL binaries or captured authenticated responses. Public OAuth client identifiers are not client secrets; user tokens are.
