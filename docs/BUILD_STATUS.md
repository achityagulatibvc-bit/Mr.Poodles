# Build verification — 0.3.0

## Artifact

- Package: `com.mrpoodles.app`, version `0.3.0`, version code **5**, Android 9+.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Size: **59,911,727 bytes (59.91 MB)**.
- SHA-256: `8d496e1c98a005af4e2924d608bac4fb7f6771091921b6941483c5b1f3357c6c`.
- Packaged connection settings match the ignored configuration; no credential values were printed.
- The package contains the original chime, current notices, and 18 attributed nutrition records. The audit rejects retired inference and image-recognition binaries or model assets.

## Passed checks

- Clean Android build: `:app:clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon`.
- **47 Android/JVM tests**, including nine Compose UI tests, all passed.
- Navigation tests use actual touch input for every tab without visiting Chat first. They also cover compact layouts, profile return, chat/recipe/label drafts, and activity recreation.
- Companion tests cover learned preference evidence, explicit boundaries, negation, expiry, old-data compatibility, split metadata chunks, invalid markers, gift cooldowns, retry deduplication, and cancellation during conversation deletion.
- Persistence tests verify independent memory/chat consent, durable reads through a new store instance, repeated commits, corruption preservation, and write failures.
- Android lint: **0 errors, 10 warnings** (dependency updates and optional KTX style suggestions).
- **12 backend tests** passed. Wrangler dry-run bundling passed.
- APK content/configuration audit passed.
- APK v2 signature verification passed.

## Live service verification

The updated backend was deployed to the existing Worker. It retains the explicit free-only model routes, 45-attempt daily quota, eight-per-minute quota, no-data-collection routing and zero-retention requirement.

- Companion expression-protocol test: **passed in 2.11 seconds** for one synthetic celebration. The reply used a valid `[poodles:happy:none]` marker and stayed within the short-reply limit.
- Recipe JSON test: **passed in 1.78 seconds** for one synthetic recipe with exact ingredients and quantities.
- These are individual observations, not latency guarantees or comprehensive assessments of conversation quality.
- **Photo transcription remains blocked by the provider.** The selected free Qwen route returned rate-limit/privacy-endpoint errors. A tested alternative failed the zero-retention routing requirement and was not retained. The owner explicitly chose to keep free-only service and retry behavior rather than introduce a paid route.

## Findings and limits

- The original report was that other tabs only worked after opening Chat. A basic test on the original layout did not reproduce that phone-specific failure. Navigation has been rebuilt with explicit destination identities, independent screen containers, persistent tab state, and a continuously reachable bottom bar. Regression tests pass; physical verification of the exact report remains necessary.
- A new persistence regression test exposed `AtomicFile.finishWrite` reporting success while replacement failed under the Windows-hosted test runtime. The store now fsyncs a temporary snapshot and uses an atomic move that throws on failure. Repeated disk reads now verify the committed data rather than only checking the UI state.
- No phone or emulator was attached. Actual camera/gallery behavior, OEM keyboard behavior, animation smoothness, sound playback, accessibility and battery impact still require the [device checklist](DEVICE_TESTING.md).
- The APK is development-signed. Update over the previous installation with the same key to retain saved records.
