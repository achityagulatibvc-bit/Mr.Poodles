# Mr. Poodles 0.4.1 repair release

Date: 2026-10-04. Android version code: **8**.

## Authorization and scope

The user reported every recipe still showing a source-lookup pause and explicitly requested fixing it and then building the APK. This release includes that repair and the previously requested context/chat border. The source tree retains all earlier uncommitted repair work. No new commit or push was requested or performed.

## Changes

- Unreadable pages and empty searches no longer put all retrieval providers into cooldown. Other valid candidate pages remain eligible, within the existing three-page cap.
- Source-specific failures return HTTP 422 without a provider pause. Real upstream rate limits, outages and Retry-After remain enforced. A denied reservation does not extend an already recorded cooldown; storage failures are not mislabeled quotas.
- Android distinguishes source-specific errors from real service limits, including older 503 source-error envelopes. It preserves useful error messages instead of replacing everything with a generic retry message.
- The earlier Workers AI eligibility and retrieval-only fallback fixes remain included. No quotas, stored data, paid-provider settings or consent flags were reset.
- Recipes, workouts and ingredient checks have a full-width 2 dp dark divider between context and chat, including compact/keyboard layouts.

## APK

- Path: `app/build/outputs/apk/debug/app-debug.apk`
- Version: **0.4.1 / 8**
- Size: **61,124,855 bytes** (61.12 decimal MB)
- SHA-256: `46ce620da90df4641dcbb5770c78bd215b17191fe8d77a5132a00b7671eca803`
- Signing certificate SHA-256: `384fc33f9cce3f83a132903dfa694b563300ae9267144370b6931ce27f3b47f8`
- Debug variant, preserving the existing signing identity. The old artifact's certificate was checked before replacement; the new signature verifies with APK Signature Scheme v2.
- Manifest confirms `com.mrpoodles.app`, minimum SDK 28, target SDK 35 and no camera permission.
- APK audit passes required assets, matching connection settings, 34 preserved legacy nutrition records, no retired model/OCR runtime or photo provider resources, and absence of all four backend provider keys.

Install as an **update** over the previous app. Do not uninstall or clear app data. The APK remains ignored and is delivered separately from source control.

## Verification

- Packaging: `scripts/build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')` — **BUILD SUCCESSFUL in 2m 2s**.
- **339 Android/JVM/Compose tests passed; 0 failures or ignored tests.** Four captured-evidence replay variables and `POODLES_LIVE_TRANSPORT=1` were explicitly set for this run.
- Lint: **0 errors, 6 existing dependency-version warnings**.
- Backend: **101 tests passed**, no failures/cancelled/skipped tests. Final dry run: **61.14 KiB / gzip 17.55 KiB**.
- Actual production tiramisu search: **HTTP 200, Cloudflare, two sources (2,801 and 1,642 characters), 10,259 ms**. Another failed candidate was reported without discarding the usable pages.
- Actual Android HTTP transport: the same `CloudModel` rejected an unsupported link, then searched Garden tomato salad and produced a client-validated recipe under the synthetic lactose/soy profile. This two-request test passed in **7.026 seconds**; no transport replacement was used.
- Other actual-evidence replay tests substitute HTTP and cover recipe edits, manufacturer uncertainty, workout save/completion, and nutrition logging. They are separate from the real HTTP check.

## Backend deployment

- Origin: `https://mr-poodles.achityagulatibvc.workers.dev`
- Final version ID: **`1ad32992-840e-4d1c-9ea8-f0082d8c924b`**
- Health version: **0.4.1**
- Verified existing account/Worker, unchanged app credential, research route, rejected retired images and unchanged quotas after invalid requests.
- Live recipe checks above ran on the first 0.4.1 deployment, `6191775b-9dca-4d5a-94f7-8e3f8602a330`. The final deployment adds only the separately tested raw-network-error classification fix; successful retrieval and Android code are unchanged.
- Existing provider secrets were supplied through Wrangler stdin without disclosure. App credential, Durable Object identity and stored counters were preserved.

## Remaining acceptance

No device was attached (`adb devices -l` returned an empty list). Installation, real-device migration, the divider's appearance on the user's screen, keyboard behavior and the user's exact failing phone state remain untested. The real HTTP test ran on the build host, not a phone.

Incomplete source layouts, unverified product labels, unsupported substitutions and genuine free-provider limits still require honest uncertainty or a real wait. This repair does not guarantee arbitrary recipes or certify any food as safe.
