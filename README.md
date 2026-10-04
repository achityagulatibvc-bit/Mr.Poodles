# Mr. Poodles

A softer little place for meals, small plans, and whatever is on your mind. An Android companion built for a friend, with an original penguin mascot and a pastel, illustrated interface inspired by cozy games such as Cats & Soup.

## Release 0.4.1 — version code 8

The APK is built and its compatible backend is deployed. [Release verification](docs/RELEASE_0.4.1.md) records the artifact path/hash, matching signing certificate, tests and production checks. This repair prevents unreadable pages from globally pausing source lookup, improves Android error/cooldown handling, and adds a clear context/chat divider. No phone was attached, so physical-device upgrade and intended-user usability remain unverified. [The implementation plan](docs/IMPLEMENTATION_PLAN.md) retains the phase checkpoints.

- Home offers **Can I eat this?**, **Find a recipe**, **Find a workout**, and **Log what I ate**, alongside companion chat. Food and recipe workspaces use context above the conversation and a bottom composer.
- **Text-only food checks:** enter a dish, exact product, public manufacturer URL, or pasted ingredients. Product lookup asks for brand, variant and country; typical dish ingredients are not an actual product label. Outcomes distinguish **Avoid**, **No listed conflict found**, and **Need more information**. Camera/gallery capture, photo reading and vision inference are retired.
- **Conservative sourced recipes:** retrieved pages can supply named quantities, servings, methods, source links and attribution. The strict parser rejects unsupported or incomplete layouts. Supported serving edits and limited, explicitly cited equal-amount no-cook substitutions are available; arbitrary conversational recipe adaptation is not supported. AI adaptations are labeled separately from published originals, and sourced recipe nutrition stays unknown.
- **Optional source lookup:** a separate choice enables Exa/Tavily search, related YouTube video lookup and Cloudflare research notes. Groq fallback requires a separate, initially unchecked opt-in. Ordinary users do not enter provider keys. Pasted ingredient checks can run locally without source lookup; unrelated companion history is not sent to research.
- **Separate allowances:** chat retains its own 120-request daily ceiling and 7,000-neuron reserve. Assistance/research cannot spend that reserve. Account-wide free capacity and additional provider limits still apply.
- **Sourced workouts:** recognized article layouts and a bounded three-page NHS path supply warm-up, movements and cooldown. Duration edits create independently validated exercise/time caps, including rest and cooldown. Wall-supported sessions require explicit wall availability. Videos remain metadata-only technique references. Injuries require clarification; arbitrary workout generation is not supported.
- **Natural-language diary:** Home opens the food-log workspace. Recognized eaten statements use comparable retrieved nutrition blocks; known-calorie batches save automatically and unknown calories require explicit save. Questions do not authorize diary writes. Manual entries and corrections remain available in Meals.
- **Planning and shopping:** arbitrary dates, day/week views, meal replacements/moves/portions, sourced-library previews and unit-aware shopping are implemented. A day needs three distinct suitable recipes; a week needs twenty-one. Discovery makes bounded attempts and can remain incomplete. Legacy records remain readable.
- A native launch splash hands off to the branded launch experience with a two-second cold-launch minimum while local loading runs concurrently. Ordinary resume and tab changes do not repeat it. Android motion preferences are respected; sounds default off.
- Poodles stays visible while chatting and becomes compact when the keyboard opens. Replies can carry validated expression and occasional imaginary gift markers. Unknown markers fall back safely to an attentive expression.
- The established soft companion voice acknowledges feelings before advice. No guilt, forced positivity, streak pressure, or demands for attention.
- Poodles learns a small set of comfort preferences from clear statements and repeated requests. Durable preferences require either an explicit instruction or repeated evidence. They expire after 90 days without reinforcement. Current requests take priority.
- Saving comfort preferences and saving chat history are independent, opt-in choices. Memories are visible and removable. Forgetting a preference clears companion Chat so its old turns cannot restore it. History revocation now sanitizes disk, memory and restored feature state; saved evidence remains separate. Private events and diagnoses are not extracted into comfort memory.
- Food restrictions, ingredient aliases and confirmed profile edits remain; chat does not silently weaken restrictions.
- Hostel and Home environments keep their equipment, pantry, budget, preparation time, and movement limits.
- Failed chat requests retain the user message and can be retried without duplication. Snapshot commits now report persistence failures rather than claiming a save succeeded.

## Service status

Keep Cloudflare on Workers Free and optional providers on verified free-only accounts without payment, phone/KYC requirements or automatic recharge. Credentials alone do not enable a provider. See [service setup](docs/CLOUD_SETUP.md) and the [research contract](docs/PHASE2_BACKEND.md) for budgets, consent and measured limitations.

**The 0.4.1 backend is deployed after the user's repair/release authorization.** It keeps v1 text/SSE behavior but rejects `task: "vision"` and any top-level JSON `image` field before quota reservation or inference. V1's request cap is 160,000 UTF-8 bytes; v2 retains its 6,000-byte cap. Old clients' photo actions are retired; install the text-only client update. Same-day stored quota history is retained, while `/v1/status` exposes active chat/assistance categories. Ordinary UTC daily rollover remains. Current research checks and historical companion probes are recorded separately in the release documentation.

Final packaging checks pass: **339 Android tests**, including four actual-evidence replays and one real HTTP recipe test, **101 backend tests**, and **0 lint errors / 6 dependency warnings**. The real HTTP check uses the Android transport and deployed backend on the build host; captured-evidence replays substitute HTTP. Signing certificate continuity is verified against the old APK. Physical-device upgrade and intended-user usability remain unperformed. [Build verification](docs/BUILD_STATUS.md) describes older releases; [0.4.1 verification](docs/RELEASE_0.4.1.md) is current.

## Development checks and release boundary

Requirements: JDK 17, Android SDK 35, Python 3, and Node.js for backend tooling.

```powershell
.\scripts\build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug')
```

The PowerShell script recognizes the workspace toolchain under `.tools/`. **Do not invoke its default task, assemble or bundle before Phase 8.** Final APK packaging requires the user's pre-packaging check-in. The conventional artifact path is `app/build/outputs/apk/debug/app-debug.apk`; an existing file there is historical, not a verified new release.

Complete [service setup](docs/CLOUD_SETUP.md) for a connected copy. `.poodles.properties` and backend secrets are ignored and must never be committed. Provider keys stay on the backend; the personal development APK contains only a limited, revocable app credential and should remain private.

Backend checks:

```powershell
npm ci --prefix backend
npm test --prefix backend
npm run check --prefix backend
node --test backend/test/phase4.test.js
```

`npm run check` is a Wrangler dry run, not deployment. Live synthetic checks use the configured service and consume its shared free allowance. Verify the target and use explicit selections. The following legacy probes do not validate the new client-side sourced parsers or automatic food logging:

```powershell
python scripts/test-cloud-features.py --only companion
python scripts/test-cloud-features.py --only recipe
python scripts/test-cloud-features.py --only distress
python scripts/test-recipe-variety.py
python scripts/test-week-plan.py
python scripts/service-status.py
```

For bounded local research probes and source-quality limitations, see [the research setup guide](docs/PHASE2_BACKEND.md). Final-phase updates must use the same signing key to retain saved data. Follow [device testing](docs/DEVICE_TESTING.md), especially migration, consent, tab switching and the phone's keyboard.

## Architecture

- Native Kotlin and Jetpack Compose. `AppShell.kt` owns navigation; companion, food, workout and food-log workspaces use `ContextChatLayout`. `PlanningScreen.kt` owns planner/shopping UI; `Screens.kt` retains Meals, diary and profile integration.
- `Design.kt` and `CozyElements.kt` contain the original visual system and native mascot artwork.
- `PoodlesViewModel.kt` coordinates tasks. `CompanionMemory.kt` validates bounded comfort preferences and reply markers. No separate inference call is needed to learn these preferences.
- `LocalStore.kt` writes version-compatible JSON snapshots using a synchronized, fsynced temporary file and an atomic replacement that propagates commit errors.
- `backend/src/worker.js` serves legacy text/SSE and `/v2/research`. A Durable Object tracks request/compute/provider allowances without conversation content. Fixed Workers models and consent-gated Groq fallback use the same `composePersonality` policy. Exa/Tavily adapters retrieve bounded page text from reviewed source hosts.
- `SourcedFoodService.kt`, `SourcedWorkoutService.kt` and `FoodLogging.kt` connect research to strict parsers and limited supported transformations, not general-purpose AI feature generation. `Planning.kt` selects and validates sourced-library previews. Stored evidence identifies its request, source and retrieval time.

## Food and movement limits

Ingredient matching cannot certify food safety. Unknown aliases, incomplete or conflicting labels, stale product identity and cross-contact require uncertainty. A quoted source is not proof of completeness, correct food identity or safety. Parsing and substitution support are narrow; retrieved research prose is not independently validated evidence. The 34-record USDA catalog remains for legacy records and estimates, not a gate on new sourced ingredients. Sourced recipe nutrition remains unknown. Recorded injuries pause sourced workout generation. See the release review for resolved integrity defects and remaining real-source coverage gaps.

## Credits

All bundled artwork and the chime are original. See [third-party notices](THIRD_PARTY_NOTICES.md). The project is agent-assisted.

Design research: [state ownership in Compose](https://dev.to/devanshu_patil/jetpack-compose-made-me-rethink-how-i-build-ui-293e), [typed navigation](https://dev.to/itsaalaa7/how-sealed-classes-make-navigation-safer-in-jetpack-compose-1pdd), and [Android AtomicFile implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/util/AtomicFile.java).
