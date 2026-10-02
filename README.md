# Mr. Poodles

A softer little place for meals, small plans, and whatever is on your mind. An Android companion built for a friend, with an original penguin mascot and a pastel, illustrated interface inspired by cozy games such as Cats & Soup.

## Version 0.3.0

- Five labeled destinations: **Today, Check, Meals, Move, Chat**. Each owns its navigation state, drafts, and scroll position.
- A native launch splash, illustrated cozy room, soft cards, floating light, gentle transitions, breathing/blinking mascot, and touch reactions. Android motion preferences are respected; sounds default off.
- Poodles stays visible while chatting and becomes compact when the keyboard opens. Replies can carry validated expression and occasional imaginary gift markers. Unknown markers fall back safely to an attentive expression.
- The established soft companion voice acknowledges feelings before advice. No guilt, forced positivity, streak pressure, or demands for attention.
- Poodles learns a small set of comfort preferences from clear statements and repeated requests. Durable preferences require either an explicit instruction or repeated evidence. They expire after 90 days without reinforcement. Current requests take priority.
- Saving comfort preferences and saving chat history are independent, opt-in choices. Memories are visible and removable. Forgetting a preference also clears the conversation so old turns cannot silently restore it. Private events and diagnoses are not extracted into memory.
- Camera and gallery photos use one label-reading service. Images are resized and re-encoded without original location metadata. A preview and retry remain available when reading fails.
- Food restrictions, ingredient aliases, typed-label checks, translations, recipe generation, recipe shelf, day/week plans, shopping lists, food diary, portion scaling, nutrition estimates, workouts, and confirmed profile edits remain available.
- Hostel and Home environments keep their equipment, pantry, budget, preparation time, and movement limits.
- Failed chat requests retain the user message and can be retried without duplication. Snapshot commits now report persistence failures rather than claiming a save succeeded.

## Service status

The backend uses explicitly free model routes, with no data collection and zero-retention routing. It never switches to a paid model automatically.

**Photo reading is currently provider-blocked.** Live tests received rate-limit and privacy-endpoint errors. The owner chose to keep the free-only policy. Photo selection, preview, upload, and retry are implemented, but successful service transcription is not claimed. Typed ingredient checks still work.

Live companion expression-protocol and recipe JSON tests passed. See [build verification](docs/BUILD_STATUS.md) for exact checks and remaining device testing.

## Build

Requirements: JDK 17, Android SDK 35, Python 3, and Node.js for backend tooling.

```powershell
.\scripts\prepare-assets.ps1
.\scripts\build.ps1
python scripts/audit-apk.py
```

Use `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` on a normally configured toolchain. The PowerShell build script also recognizes the workspace toolchain under `.tools/`.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Complete [service setup](docs/CLOUD_SETUP.md) before building a connected copy. `.poodles.properties` is ignored and must never be committed. The provider key belongs only in the Worker secret. The APK contains a limited, revocable app credential; keep this personal development APK private.

Backend checks:

```powershell
npm ci --prefix backend
npm test --prefix backend
npm run check --prefix backend
```

Live synthetic checks use the configured service and consume the shared free allowance:

```powershell
python scripts/test-cloud-features.py --only companion
python scripts/test-cloud-features.py --only recipe
python scripts/test-cloud-features.py --only image
```

Install updates using the same signing key to retain saved data. Follow [device testing](docs/DEVICE_TESTING.md), especially the reported tab-switching sequence and the phone's keyboard. The APK is development-signed, not a store release.

## Architecture

- Native Kotlin and Jetpack Compose. `AppShell.kt` owns navigation; `ChatScreen.kt` owns conversation presentation; `Screens.kt` contains food, movement, and profile flows.
- `Design.kt` and `CozyElements.kt` contain the original visual system and native mascot artwork.
- `PoodlesViewModel.kt` coordinates tasks. `CompanionMemory.kt` validates bounded comfort preferences and reply markers. No separate inference call is needed to learn these preferences.
- `LocalStore.kt` writes version-compatible JSON snapshots using a synchronized, fsynced temporary file and an atomic replacement that propagates commit errors.
- `backend/src/worker.js` calls fixed free OpenRouter routes through Cloudflare Workers. A Durable Object limits requests to 45 attempts per UTC day and 8 per minute across APK copies.
- `LabelPhoto.kt` prepares images. No photo is included in general companion conversations.

## Food and movement limits

Ingredient matching is conservative and cannot certify food safety. Unknown names, incomplete labels, translation errors, and cross-contact can be missed. The bundled nutrition catalog contains 18 source-backed USDA records, not a comprehensive food database. Generated recipes are constrained by ingredient IDs and preparation rules. Recorded injuries pause automatic workout generation.

## Credits

All bundled artwork and the chime are original. See [third-party notices](THIRD_PARTY_NOTICES.md). The project is agent-assisted.

Design research: [state ownership in Compose](https://dev.to/devanshu_patil/jetpack-compose-made-me-rethink-how-i-build-ui-293e), [typed navigation](https://dev.to/itsaalaa7/how-sealed-classes-make-navigation-safer-in-jetpack-compose-1pdd), and [Android AtomicFile implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/util/AtomicFile.java).
