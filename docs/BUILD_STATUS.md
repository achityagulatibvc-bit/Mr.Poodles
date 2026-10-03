# Build verification — 0.3.1

## Artifact

- Package `com.mrpoodles.app`, version **0.3.1**, version code **6**, Android 9+.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Size: **59,962,003 bytes (59.96 MB)**.
- SHA-256: `8e47b964230d0bb1c04587359f7dc277c71da37d62db94669b0ab1766d38f122`.
- Connected configuration audit and APK v2 signature verification passed. No credential values were printed.
- The APK includes 34 attributed nutrition records, original artwork/chime, and current notices. Retired inference/image-recognition model assets are rejected by the package audit.

## Findings and fixes

### Availability

The former backend shared **8 requests/minute and 45/day** across chat, recipes, plans and photos. Three recipe requests plus five messages matched that burst ceiling. The former upstream also had a 50-request/day free-account policy, so raising only the app counter could not meet the requested 100-message target.

Inference now uses the existing Cloudflare account's Workers AI binding. No paid-plan upgrade, Gateway billing, or paid fallback was configured. Separate request and conservative compute reservations protect chat capacity:

- Chat: 120/day, 30/minute, 7,000 reserved neurons/day.
- Recipes/plans/help: 40/day, 15/minute, 1,800 reserved neurons/day.
- Images: 6/day, 6/minute, 1,000 reserved neurons/day.

Both request and compute ceilings apply. The total reservation is below the provider's 10,000-neuron daily free allocation. A regression exercises **100 bounded chat requests after tool use**. This is a budget/logic test, not 100 live paid or free inference calls. Other projects on the account and provider capacity can still affect availability. Keep the account on Workers Free for the provider's hard no-overage behavior.

The client preserves `Retry-After`, distinguishes minute/daily/category limits, and avoids repeated inference calls during cooldown. An authenticated status endpoint reads counters without spending an inference request.

### Recipes

The earlier planner selected from four preset recipes, and recipe generation had only 18 ingredients, a banana-based schema example, no recent-suggestion context, and low-variety sampling.

Preset injection is removed. Qwen3 now generates recipe alternatives and ordered preparation actions using 34 source-backed ingredients. Required/excluded ingredients, domain constraints and recent signatures are validated. Renaming a dish or changing portions does not evade repeat detection. Preparation wording is rendered from validated actions and ingredient references; it is not an unrestricted food-safety instruction generator.

A full week is selected in one inference request when a recipe library exists. A fresh library requires one additional AI recipe-generation request, not seven separate daily planning calls.

### Emotional support

Both app and backend prompts distinguish ordinary distress from concrete immediate danger. Crying alone calls for acknowledgment and a gentle question about what happened, without assumed suicidal intent or a crisis referral. Concrete current danger still receives calm, appropriate urgent guidance.

## Passed checks

- Clean Android build: `:app:clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon`.
- **60 Android/JVM tests passed**, including navigation, drafts/recreation, recipe request matching, duplicate rejection, AI-seeded planning, one-call week planning, scoped cooldowns, memory and persistence.
- Android lint: **0 errors, 10 warnings** (dependency versions and optional KTX suggestions).
- **19 backend tests passed**, covering separated quotas, the 100-chat budget, the reported three-recipes/five-messages pattern, bounded inputs, schemas, response normalization, credentials and sanitized errors.
- Wrangler dry-run bundling, APK configuration/content audit, and APK v2 signature verification passed.

## Live synthetic checks

- "I am crying": supportive acknowledgment plus a question about what happened; no helpline or emergency assumption.
- Crying because a friend ignored the user: contextual support without crisis framing.
- Explicit present overdose: urgent help retained; the response stayed calm after prompt calibration.
- Three different recipe requests passed request/exclusion/preparation checks: **Chickpea Tomato Pasta Salad**, **Simple Potato Sandwich**, and **Cucumber Chickpea Salad**.
- A seven-day, 21-meal schedule passed using **one** planning request.
- Synthetic label-image transcription passed in **1.63 seconds** on the new vision route. This supersedes the previous provider-blocked image status.
- Companion expression probe passed in **1.17 seconds**. Model-supplied expression markers are still optional/unreliable on some generations; the app's validated attentive fallback handles missing or malformed markers.
- Service counters showed chat and assistance usage growing independently, without the former shared eight-request block.

Timings are individual observations, not latency guarantees. Prompt tests do not guarantee every future model response. No phone was attached for this patch; real camera/gallery behavior, OEM keyboard behavior, and physical-device UX still require `DEVICE_TESTING.md`.
