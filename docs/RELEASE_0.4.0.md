# Mr. Poodles 0.4.0 release record

Date: 2026-10-04. Android version code: **7**.

## Artifact

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Size: **61,124,859 bytes** (61.12 decimal MB).
- SHA-256: `ec86b57fd3fdd48a4d2acc8502d2dcf17ad18847615a5f5c64b71292bdaec251`
- Variant: **debug**, matching the previously delivered application's signing setup. No new key was created.
- Signing certificate SHA-256: `384fc33f9cce3f83a132903dfa694b563300ae9267144370b6931ce27f3b47f8`.
- `apksigner verify --verbose --print-certs` passed; APK Signature Scheme v2 verified, one signer. The preceding APK has the same certificate.
- `aapt dump badging` confirms `com.mrpoodles.app`, version `0.4.0` / `7`, minimum SDK 28 and target SDK 35. No camera permission is present.
- APK audit passed: required assets, 34 historical nutrition records, matching configured backend/app credential, no retired model/OCR runtime, no photo FileProvider paths, and none of the four configured backend provider keys in archive contents.
- APK remains ignored and is delivered separately from Git source. It contains the existing limited app credential and is intended for the private recipient.

## Backend deployment

- Worker origin: `https://mr-poodles.achityagulatibvc.workers.dev`
- Deployment version: `328eb8f4-c16d-42d5-a402-a568da02f4f8`.
- The user authorized deployment together with packaging and final source commit/push at the pre-Phase-8 check-in.
- Preflight verified the authenticated Cloudflare account's workers.dev subdomain matches the configured client origin, the existing Worker exists and the existing app credential works.
- Existing eligible Exa/Tavily/Groq/YouTube keys, eligibility flags and reviewed source policy were uploaded through Wrangler stdin. No credential value was printed or committed. The app credential was not rotated; Durable Object identity and counters were not reset.
- Health returns `0.4.0`. Authenticated status and the research route passed. Retired image requests return HTTP 400 and invalid requests do not consume quota.
- One production synthetic research request passed: **HTTP 200**, **Groq**, **one source**, valid request-bound quotations, **2,827 ms**. This records the actual selected provider, not an inferred reason for primary-path fallback.
- Production companion probe passed in **2.74 seconds**. No valid optional expression marker was returned; the client uses its tested default expression fallback. This is not a claim that every future marker or response will comply.

## Build and verification

- Final command: `scripts/build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')`, with the three explicit captured-evidence replay environment variables configured.
- **Build successful in 2m 42s. 324 Android/JVM/Compose tests, 0 failures, 0 ignored.** Lint passed with 0 errors and 6 dependency-version warnings.
- Backend version metadata updated to `0.4.0`; **67 backend tests passed**. Wrangler dry run passed: **58.98 KiB / gzip 16.95 KiB**.
- Actual-source replays cover recipe range/serving/restriction handling, nutrition persistence/correction/Undo and a sourced timed workout/save/completion. Replay substitutes Android transport; separate production probes verify service operation, not the phone's UI.
- Current notices were corrected to text-only checks and optional research providers. Model weights and provider secrets are not bundled.

## Unperformed device acceptance

`adb devices -l` returned no attached devices. Therefore installation over the old app, real-device migration, OEM keyboard, splash handoff and intended-user unaided usability were **not performed**. Certificate continuity and automated migration tests do not substitute for those checks. Install as an update with the same key; do not uninstall if existing private data must be preserved.

Supported-source limitations remain: unknown/mismatched food evidence stays unknown; recipe adaptations and recognized layouts are bounded; the NHS wall session requires a suitable wall and clear floor space. A successful banana estimate does not validate the earlier wrong-food pyaaz-kachori search.

## Source delivery

Release source commit: **`a88b31c` — `Add sourced chats, durable diary and meal planning`**. It was pushed successfully to `origin/main` at `https://github.com/achityagulatibvc-bit/Mr.Poodles.git`. A documentation-only follow-up records this actual outcome; application/backend code and the APK remain unchanged. The staged-source audit confirmed the five configured credential values were absent and no binary/signing/local configuration was staged.
