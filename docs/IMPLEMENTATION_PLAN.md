# Mr. Poodles: phased implementation and session handoff

Last updated: 2026-10-04.

## 1. Resume here

**0.4.0 / code 7 is built, audited, deployed and source-pushed. Release code commit `a88b31c` is on `origin/main`; this documentation follow-up records the verified outcome. APK: `app/build/outputs/apk/debug/app-debug.apk` (61,124,859 bytes). All 324 Android and 67 backend tests passed. Phase 8 physical-device upgrade/usability acceptance remains pending because no device was attached. Do not restart implementation phases or repackage merely to resume.**

The user authorized sequential phases in one continuous run, with a check-in before APK packaging. That check-in occurred, and “krde” authorized Phase 8 packaging, coordinated production backend deployment and final commit/push. Actual results and unperformed device gates are recorded below; authorization does not turn an unperformed check into a pass.

- Baseline: commit `983459e` (`Fix free quotas, recipe variety and distress responses`).
- Branch: `main`, tracking `origin/main`. All intended Phase 1–8 source, tests and documentation were committed as `a88b31c` and pushed. APKs, captured evidence, credentials and signing files remain ignored. Check current Git status before editing; subsequent user changes must be preserved.
- Application release: version `0.4.0`, version code `7`; previous baseline was `0.3.1` / `6`.
- Foundations, research transport, shared navigation, sourced recipe/food-check/workout workspaces, natural-language diary and planner/shopping are integrated locally. These use strict evidence parsers and limited supported follow-ups; they are not unrestricted AI generation. Live coverage limitations remain explicit.
- Exa, Tavily, Groq and YouTube keys/eligibility flags are present in ignored local configuration. The user previously reported signup without payment/phone/KYC. Live retrieval, both model paths, video lookup, injected-primary fallback and hostile-text probes now have measured results; see the latest Phase 2 live checkpoint. Billing settings and actual account quotas are not inferred from successful calls.
- Existing reports in `BUILD_STATUS.md` describe historical verification, not verification of the planned redesign.

### Phase status

| Phase | Deliverable | Status |
| --- | --- | --- |
| 0 | Persistent requirements, phase plan, and resume instructions | Complete; documentation reviewed |
| 1 | Data model, migration, and state/persistence foundations | Complete; 78 Android/JVM/Compose tests passed; lint passed |
| 2 | Search, retrieval, evidence, and free-provider backend | Complete: 54 backend tests, live checks and partial account audit; remaining account review confirmed by user |
| 3 | Easy navigation, shared chat layout, settings, and splash | Complete: 93 Android/JVM/Compose tests; lint 0 errors, 10 existing warnings |
| 4 | Sourced recipe chat and text-only food checks | Complete local acceptance; 140 Android tests and lint pass; 13 focused backend removal tests pass |
| 5 | Sourced workout chat and related YouTube links | Complete local acceptance; 24 service/domain and 8 integration tests pass; lint passes |
| 6 | AI food logging, improved planning, shopping, and diary | Complete local acceptance; covered by final 324-test regression |
| 7 | Cross-feature regression and release readiness | Pre-packaging checks passed: 324 Android tests, three real-evidence replays, lint 0 errors / 6 warnings; user authorized Phase 8 with external gates recorded |
| 8 | Final APK packaging, release checks, commit, and push | Artifact, deployment, automated checks and source push done; physical-device migration/usability acceptance remains pending |

**Next action when a phone/user is available:** install the verified APK as an update, preserve existing private data, and perform `docs/DEVICE_TESTING.md`, especially migration, keyboard/splash and unaided recipe/check/workout/log tasks. Record real results and fix any observed issue in a subsequent authorized change. No further deployment, packaging or commits are needed simply to resume this handoff.

## 2. Non-negotiable execution rules

1. Implement only the authorized phase. Update this file with actual changes, checks, blockers, and the next step before stopping.
2. Do not mark a phase complete until its acceptance criteria have been verified. A missing provider key or unavailable device is a recorded blocker or unverified check, not a pass.
3. No new APK/AAB packaging before Phase 8. The user's wording was that APK bundling happens directly in the final phase, not earlier.
4. No intermediate commits or pushes. The user explicitly requested bundling, commit, and push in the final phase.
5. Preserve existing user data and unrelated work. Recheck `git status` when resuming; do not reset or discard changes to obtain a clean baseline.
6. No paid provider upgrade, payment method, automatic recharge, or automatic paid fallback. Email or Google login is allowed. If a provider requires phone verification or KYC, skip it.
7. Credentials belong in ignored local configuration or backend secrets, never in this document, source control, logs, or the APK as provider keys.
8. Do not bypass provider limits using multiple accounts/keys. Account-wide token, request, compute, and credit limits still apply.
9. Do not run production deployment as an incidental test. Coordinate backend/client compatibility and explicitly record deployment authorization.
10. Do not add unrelated features or delegate work to subagents without authorization. Keep the implementation bounded to these requirements.

## 3. User intent and confirmed product requirements

Session communication preference (2026-10-03): the user selected Caveman ultra. Use terse Hinglish, preserve exact technical terms, and omit routine tool narration. Repository documentation, comments and other persistent material remain normal English. This preference persists until the user requests normal mode.

### 3.1 The intended user must be able to operate the app independently

The app is for a person who is not technically confident. The user reports that she cannot operate the current interface reliably. Easy navigation is a release requirement, not optional polish.

- Make the main task reachable in one or two taps from Home, before typing.
- Provide large, clearly labeled Home actions: **Can I eat this?**, **Find a recipe**, **Find a workout**, and **Log what I ate**.
- Keep **Talk to Poodles**, today's food total, and the next planned meal easy to discover.
- Reduce nested tabs, duplicate controls, technical forms, and vague button labels.
- Use text with icons, at least 48 dp touch targets, readable text, adequate contrast, and support for large system fonts.
- Make Back/Home behavior predictable. Preserve drafts, results, dates, and scroll state across navigation and recreation.
- Use one clear primary action per screen. Keep the input reachable above the keyboard.
- Show simple progress, an actionable retry on failure, and visible save/log confirmation with Undo where practical.
- Provide example prompts so the user does not have to invent the right wording.
- Keep the penguin and cozy visual identity. Functional labels must describe the action plainly.
- Reveal detailed instructions, nutrition, sources, and advanced preferences only when needed; keep important findings visible.
- Final usability acceptance: the intended user attempts a recipe, a food check, a workout, and a food log without step-by-step coaching. Record where she hesitates or gets lost and fix those flows. Do not claim this test happened without her participation.

### 3.2 Shared context-above/chat-below interaction

Recipes, food checks, and workouts should feel like dedicated chats:

- A scrollable context/result panel appears above the conversation and composer.
- A text composer remains accessible at the bottom; the result panel compacts when the keyboard opens.
- Follow-up messages revise the current recipe, assessment, or workout rather than creating disconnected outputs.
- Each feature owns its conversation, draft, current result, error, and retry state.
- Stop/retry must not duplicate actions or let obsolete results replace newer results.
- Associate each result with the request, source evidence, and profile revision that produced it.
- Current instructions override ordinary saved preferences. Conflicts with allergy or injury constraints must be surfaced, not silently accepted as profile edits.

#### 3.2.1 One shared Mr. Poodles personality across all chatbot features

The user explicitly requires every chatbot feature to use the same personality already used by the existing companion Chat feature. This applies to recipe chat, food checks, workout chat, AI food logging, and any conversational planning or follow-up flow.

- Reuse the established companion personality rather than inventing separate food, fitness, or logging personas. Inspect the effective instructions in `backend/src/personality.js` and the companion prompts in `PoodlesViewModel.kt` before implementation.
- Preserve the warm, gentle, cute, supportive penguin voice: acknowledge feelings, avoid food/exercise shaming, forced positivity, guilt, baby talk, and pressure for attention.
- Preserve the existing emotional-response rules: ordinary sadness or crying gets comfort, not an assumed emergency; concrete immediate danger still receives appropriate urgent guidance.
- Compose task-specific instructions with one shared personality definition across primary and fallback providers. Follow-ups, clarification questions, and conversational confirmations must retain the same voice.
- Keep recipe quantities, workout instructions, nutrition estimates, verdicts, uncertainty, and source attribution clear and accurate. Personality must not weaken evidence checks or obscure important information.
- Sharing personality does not merge feature conversations or authorize sharing unrelated private chat history. Respect existing memory consent and current user preferences.
- Acceptance: compare representative replies from every chatbot feature, including fallback-provider replies, against the existing companion voice. Test that personality instructions coexist with task constraints and truthful uncertainty.

### 3.3 Recipe chat using published internet sources

Example request: **"tiramisu recipe"**.

1. Search published recipes, prioritizing identifiable authors and established cooking publications.
2. Retrieve the recipe content, quantities, and method. Do not treat a short search snippet as a complete recipe.
3. Check ingredients against enabled restrictions, diet, equipment, and practical preparation limits.
4. Search documented substitutions when ingredients conflict, including conflicts introduced by replacement ingredients.
5. Display an adapted recipe with quantities, units, servings, active time, cooking/chilling time, and ordered steps.
6. Explain each changed ingredient: original, replacement, reason, and supporting source.
7. Provide clickable original recipe and substitution sources. Distinguish an AI adaptation from the author's published recipe; do not claim human authorship of the adaptation or guarantee every indexed article is human-written.

Follow-ups include **"No coconut"**, **"Make two servings"**, and **"Use hostel equipment"**. If evidence for a suitable alternative is missing, explain that and ask a useful follow-up.

- Remove the fixed 34-ingredient generation limit.
- Remove the generation restriction to `assemble`, `soak`, and `warm`; real recipes may need other methods and timings.
- Keep domain validation, package checks, and equipment constraints appropriate to the new model.
- Save sourced recipes and their adaptations for planning and later review.

### 3.4 Text-only "Can I eat this?"

- Remove camera/gallery inputs, image previews, rotation/re-reading, label image processing, vision requests, obsolete provider configuration, and related copy/resources when no longer referenced.
- Accept a dish name, exact packaged product name, pasted ingredient list, or public product URL.
- Search manufacturer ingredient/allergen information first for packaged products.
- Ask about brand, variant, and country when identity is ambiguous. Typical dish ingredients are not a verified product label.
- Check ingredients, compound ingredients, relevant aliases, advisory statements, and cross-contact information against the profile.
- Preserve the distinction between allergy and intolerance, including lactose intolerance versus milk allergy.
- Show the evidence behind each detected conflict and the identity/version of the product reviewed.
- Use clear outcomes: **Avoid** for identified conflicts, **No listed conflict found** for no match in reviewed information, and **Need more information** for incomplete or conflicting evidence.
- An internet search cannot establish that a food is safe to eat. Missing allergen mentions, unreadable text, stale product information, or retrieval failure must not become an unconditional yes.
- Do not weaken restrictions automatically through chat. Permanent profile edits require explicit confirmation.

### 3.5 Workout chat using articles and YouTube

Example request: **"No equipment workout, calisthenics beginners"**.

- Retrieve published fitness articles that actually support the session.
- Respect experience, duration, equipment, space, noise constraints, and recorded injuries.
- Ask a brief clarification when an essential detail is missing rather than presenting a long setup form.
- Show warm-up, exercises, sets/reps or time, rests, form cues, easier alternatives, and cooldown.
- Show article sources and author/publisher attribution where available.
- Return one related YouTube video with a real retrieved URL, title, and channel.
- Label a partial match as a **Technique reference**, not an exact follow-along session. Do not imply the video was watched or its instructions verified from metadata alone.
- Follow-ups such as **"Only 15 minutes"**, **"No jumping, hostel room"**, **"Push-ups are difficult"**, and **"Make it easier"** update the session.
- Distinguish article-supported instructions from AI adjustments.
- Replace the fixed six-exercise generation library as the source of new workouts.
- Retain saved sessions and completion history; include a simple optional rest timer if it fits the session UI.
- Injury-related requests need appropriate clarification. A generic workout must not be represented as injury rehabilitation.
- Retrieval failure preserves the last valid result and offers retry without pretending a new session was sourced.

### 3.6 AI-assisted natural-language food logging

The user reports food logging is not working and wants rough internet-based nutrition estimation. The current implementation takes a manual name and calorie value; natural-language retrieval/estimation is not implemented.

Example: **"Pyaaz kachori khayi hai"**.

- Recognize an eaten-food statement, search relevant nutrition information, and automatically persist a rough estimate in today's diary and total.
- If quantity is absent, use a visible, editable assumption such as **1 medium kachori**. Do not invent an exact serving weight or hide uncertainty.
- Include calories and, when supported, protein/carbohydrates/fat. Unsupported values remain unknown, never zero.
- Normalize comparable servings before selecting a representative estimate; do not average `per 100 g` and `per piece` values directly.
- Keep source links, portion assumptions, estimate status, and a practical uncertainty explanation/range where evidence permits.
- Distinguish food-information questions from logging intent: **"How many calories in kachori?"** answers without creating an intake entry.
- Support several foods in one message and explicit dates. Use the device's local day for "today" and "yesterday"; clarify genuinely ambiguous dates.
- **"I ate two"** revises the same entry; **"Half"** scales it; **"There was chutney too"** adds the extra food.
- Provide Edit and Undo. Deduplicate retries and delayed responses; a correction must update, not duplicate, the entry.
- Log what was actually eaten even if it conflicts with a restriction. Any restriction warning is separate from the intake record.
- Show success only after persistence succeeds. Preserve recoverable input on failure.
- If internet evidence is unavailable, do not claim a sourced estimate. Offer retry or an explicit unknown-nutrition entry.

### 3.7 Keep and improve planning, shopping, and the diary

The user explicitly chose to keep these features and make them better.

- Browse arbitrary dates and full weeks, not only the next seven visible day chips.
- Add, move, replace, and remove individual meals; set portions per planned meal.
- Scale recipe quantities consistently with planned/eaten servings.
- Generate varied day/week schedules from sourced recipes; fetch more suitable choices when needed instead of repeatedly permuting three recipes.
- Preview a generated replacement before applying it. Do not overwrite user edits made while generation was pending.
- Revalidate meals after profile changes and show stale-profile review status.
- Provide day/week shopping aggregation, compatible-unit merging, explicit pantry deductions, and bought checkboxes.
- Keep incompatible units separate unless a supported conversion is available. Do not subtract arbitrary free-text pantry guesses.
- Support diary dates, corrections, unknown calories/macros, and no duplicate logs for the same intended action.
- Original recipe nutrition cannot silently survive ingredient substitution. Recalculate only from applicable reliable data or mark affected values unknown/estimated.

### 3.8 Settings cleanup and splash

Keep useful constraints: restrictions and their type, diet, workout level, injuries, equipment, space/noise, and simple Hostel/Home presets.

- Move cuisine, pantry, and budget to optional preferences; only present them as enforced when the implementation actually checks them.
- Separate active recipe time from waiting/cooking/chilling time.
- Allow workout duration/intensity changes through chat without duplicate setup forms.
- Keep aliases/tolerance notes in an advanced restriction editor.
- Put the optional calorie target with diary settings.
- Remove image/OCR controls, duplicate environment editing, unused prompt/settings fields, and controls with no tested effect.
- Preserve old stored fields when needed for migration; removing a control is not permission to destroy historical user data.

Splash finding: `MainActivity.kt` installs the native splash without a minimum display condition; the illustrated Compose splash is tied to local-data loading and disappears quickly.

Proposed accepted planning default: show the branded Poodles launch experience for **2 seconds on cold launch**, loading local data concurrently. Keep native/Compose handoff visually consistent, avoid double flashes, do not wait for network inference, and do not repeat the delay on tab changes or ordinary resume. Respect reduced-motion preferences and do not block the main thread.

## 4. Providers, budgets, and unresolved setup

### Confirmed constraints

- Email/Google sign-in is acceptable. Payment/card/bank information, phone verification, and KYC are not.
- Use renewable free allowances where possible, with independently configured free fallbacks.
- Public no-card documentation does not prove that a particular account will never encounter additional verification. Verify the actual setup path before enabling a provider; skip it if it violates the constraint.
- Do not invent keys, complete identity steps on the user's behalf, or describe unconfigured services as operational.

### Research snapshot (checked 2026-10-03; reverify before integration)

| Service | Documented allowance | Planned role / qualification |
| --- | --- | --- |
| Exa | $10 renewable monthly credits; up to 2,500 Instant searches at $4/1,000; extraction/extras consume balance | Preferred search; documentation says no payment method; sign-in shown. Actual account verification still untested. |
| Tavily | 1,000 credits/month; basic search 1 credit, advanced search 2; extraction can cost extra | Free search fallback; no credit card documented; complete signup flow untested. |
| SerpApi | 250 searches/month, 50/hour on Free | Optional final search fallback only after signup requirements are verified. |
| Cloudflare Workers AI | 10,000 neurons/day on Workers Free | Existing generation provider; keep free-compatible models and protect companion capacity. |
| Groq Free | Listed GPT-OSS models: 1,000 requests/day, 200,000 tokens/day, 30 requests/minute, 8,000 tokens/minute | Candidate generation fallback; actual organization limits and signup requirements need verification. |
| YouTube Data API | Current documentation: separate default 100 `search.list` calls/day; other endpoints have a 10,000-unit daily pool | Prefer dedicated video discovery to avoid consuming article-search credits. Verify project setup and actual quota. |
| Jina Reader | Basic unauthenticated URL reading documented at 20 requests/minute; keyed usage consumes tokens | Optional public-page extraction fallback, not an unlimited web-search API. Respect source access restrictions. |

Exa + Tavily + SerpApi have a theoretical upper bound of **3,750 basic searches/month before extraction/extras**. This is not a guarantee of 3,750 complete sourced sessions. A recipe adaptation can need multiple searches and model calls. The allowances are shared across this app's features and any other use of the accounts.

The earlier proposed application ceilings were **100 recipe requests/day and 100 workout requests/day**, with separate counters for food checks and companion chat. These are ceilings, not promised daily capacity. Benchmark realistic retrieval/context/output sizes before claiming practical capacity. Count deterministic local actions separately so serving changes, calendar edits, shopping checks, and timers do not consume search credits unnecessarily.

Fallback policy:

- Reuse sufficiently fresh, permitted cached evidence; product/allergen information needs more conservative freshness handling than general exercise articles.
- Track request, token/compute, and monetary-credit limits separately and reserve allowance before calls.
- On an unavailable/exhausted provider, try an eligible configured free alternative with its own budget and timeout.
- Honor `Retry-After`; do not shorten it with jitter. Cap retries and use provider cooldowns/circuit breakers.
- Keep the same schema, citation, restriction, and evidence checks across providers. A fallback is not permission to weaken validation.
- If all eligible providers are exhausted, preserve the draft and communicate the real limit/reset. Never pretend cached or model-only output is a fresh web search.
- Exa's auto-recharge setting of `$0` is documented as **no cap**, not disabled billing. Disable auto-recharge rather than relying on a zero spending field.

Excluded or unsuitable as the permanent baseline:

- Brave: payment-card verification is documented; excluded by user constraint.
- Cerebras: currently a $5 trial expiring after 30 days with a verified payment method; not a renewable free fallback.
- Serper: 2,500 signup queries advertised; do not count as monthly renewable capacity without confirmation.
- Jina keyed search's initial free tokens are not an established renewable monthly search grant.
- Gemini pricing retrieval timed out; no verified recommendation or allowance was established in this session.
- Event offers were checked: the joined Weekend Challenge returned no offers; Hacktoberfest returned an unrelated ElevenLabs offer. No search/inference credits were claimed.

### Official references

- Exa pricing: https://exa.ai/pricing
- Exa billing and monthly grant: https://exa.ai/docs/admin/billing
- Exa sign-in: https://dashboard.exa.ai
- Tavily credits: https://docs.tavily.com/documentation/api-credits
- Tavily search contract: https://docs.tavily.com/documentation/api-reference/endpoint/search
- SerpApi: https://serpapi.com/pricing
- Cloudflare pricing: https://developers.cloudflare.com/workers-ai/platform/pricing/
- Groq limits: https://console.groq.com/docs/rate-limits
- Groq billing: https://console.groq.com/docs/billing-faqs
- Groq structured outputs: https://console.groq.com/docs/structured-outputs
- YouTube quotas: https://developers.google.com/youtube/v3/determine_quota_cost
- Jina Reader: https://jina.ai/reader/
- Brave requirements: https://brave.com/search/api/
- Cerebras trial restrictions: https://inference-docs.cerebras.ai/support/rate-limits
- Serper signup allowance: https://serper.dev/

## 5. Architecture and known baseline findings

### Relevant files

- `app/src/main/java/com/mrpoodles/app/Domain.kt`: serialized profile, catalog recipes, meal/intake/workout models, food matching, nutrition, and domain rules.
- `PoodlesViewModel.kt` in the same directory: task execution, persistence coordination, recipe/plan generation, label/photo flows, intake logging, workout generation.
- `RecipeGeneration.kt`: fixed ingredient aliases, request intent, repeat signatures, catalog-only recipe prompts.
- `Screens.kt`: Check, Meals (plan/recipes/diary), Move, profile/settings, and dialogs.
- `AppShell.kt`: navigation, per-tab state, shared loading/error presentation.
- `ChatScreen.kt`: existing companion chat/composer patterns to reuse carefully without mixing feature conversations.
- `MainActivity.kt`, `CozyElements.kt`, `app/src/main/res/values/styles.xml`: native and illustrated launch experience.
- `CloudModel.kt`, `ServiceLimits.kt`: backend transport, parsing, cancellation, and cooldowns.
- `LocalStore.kt`: version-compatible JSON snapshots and atomic writes.
- `LabelPhoto.kt`, `PhotoPreview.kt`, `app/src/main/AndroidManifest.xml`: image-flow removal targets; inspect references before removing resources/provider declarations.
- `app/src/main/assets/nutrition.json`: 34-record catalog; retain compatibility for old estimates, but do not gate new recipes on it.
- `backend/src/worker.js`, `inference.js`, `schemas.js`, `quota.js`: model-only backend, structured output, fixed quotas. No web-retrieval implementation exists at the baseline.
- `backend/wrangler.jsonc`: Workers AI and Durable Object bindings.
- `app/src/test/java/com/mrpoodles/app/`, `backend/test/`: existing test suites.
- `scripts/build.ps1`, `app/build.gradle.kts`: build boundaries and asset requirements. The Gradle asset verification currently requires `nutrition.json` and `NOTICES.txt`.

### Findings from source review (not all reproduced on a device)

1. `FoodRules.check()` does not mark `[unclear]` as uncertainty; a complete-marked `rice, [unclear]` can produce "No listed trigger found".
2. A newly selected photo changes the preview before transcription succeeds; prior label text/verdict can remain if reading fails. Removing the image flow eliminates this path.
3. Recipe intent negation can cross positive clauses: `pasta without soy, with tomato` can exclude tomato. The negation regex behavior was confirmed with an in-memory regex probe, not an Android test.
4. `saveDraft()` clears the draft before asynchronous persistence succeeds.
5. Several recipe views call `Nutrition.calculate()` or `foods.first()` without recovering from invalid saved ingredients/quantities; snapshot deserialization is not domain validation.
6. Week planning requires only three candidates and validates distinct meals within a day, not meaningful weekly variety.
7. Budget/pantry are supplied as prompt text, not deterministic constraints. Shopping currently covers the selected day and whole-recipe quantities, while planned calories use one serving.
8. Intake dates and corrections need improvement; current manual logging uses today's date. Natural-language food estimation is absent.
9. Existing workout generation selects from six fixed exercise IDs and pauses generation whenever an injury string is present. Redesign injury handling deliberately rather than accidentally dropping the constraint.

The historical build report says 60 Android/JVM tests and 19 backend tests passed for v0.3.1. Those baseline-only reports are not proof of the redesign; the current 78-test Phase 1 Android run is recorded separately below. Backend counts remain historical. Physical-device testing was still pending in that report.

### 5.1 Phase 1 implemented contracts (2026-10-03)

**Local format and migration**

- Keep the existing `poodles-v1.json` filename and atomic pending-file replacement; `AppData.schemaVersion` is now `2`. Missing versions are treated as historical version 1. Explicit versions outside 1–2 fail read without overwriting the file.
- All historical profile/environment fields, recipe catalog IDs/grams, recipe preparation, meal IDs/dates, nullable intake nutrients, checks, chat/memory fields, and the current workout/completion date remain represented. New fields have defaults. This supports upgrading historical snapshots, not an old binary editing new fields safely after downgrade.
- `SnapshotMigration.decode` migrates in memory. Ordinary reads do not rewrite source bytes. The existing AtomicFile `.bak` recovery remains supported. Missing historical record IDs receive deterministic IDs rather than changing on every read.
- Structurally malformed individual list records or optional recipe/workout records are retained as `RecoveredRecord(path, raw, reason)`, with valid neighbors still available. Semantic problems such as obsolete ingredient IDs or invalid amounts remain in their original records. Malformed whole snapshots, profiles, feature maps, and operation ledgers fail closed rather than resetting user data or dropping deduplication receipts.
- Recovery records survive subsequent saves. The app displays a recovery notice; a user-facing repair/import/export workflow is not part of Phase 1. Quarantined raw content must not be sent as model instructions or silently treated as usable data.

**Evidence and future feature state**

- `EvidenceModels.kt` separates `UserAssertion`, request-bound `RetrievalSnapshot`/`RetrievedSource`, excerpt references, and `RecipeAdaptation`/`Substitution`. Named quantities and units, separate active/cooking/waiting time, portions, nullable nutrition/uncertainty ranges, product identity/outcomes, workout instructions and adjustments, and video metadata/match type have serialized representations.
- `Recipe.sourced` and `Workout.sourced` are additive; legacy fields remain intact. Recipe/workout generation now attaches request/profile origin metadata without claiming internet retrieval. `retrieval = null` means no retrieved evidence, not a verified source.
- `AppData` stores a recipe draft, per-feature conversation state, saved workout/completion collections, intake receipts, and recovered records. Future feature states include draft, messages, result, active/retry identity, error, selected date, and scroll position; they remain separate from companion history.
- `FeatureConversation` rejects old attempts, edited input, changed profiles, and mismatched result origins. An interrupted persisted request becomes retryable after loading, preserving draft/result/date/scroll; it never resumes network work automatically. A retry uses a fresh request identity and retains its logical operation identity.
- Existing feature UIs are not yet converted to these future chat states. Navigation redesign is Phase 3, sourced integrations are Phases 2/4/5/6. New sourced recipes are excluded from legacy catalog nutrition/planning until their dedicated validation exists; no empty ingredient list becomes a zero-calorie estimate.
- Persisted feature conversation messages follow the existing `rememberChats` policy; companion messages and comfort memories retain their independent existing consent behavior. No cross-feature private history is added to prompts.

**Commit and operation boundaries**

- The existing single-active-task ViewModel now creates request identities. Profile changes, cancellation, label edits, and edits to an in-flight recipe request invalidate ownership. Status/stream callbacks and completed tasks cannot keep publishing as an old request.
- `commitData` rechecks request/profile ownership inside the persistence mutex. `LocalStore.save` checks the request fence immediately around the atomic file move. Invalidation and the move share a lock: an attempt invalidated before the move cannot commit. A move that finished before cancellation is a committed action; its in-memory snapshot is published even if its caller is then cancelled.
- User saves and generated state commits publish data only after the disk operation succeeds. Recipe drafts remain on failed saves and are restored on a new ViewModel. Failed/repeated recipe generation retains the last draft. Manual logging/correction dialogs keep recoverable input, including saved-instance restoration; success callbacks close only the submitted draft, not newer edits.
- `IntakeOperation` contains a logical operation ID, stable target ID, expected entry revision, and optional replacement entry (null means delete). `IntakeOperations.apply` stores the effect and its SHA-256 receipt in the same snapshot. Same ID/payload is a no-op; changed payload is rejected. Corrections keep the entry ID and advance its revision. Deleted entries retain receipts so delayed creates/corrections cannot restore them.
- Callers of `applyIntake` must keep the **entire original operation**, including date, target ID and expected revision, for retries. Manual dialog IDs survive recreation and change when input changes. Later natural-language logging must persist prepared operations with its feature state; it must not rebuild a retry from a new date or current entry revision.
- Generated plans compare the target dates' current meals against the request's original meals at commit time, preserving intervening user edits. Replacement-preview UX remains Phase 6.
- These guarantees cover this app's serialized local writes and current single-active-task model. They are not claims of distributed transactions, multi-process conflict resolution, provider-side idempotency, power-loss testing, or completed later-phase integrations.

**Graceful legacy rendering**

- Recipe cards, saved shelves, and planned meals use nullable nutrition calculation and safe ingredient lookup. Invalid/unknown nutrition is explicitly unknown; incomplete totals are labeled. Historical invalid records are not removed to make the UI render.
- Invalid saved workouts display review status and cannot be marked complete through their UI. Existing catalog rules are retained for current generation. Full sourced validation and shared personality composition remain the explicitly scheduled later phases.

### 5.2 Phase 2 implemented contract and current boundary (2026-10-03)

See `docs/PHASE2_BACKEND.md` for the request/response schema, reviewed-source egress boundary, account eligibility/setup steps, provider budgets, cache policy, and live-check procedure.

- Added bounded Exa/Tavily search and extraction adapters, Workers AI/Groq model paths, and YouTube metadata lookup. New providers require both credentials and explicit operator attestation of a verified free-only account. Local credentials/flags are present, and the user has confirmed completion of the account review; independent account measurements remain limited to the recorded audit.
- Added `POST /v2/research` for research notes and request-bound extracted evidence. Existing `/v1/help` text/SSE/catalog contracts and legacy quota records remain supported. New Android `ResearchContract`/`CloudModel.research` code is ready for later feature integration but is not connected to new UI actions here.
- Reused `CHAT_PERSONALITY` through one `composePersonality` function for all current/new tasks and fallback model inputs. Schema/accuracy/evidence rules retain priority. New legacy Groq fallback is buffered for chat and starts only before a primary stream is delivered; it does not bypass app quota refusals or add vision fallback. It also requires explicit per-request `allowExternalFallback`; existing clients do not send it and remain Cloudflare-only. V2 `allowExternalModel` defaults false. Future UI integration must obtain appropriate provider consent before setting either flag.
- Retrieval uses only reviewed exact source hosts and fixed external extraction APIs, not direct arbitrary-page fetching. Credential-bearing API redirects are rejected; unapproved/local/IP URLs are rejected. The extraction service's internal crawl/redirect behavior remains that provider's responsibility; the adapter verifies the returned URL matches the requested URL.
- Extracted text, not snippets or generated summaries, is retained. Source facts must be verbatim quotes with valid snapshot/source references. AI adjustments are separately labeled. This checks attribution, not truth, completeness, safe-to-eat status, or full recipe/workout suitability.
- Research synthesis selects task-relevant windows of at most 600 characters/source while the response retains up to 12,000/source. It reduces source count/window length to fit the conservative 7,500-token Groq reservation, preserving personality and constraints; an input that still cannot fit fails with 413. Workers AI receives the explicit research JSON schema. Complete sourced feature generation and its domain validation remain later-phase work. This endpoint never logs food, saves a recipe, changes a profile or overwrites a plan.
- Persisted provider budgets track requests, tokens, micro-USD/credits, compute and cooldowns separately. V2 Workers compute also debits the legacy assistance pool, preserving companion capacity. Local budget windows are UTC; actual account reset calendars and other account consumers still need verification. Android honors the new error scopes and monthly `Retry-After` delays without shortening them to a day.
- Public-page caching is per-isolate, bounded to 32 entries and disabled by default. Explicitly permitted manufacturer entries are limited to 15 minutes; other permitted entries to one day. No prompts/private profiles/answers are cached. Cache hits preserve retrieval timestamps and receive a new snapshot identity.
- The new fields on `RetrievedSource` are additive defaults (`kind`, `cached`, `completeness`) and retain schema-2 compatibility. Phase 1 migration/state tests still pass.
- Current limitations: live MAGGI pages lack a complete product ingredient/advisory label; some recipe candidates fail extraction; short model windows omit available page facts and occasionally end mid-sentence. Source completeness remains unverified. Live personality/fallback samples now exist, but they do not prove universal model compliance. Account review completion is user-reported; unseen dashboard values are not independently verified. SerpApi/Jina remain optional candidates, not implemented fallbacks.

### 5.3 Phase 3 interaction contracts (2026-10-03)

- Home has four explicit actions: **Can I eat this?**, **Find a recipe**, **Find a workout**, **Log what I ate**. They open the relevant checker, recipe workspace, workout screen or manual diary dialog directly. **Talk to Poodles**, today's total and the next unlogged planned meal remain discoverable. Typed `HomeAction` values replace numeric destination indexes.
- The recipe workspace is a detail route; Back returns to its origin (Home or the recipe shelf), while the labeled Home action returns Home. Root tabs retain their prior state. The Meals subpage is hoisted so Home can open the diary or planner intentionally. Its calendar/meal selection remains in the existing saveable destination state.
- `ContextChatLayout` is a reusable context-above/conversation-middle/composer-below container. Companion chat and the recipe workspace use it. Long context scrolls within a bounded panel, compact context has independent scroll state, and opening the keyboard does not reset expanded-context scroll. Small screens expose recipe Details and the saved shelf without hiding the composer. Chat options, including conversation deletion, remain available in compact mode.
- The recipe request lives in `SavedStateHandle`; submitted-request and sheet state use saveable composition state. Recipe draft results still use Phase 1 disk persistence. Recipe failures remain inline with their draft/result and a retry action; editing invalidates the old retry. Recipe error ownership is separate from companion chat errors. Save confirmation is based on the saved recipe appearing in the committed data snapshot, not on clicking Save.
- This workspace currently calls the existing catalog-based recipe generation. It explicitly labels that boundary and does not claim internet sourcing or follow-up adaptations. Food checks and workouts retain their legacy implementations until Phases 4–5. Home's logging shortcut opens the existing manual dialog; natural-language logging remains Phase 6. No hidden external-provider opt-in was introduced.
- Settings retain restrictions/types, environment presets, equipment, preparation time, workout experience and injuries. Optional food preferences, diary target, comforts and memory controls use expandable sections. Budget/pantry text explicitly states its current non-enforced nature. Duplicate environment editing and the unvalidated movement-goal editor are removed; all stored fields and both environments remain intact. Alias/tolerance editing is available under advanced restriction details.
- `ColdLaunchGate`, retained by the activity ViewModel, gives the Compose launch experience a two-second minimum while local loading runs concurrently. It records completion so recreation/resume cannot replay the timer. It never waits for network inference or sleeps the main thread. Native splash removal hands off to Compose using the existing matching background; the existing reduced-motion theme still governs illustration motion. Physical-device visual handoff remains a final release check.

## 6. Phase deliverables and acceptance gates

### Phase 0 — Documentation and context freeze

Deliver `AGENTS.md` and this master plan. Verify all confirmed requirements, provider caveats, release boundaries, and session-resume instructions are recorded. Review the diff and ensure only documentation changed. Stop.

### Phase 1 — Data and state foundations

- Design backward-compatible representations for sourced recipes, named ingredients/units, substitution evidence, workouts, videos, feature conversations, portions, and nutrition estimates.
- Separate user assertions, retrieved evidence, and AI adaptations in the data model.
- Preserve historical recipes, meals, profile fields, and intake data. Unknown nutrition stays nullable/explicitly unknown.
- Introduce request identity/profile revision checks and idempotent operation identities for logs and corrections.
- Fix draft persistence semantics and graceful handling of invalid saved records.
- Integrate incrementally so the existing app compiles while later UI/features are still pending.

Acceptance: migration fixtures preserve old data; failed saves retain recoverable state; retries/corrections cannot duplicate intake; obsolete results cannot commit; invalid records do not crash rendering paths. Run relevant unit tests. Stop.

### Phase 2 — Retrieval and free-provider backend

- Implement bounded search/retrieval adapters and separately configured model adapters.
- Establish shared personality composition from the existing companion Chat instructions for every chatbot task and provider, as required in section 3.2.1.
- Validate external URLs, redirects, response sizes, and timeouts; do not fetch local/private network targets or forward credentials to arbitrary pages.
- Treat retrieved content as untrusted data, not system instructions. Keep citation IDs tied to the request's retrieval snapshot and evidence excerpts tied to fetched content.
- Support trusted-product-source preference, article extraction, and video lookup.
- Keep queries limited to necessary food/workout facts, not unrelated conversation history or private profile events.
- Implement renewable free budgets, cooldowns, caching, fallback eligibility, and actionable errors. Update backend/client limit contracts coherently.
- Verify actual signup requirements and configure secrets without printing them. Account setup requiring user login is an explicit external dependency.
- Keep backend API compatibility explicit; no silent production deployment.

Acceptance: tests cover real response shapes, invalid citations, source failure, prompt injection, malicious URLs, timeouts, quota exhaustion, reset times, cancellation, and provider fallback. Run bounded synthetic live checks only for configured providers; record actual source quality and latency. Missing live checks remain pending. Stop.

### Phase 3 — Simple interaction shell, navigation, settings, and splash

- Implement the easy Home/navigation requirements and shared context/chat/composer layout.
- Keep feature state isolated and provide examples, inline progress/errors, retry, and clear next actions.
- Simplify profile/settings without destroying migration data. Do not advertise future controls as working.
- Add the cold-launch 2-second branded experience with a stable handoff and no main-thread sleep/network wait.
- Keep the existing simple English UI as the provisional default; the English-versus-Hinglish question remains unanswered.

Acceptance: Compose tests cover core reachability, labeled controls, Back navigation, tab switching, recreation, large fonts/small screens, and keyboard-visible composer access. Test splash timing and resume behavior. No new APK is needed for Robolectric/Compose unit checks. Final user usability testing remains a Phase 8 device gate. Stop.

### Phase 4 — Recipe chat and text-only food assessment

- Connect sourced recipes and follow-up adaptations to the shared UI and saved recipes.
- Implement evidence-backed product/dish checks, ambiguity questions, restriction matching, and explicit uncertainty.
- Remove the complete image input/vision feature and its dead code/resources/copy once reference checks confirm they are unused.
- Retire fixed catalog/method constraints for newly sourced recipes while preserving old records.

Acceptance: test tiramisu with lactose/soy restrictions, replacement conflicts, excluded ingredients, servings, equipment, recipe provenance, ambiguous product variants/countries, missing labels, conflicting sources, unknown aliases, invented citations, and retrieval failure. Assert no camera/gallery/vision entry points remain. Stop.

### Phase 5 — Workout chat and video references

- Generate sourced sessions and apply follow-up edits using the shared state/UI.
- Show duration, sets/reps/rests, form, alternatives, source attribution, and a related retrieved video.
- Add saved sessions/completion and a simple optional rest timer without introducing unnecessary navigation.
- Handle injury clarification and video mismatch/unavailability explicitly.

Acceptance: test beginner no-equipment calisthenics, quiet-room/no-jumping constraints, short duration, difficult-exercise substitutions, restriction/profile changes, source evidence, video relevance, invalid/deleted video metadata, retries, and cancellation. Stop.

### Phase 6 — Natural-language diary, planning, and shopping

- Implement eaten-versus-question intent, source-backed rough nutrition, visible portion assumptions, automatic logging, and follow-up corrections.
- Support local dates, multiple foods, unknown nutrients, Edit/Undo, and durable idempotency.
- Improve arbitrary-date/week planning, servings, source-library variety, and replacement previews.
- Implement unit-aware day/week shopping aggregation and explicit pantry/bought state.

Acceptance: test kachori with missing quantity, two pieces, half portions, added chutney, multiple foods, yesterday, calorie questions that must not log, restricted-but-eaten foods, unconvertible units, source disagreement, unknown macros, failed saves, retries, totals, week variety, and concurrent calendar edits. Stop.

### Phase 7 — Regression and release readiness

- Run full relevant Android unit/Compose tests, lint, backend tests, and backend dry-run checks.
- Exercise transitions between all features, profile revisions, saved state, offline behavior, quota exhaustion, and recovery.
- Reconcile source freshness, privacy copy, provider claims, settings behavior, migration, and documented limitations.
- Verify the same established companion personality across recipe, food-check, workout, food-log, and conversational planning replies, including free-provider fallbacks (section 3.2.1).
- Close implementation blockers. Do not convert missing keys, unperformed live tests, or absent device access into successes.
- Prepare a release checklist and final artifact configuration without packaging an APK/AAB.

Acceptance: all pre-packaging checks pass, remaining external/device checks are explicitly listed, and the user authorizes Phase 8. Stop.

### Phase 8 — Final artifact, verification, commit, and push

This is the first phase permitted to generate a new APK/AAB. The requested deliverable is an APK; an AAB is not automatically required.

1. Verify backend configuration, free-tier account state, client/API compatibility, and signing-key continuity. Coordinate any explicitly authorized production backend deployment.
2. Update version metadata appropriately and run final packaging/checks using the intended signing setup.
3. Audit APK contents/configuration, verify the signature, and record artifact path, size, and SHA-256.
4. Install over the prior version with the same signing key when a device is available. Check migration, keyboard behavior, splash, and the intended user's task-based usability. Fix and rebuild within this phase as needed.
5. Record actual results and remaining limitations in this file and release/device documentation. An untested usability gate remains untested.
6. Before committing, inspect `git status`, `git diff`, and `git log --oneline -10`; verify branch/upstream and stage only intended source/documentation changes. Never include secrets or unrelated work.
7. Commit with a concise message consistent with repository history, run hooks normally, and push to the verified intended upstream. Do not force-push, amend unrelated commits, or change Git configuration.
8. Deliver the APK path and verification summary plus commit/push status. APKs/AABs are ignored by this repository; do not force-add binaries or create a release/PR without additional authorization.

If packaging, hooks, device checks, or push fail, record the exact blocker and do not claim the release completed.

## 7. Verification commands and artifact boundaries

These commands are reference instructions, not evidence that they were executed in Phase 0.

Before Phase 8, use explicitly selected non-packaging tasks, for example in PowerShell:

```powershell
.\scripts\build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug')
npm test --prefix backend
npm run check --prefix backend
```

The backend `check` script is `wrangler deploy --dry-run`; it does not authorize production deployment. Inspect the Gradle dry-run graph if tooling/task dependencies change. Do not run broad Gradle `build`, `assemble`, or `bundle` tasks, `:app:assembleDebug`, `:app:assembleRelease`, `:app:bundleRelease`, or the default build script before Phase 8. Unit-test compilation/resource processing is not permission to create an APK.

Baseline tooling: Windows PowerShell, JDK 17, Android SDK 35, Kotlin/Compose, Node.js backend tooling, Python utility scripts. `scripts/build.ps1` recognizes workspace tooling under `.tools/` and sets its Gradle cache. Read current scripts before relying on these details.

Final APK's existing conventional path: `app/build/outputs/apk/debug/app-debug.apk`. Select and document the actual final variant/signing path in Phase 8. An old APK at this path is not a new verified artifact.

Ignored sensitive/generated paths include `.poodles.properties`, `.env*`, `.dev.vars*`, `backend/.secrets/`, signing keystores, `**/build/`, `*.apk`, and `*.aab`. Do not print credential-bearing configuration while inspecting it.

## 8. Decisions still requiring evidence or user input

- UI language: existing simple English is the provisional default. The user has not answered the English-versus-Hinglish question. Session conversation is terse Hinglish; that does not authorize translating all app copy.
- Provider setup is complete on the basis of recorded live checks, a partial automated audit and user-confirmed account review. Unexposed dashboard values are not independently measured; do not overstate the evidence or promise universal signup requirements.
- Actual free capacity and response quality need realistic sourced-recipe/workout benchmarks. Earlier 100/day feature ceilings are not validated throughput commitments.
- Exact video matching can require more than title/channel metadata. Report what was actually checked; do not fabricate transcript access.
- Device availability, signing continuity, backend deployment coordination, and the intended user's availability are final release dependencies.
- Keep API contracts/data migrations workable between phases. A later feature's absence must not be hidden by fake data or unconditional success messages.

## 9. Research rationale (secondary references, not proof of implementation)

- [Check AI Citations Against Retrieved Source IDs](https://dev.to/ranknod/check-ai-citations-against-retrieved-source-ids-3hi8), [Ranknod](https://dev.to/ranknod), tags `ai`, `llm`, `rag`: reject citation IDs absent from the retrieval snapshot. This does not prove the cited page supports the claim.
- [Your AI cited a real file. It still lied to you.](https://dev.to/enhanciar_ai_96a4ba4877e3/how-we-make-an-llm-cite-every-answer-down-to-fileline-and-check-the-citation-is-telling-the-truth-hmj), [Enhanciar](https://dev.to/enhanciar_ai_96a4ba4877e3): distinguish unsupported and unverifiable evidence; do not mark failed verification as supported.
- [Retry, Backoff, and Circuit Breakers for LLM API Calls](https://dev.to/draganristicrsjpg/retry-backoff-and-circuit-breakers-for-llm-api-calls-h3k), [author](https://dev.to/draganristicrsjpg): bounded retries, cooldowns, and workload-tested fallbacks. Its sample jitters below the `Retry-After` duration; do not copy that flaw.
- [Top Best Practices for Successful Mobile App Design](https://dev.to/dhruvjoshi9/top-best-practices-for-successful-mobile-app-design-4lbi), [Dhruv Joshi](https://dev.to/dhruvjoshi9): familiar labels, one primary action, progressive disclosure, and task-based usability checks. This is general advice, not evidence that the intended user can operate this app.

The comment threads inspected for these articles had no comments. No broad community consensus or measured usability improvement was established. Use official provider documentation for quotas and terms; verify application behavior with tests.

## 10. Checkpoint log

### Phase 8 source delivery and handoff — 2026-10-04

- Inspected status, diffs, recent commits and the intended GitHub remote before committing. Staged only the reviewed application/backend/scripts/fixtures/docs. The staged audit passed **108 added/modified files**, with all **five configured credentials absent** and no APK/AAB/signing/local configuration files. Three authorized retired files were deletions, for **111 changed files** total. An initial audit rule matched its own example private-key string; anchoring detection to an actual PEM header line fixed that false positive, and the audit then passed.
- Created source commit **`a88b31c` — `Add sourced chats, durable diary and meal planning`**. Normal commit succeeded and `git push origin main` succeeded: `983459e..a88b31c`. No force push, hook bypass, amend, Git configuration change or binary force-add was used.
- This follow-up changes documentation only, recording the completed source push and next device-dependent work. Application and deployed backend code still match the tested artifact's release source. APK hash/certificate and deployment ID remain in `docs/RELEASE_0.4.0.md`.
- Delivery work is finished. **Full Phase 8 device acceptance is not complete:** no device was attached, so upgrade migration, OEM keyboard/splash and intended-user usability remain untested. Do not claim all acceptance passed or reinstall by uninstalling first.

### Phase 8 artifact and deployment verification — 2026-10-04

- Built **0.4.0 / version code 7** with the existing debug signing identity, verified against the prior APK before replacement. Final build command assembled the APK and reran all **324 Android/JVM/Compose tests**, including configured actual-evidence replays, with **0 failures/ignored**; lint passed. Backend **67 tests** and dry run passed after version metadata changes.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, **61,124,859 bytes**. SHA-256: `ec86b57fd3fdd48a4d2acc8502d2dcf17ad18847615a5f5c64b71292bdaec251`. Signature v2 verifies; certificate SHA-256 matches the old APK: `384fc33f9cce3f83a132903dfa694b563300ae9267144370b6931ce27f3b47f8`. Manifest reports version 0.4.0/7 and no camera permission.
- APK audit verifies required assets, the preserved 34-record legacy nutrition catalog, expected backend/app configuration, no retired runtime/photo provider resources, and absence of all four configured backend provider keys. Notices and their generation script now describe text-only checks and optional research providers accurately.
- Production preflight matched the authenticated Cloudflare account's subdomain to `https://mr-poodles.achityagulatibvc.workers.dev`, verified the existing Worker and app credential, and validated local provider eligibility/source policy without printing secret values. The existing app token was not rotated. Selected configuration went through Wrangler stdin, not command arguments or committed files.
- Backend deployment passed: version ID **328eb8f4-c16d-42d5-a402-a568da02f4f8**. Health/version, authenticated status, v2 route, retired image rejection and unchanged quota after invalid requests all passed. One deployed synthetic research request passed **HTTP 200, Groq, 1 source, validated quotations, 2,827 ms**. A deployed companion probe passed in **2.74 seconds**; its optional expression marker was absent/invalid, so the client uses its existing tested default expression behavior.
- `adb devices -l` showed **no attached devices**. Installation/update migration, OEM keyboard/splash behavior and the intended user's unaided task test were not performed. Signing continuity and automated tests do not count as those device checks. Keep Phase 8 device acceptance explicitly pending.
- Artifact and backend delivery checks are finished. Intended-source review/staging, commit and push are next; no source-delivery success is claimed before those commands. Full metadata is in `docs/RELEASE_0.4.0.md`.

### Phase 8 authorization and release start — 2026-10-04

- The user answered **“krde”** after the explicit question: “Phase 8 shuru karun—APK packaging + final commit/push? Production backend deployment ki permission bhi chahiye.” In context this authorizes the coordinated backend deployment as well as final APK packaging, verification, commit and push. No further intermediate approval is required for that scope.
- Re-read the current master plan and AGENTS instructions, inspected status/recent history/remote, and preserved all uncommitted earlier-phase files. Branch is `main`, upstream `origin/main`, remote `https://github.com/achityagulatibvc-bit/Mr.Poodles.git`, HEAD baseline `983459e`.
- Initial `:app:signingReport` passed. Debug uses the existing AndroidDebugKey, SHA-256 certificate `384fc33f9cce3f83a132903dfa694b563300ae9267144370b6931ce27f3b47f8`. Release has no configured signing key. Verify this certificate against the prior APK before choosing the final compatible debug artifact; do not generate a replacement signing key.
- Deployment, new artifact, commit/push and device checks are not yet complete. Actual results will follow.

### Phase 7 final pre-packaging checkpoint — 2026-10-04

- Resumed under repeated “continue” and “do it quick” requests. Finished the remaining functional fixes rather than merely relabeling the earlier source-coverage gaps. All changes remain uncommitted on `main`, preserving earlier phases.
- **Workouts:** duration follow-ups now produce deterministic bounded workloads with exercise/dose, rest/transition and recovery caps. Validation reconstructs the program from original article blocks. Broad, justified beginner/bodyweight video metadata can qualify as a technique reference, never an exact watched session. Added a bounded, exact-source NHS adapter and at most three supplementary research requests for warm-up, strength and cooldown. Wall availability must be explicit; chair/weight exercises are not silently substituted. The supported NHS program uses a 6-minute warm-up / 4-minute main / 5-minute cooldown schedule; it is an AI-selected combination, not an NHS-authored combined routine.
- **Recipes:** actual Good Food extraction exposed valid quantity and chilling ranges, multiple serving descriptions and ingredient subsection layouts. Added optional ingredient upper bounds, waiting-time upper bounds and an explicit original serving description/basis note. Scaling validates both bounds; shopping treats ranged amounts as uncertain rather than silently totaling the lower end. The actual Garden tomato salad passes the explicitly tested lactose/soy profile, scales from the stated six-serving basis to two, and still rejects a black-pepper restriction. This is not a successful tiramisu adaptation or an all-profile guarantee.
- **Nutrition:** actual MyFoodData replay caught the equivalent mass declaration `100 grams (100g)` being rejected. Equivalent repeated mass is now supported; contradictory mass stays invalid. The actual banana table produces **89 kcal / 100 g**, persists one entry, halves it to **44.5 kcal**, and Undo restores the earlier quantity without another lookup.
- Added opt-in `LiveNutritionReplayTest`, `LiveWorkoutReplayTest` and `LiveRecipeReplayTest`. They consume captured actual public provider responses while substituting Android transport and rebinding request identities. Nutrition/workout replays exercise the final ViewModel and persistence; recipe replay exercises the service, serving edit, restriction validation and shopping bounds. Existing synthetic integration tests cover remaining storage/retry cases. Without explicit capture environment variables these optional replay tests skip; no skipped replay is an acceptance pass.

#### New actual provider results

| Probe | Measured result |
| --- | --- |
| `workout --cloudflare --with-groq --next-minute` | HTTP 200, Cloudflare, 20,973 ms; ACE index/program/articles plus related video. Not enough complete technique/cooldown evidence for the old parser. |
| `workout --cloudflare --with-groq --next-minute --capture` | HTTP 200, Cloudflare, 18,005 ms; captured full ACE evidence for review. Supplementary sources were needed. |
| `nutrition --cloudflare --with-groq --next-minute --capture` | HTTP 200, Cloudflare, 12,455 ms; actual MyFoodData/USDA banana table, 7,773 characters. Replay initially failed on repeated mass; fix and replay passed. |
| `workout-nhs-warmup --with-groq --cloudflare --next-minute --capture` | HTTP 200, Cloudflare, 13,142 ms; 2,584 characters from the approved NHS warm-up page. |
| `workout-nhs-strength --with-groq --cloudflare --next-minute --capture` | HTTP 200, Cloudflare, 10,906 ms; 3,336 characters from NHS strength exercises. |
| `workout-nhs-cooldown --with-groq --cloudflare --next-minute --capture` | HTTP 200, Cloudflare, 11,262 ms; 2,051 characters from NHS cooldown guidance. |
| `phase7-salad --cloudflare --with-groq --next-minute --capture` | HTTP 200, Cloudflare, 18,804 ms; chickpea salad evidence retained, not claimed as a suitable all-restriction result. |
| `phase7-tomato --cloudflare --with-groq --next-minute --capture` | HTTP 200, Cloudflare, 24,176 ms; Garden/Spanish tomato sources. Garden source passes the restricted-profile replay after range/layout fixes. |

Commands use `node backend/scripts/local-live.mjs <case>`. Raw public evidence captures are under ignored `backend/.wrangler/live-evidence/`; Git ignore checks passed. Captures contain no request headers, generated app token or local provider configuration. They are test artifacts, not the application evidence cache. Existing quotas/cooldowns were preserved. No production deployment occurred.

#### Final checks and release decision

- Final full command set the three explicit capture environment variables and ran `scripts/build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug')`: **BUILD SUCCESSFUL in 2m 50s; 324 tests, 0 failures, 0 ignored; lint 0 errors / 6 dependency-version warnings.** Reports were read after the run. This supersedes the prior 285-test Android baseline and intermediate focused runs.
- Intermediate findings remain recorded: first Kotlin build exceeded its shell timeout; subsequent execution completed. One old integration assertion expected unchanged source IDs; it was updated to compare every source field while allowing intentional snapshot-local ID rebinding. The initial actual banana replay failed, then passed after the mass-label fix. No acceptance assertion was removed to disguise source failure.
- Backend production source did not change during this resume. Prior **67 passed backend tests** and **58.98 KiB / gzip 16.95 KiB dry run** remain the latest production-backend results. The local probe script gained fixed cases and explicit capture output only.
- R1–R5 are resolved. The additional workout-duration and known real-source parsing gaps now have targeted tests and successful captured-evidence replays. Missing/mismatched product or food evidence still yields uncertainty. Pyaaz kachori's previous wrong-food results are not a nutrition pass; its explicit unknown-save path remains the correct fallback.
- Visible service replies were reviewed against the shared personality contract: gentle language, no food/exercise shaming, no invented source facts, and explicit uncertainty. Provider prose is not used as authoritative instructions/nutrition. Primary/fallback instruction equality and earlier live provider voice samples remain recorded separately; this is not a universal live personality guarantee.
- **Pre-packaging checks are complete for supported flows. Await the required user confirmation before Phase 8.** True Android-to-deployed-backend/device usability, signing continuity and upgrade installation remain explicit Phase 8 dependencies. Backend deployment additionally needs explicit permission. No APK/AAB, version change, commit, push, post or session upload has occurred.

### Phase 7 resume after urgent pause — 2026-10-04

- User said “continue” in Build mode. Read the master resume/pause record and `AGENTS.md`, then inspected `git status --short --branch`. All prior uncommitted work is preserved on `main...origin/main`; no staging or release operation occurred.
- Reconciled `README.md` and `docs/RELEASE_READINESS.md`: R1–R5 are resolved, with actual fixes/tests recorded and original findings retained as historical context. Real-source coverage gaps are separate from the fixed integrity defects.
- Read the final pre-pause lint report: **0 errors, 6 dependency-version warnings**. The additional window-size warning is gone. The prior full **285-test** Android run, subsequent **5-test** UI run, **67 backend tests** and Wrangler dry-run results remain prior measured executions, not rerun merely to resume.
- Continue Phase 7 source-coverage review within configured free budgets. No live final-client acceptance, production deployment or device success is inferred from these results.

### URGENT PAUSE / EXACT RESUME POINT — 2026-10-04

**User explicitly requested an immediate pause and a Markdown handoff for the morning. Stop after this checkpoint. No background work or automatic resume is intended.**

#### Completed in this session

- Read the full plan, inspected existing uncommitted Phase 1–3 and partial Phase 4 work, and preserved it. The user updated `AGENTS.md` to permit subagents and continuous work. Its obsolete intermediate-stop instruction was reconciled with that later instruction; written phase acceptance and the required pre-Phase-8 confirmation remain.
- Phase 4: integrated sourced recipe chat and text-only food checking; complete camera/gallery/vision removal from client, manifest/provider resources, backend and obsolete probe. Added evidence/domain, persistence, consent and UI checks. Source settings now permit revocation and optional Groq fallback changes. See the detailed Phase 4 checkpoint below.
- Phase 5: added article-backed workout chat, supported local follow-ups, source/video validation, saved sessions, completion deduplication and rest timer. Wired ViewModel/navigation and retained legacy workouts. See the Phase 5 checkpoint.
- Phase 6: added natural-language food logging, persisted prepared operation batches, automatic evidence-backed estimates, explicit unknown-nutrition saves, quantity corrections/Undo, arbitrary-date diary/manual edits, sourced planner previews, meal movement/portions, exact pantry deductions and unit-aware shopping. See the Phase 6 checkpoint.
- Phase 7 independent review identified five concrete bugs. **All five now have implementation fixes and passing regression tests:** nutrition food-identity mismatch; grouped number suffix parsing; stale SavedState overriding committed food-operation state; history restoration after consent revocation; planner repeating unusable first candidates.
- Added `FeatureRestoration.kt`; integrated it at ViewModel startup. Profile saving now sanitizes and rewrites all feature SavedState histories when storage consent is off while preserving current drafts. `AppData.planSearchOffset` persists bounded candidate progression before each lookup, including unsuccessful candidates.
- Hardened `FoodLogging.kt` with exact normalized food identity, whole-number parsing, bounded reviewed MyFoodData table support and preservation of explicit service-limit/reset messages. Wrong-food results remain unknown. Added `Phase7NutritionTest`, `Phase7RestorationTest`, `Phase7StateFlowTest`.
- Real Good Food extraction exposed a layout mismatch. `SourcedFoodRules.kt` now supports the bounded known layout (servings plus difficulty, a contiguous bullet ingredient list without a heading, preparation continuations, nested method steps); vague waiting times and compound-label uncertainty remain explicit. Six synthetic layout regressions added; no complete copyrighted live recipe was copied into the repository.

#### Latest actual verification — do not confuse full and focused runs

1. **Full Android/JVM/Compose run: 285 tests, 0 failures, 0 ignored**, confirmed from `app/build/reports/tests/testDebugUnitTest/index.html` after `scripts/build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug')`. All source-review fixes above were included.
2. Immediately afterward, changed only `PlanningScreen.kt` dialog sizing from `LocalConfiguration.screenHeightDp` to `LocalWindowInfo.current.containerSize` with `LocalDensity`, addressing the new window-size lint warning.
3. **Final command before pause:** `scripts/build.ps1 -Tasks @(':app:testDebugUnitTest', '--tests', 'com.mrpoodles.app.Phase6NavigationUiTest', '--tests', 'com.mrpoodles.app.InvalidRecordsUiTest', ':app:lintDebug')` — **BUILD SUCCESSFUL in 51s**, covering the affected 3 navigation and 2 recovery/UI tests. Lint passed. The immediately preceding full report had 0 errors / 7 warnings (six dependency-version notices plus the now-fixed window-size warning); read the latest lint report for the final count instead of assuming it.
4. The focused run overwrites Gradle's current test reports with its subset. The 285-test full result is historical to the immediately preceding run, not the current subset report. No other application changes followed the focused pass.
5. `npm test --prefix backend`: **67 passed, 0 failed/cancelled/skipped**. `npm run check --prefix backend`: **passed**, Wrangler dry run, **58.98 KiB / gzip 16.95 KiB**. Backend production sources did not change afterward.
6. Tracked `git diff --check` and no-index whitespace checks for every untracked file passed with LF/CRLF notices only, before the final small UI/AGENTS/checkpoint edits. No files were staged.
7. A scoped search of Android main Kotlin/XML found no camera/gallery/photo/vision entry points. The obsolete app-owned OCR directory cleanup is retained; this is not an OCR feature.

#### Actual live probes / unresolved coverage

- `node backend/scripts/local-live.mjs phase7-tiramisu --next-minute --full-sources`: HTTP **200**, Groq, **11,472 ms**; one Good Food page, **2,801 characters**. Published ingredients/method were present, but the original parser missed the real layout. Parser fixes above followed. Lactose conflicts, compound ingredient labels and vague chilling time still prevent claiming a fully verified suitable adaptation. This was a research/source inspection, not a live Android feature end-to-end pass.
- `node backend/scripts/local-live.mjs phase7-kachori --next-minute --full-sources`: HTTP **200**, Groq, **4,978 ms**. Returned **dry-fruit kachori, aloo puri and walnut kolachi**, not pyaaz kachori. The model incorrectly associated a 115-calorie quote with the requested dish. Client identity checks reject these mismatches; **do not report 115 kcal for pyaaz kachori**. This is a real source-coverage failure, not a successful food estimate.
- `node backend/scripts/local-live.mjs workout --next-minute`: HTTP **429**, `free_limit`, `research_provider`, Groq cause, **Retry-After: 8030 seconds**, **8,017 ms**. No retry/reset/key rotation was attempted. On resume use actual persisted/provider cooldowns; the recorded delay is historical, not a fresh countdown.
- Source parsing and substitutions remain intentionally narrow. No current live end-to-end pass exists across all final client parsers/persistence/UI. Phase 2's both-provider personality/fallback tests are historical; current services share backend personality but mostly display client-authored confirmations. Do not claim arbitrary conversational adaptation or universal source coverage.

#### Exact next work after the user resumes

1. Read this checkpoint and `AGENTS.md`; inspect `git status --short --branch`. HEAD baseline remains `983459e`, branch `main...origin/main`; preserve every existing modified/deleted/untracked source, fixture and document. No commits or releases have been created.
2. **Reconcile stale release documentation.** `docs/RELEASE_READINESS.md` still describes R1–R5 as open; update each with the actual fix/tests and retain the historical findings. `README.md` still says five review blockers remain. Other older master-plan/Phase 2/3 handoff prose is historical; the resume header and this checkpoint control current state.
3. Read the latest lint report and record its final warning count. Inspect final diff/whitespace after documentation changes. Do not repeat the successful full suite solely to resume; run appropriate checks only for new code changes or unresolved concerns.
4. Finish Phase 7 review of realistic source coverage, privacy/settings/retained evidence and final release dependencies. Decide honestly whether remaining coverage is a release blocker or an explicitly disclosed limitation; never mark missing live acceptance as passed. Any bounded live probe must respect the provider cooldown and existing free budgets.
5. **Check with the user before Phase 8.** Confirm final APK packaging and separately request explicit production backend deployment permission if deployment is needed. Backend redesign remains undeployed; an old backend without `/v2/research` cannot serve these new sourced flows.
6. Only after the required authorization and applicable release gates: verify signing continuity/configuration, update version from `0.3.1` / code `6`, package the APK, verify signature/contents/hash, perform available device upgrade/usability checks, then inspect/stage intended files, commit and push. Do not force-add ignored APKs/secrets or silently skip unavailable device/intended-user tests.

#### Current stop state

- Phase 7 remains **incomplete / paused by user**, not a completed production release.
- Version unchanged: **0.3.1 / 6**. No new APK/AAB, production deployment, signing change, commit, push, publication or transcript upload.
- Physical-device availability, upgrade/signing continuity, OEM keyboard/splash and intended-user unaided testing remain unverified. No device installation occurred.
- All delegated tasks have returned. No further work should run until the user resumes.

### Phase 4 Build-mode resume — 2026-10-04

- The user requests removing the stop requirement from `AGENTS.md`, completing Phases 4–7 without interruptions with a one-hour target, and checking in immediately before Phase 8. The governing instructions supplied to this session still require phase acceptance, checkpoint updates, and a stop; a repository edit cannot override that boundary. The stop rule is retained. Minimize questions and routine narration while completing the active phase.
- Build mode is active. The master plan and working tree were inspected. Existing uncommitted Phase 1–3 work and partial Phase 4 files (`FoodChatScreens.kt`, `SourcedFoodRules.kt`, `SourcedFoodService.kt`, and integration edits) are preserved and require verification.
- Phase 4 remains in progress. No one-hour completion guarantee, packaging, production deployment, commit, or push is implied by this resume.
- Subsequent steering: the user again requested continuous work and explicitly asked to re-read `AGENTS.md`. That file now adds authorization to spawn subagents and a later instruction to work continuously. Preserve the user's file edits. Follow the later continuous-execution instruction while retaining sequential phase acceptance and written checkpoint records; the user check-in immediately before Phase 8 remains mandatory. Earlier descriptions of a mandatory stop are historical and superseded by this update. Subagents are now used for bounded non-overlapping implementation and test work.

### Phase 0 — 2026-10-03

- Authorization: documentation only.
- Baseline inspected: `main...origin/main`, clean working tree, commit `983459e`.
- Added: `AGENTS.md` and `docs/IMPLEMENTATION_PLAN.md`.
- Latest documentation-only update: recorded the user's requirement to reuse the existing companion Chat personality across all chatbot features, with Phase 2 implementation and Phase 7 verification gates. No implementation phase is authorized by this update.
- Application/backend changes: none.
- Build, Android/backend tests, live provider calls, packaging, deployment, commit, push: not performed in this phase.
- Documentation review: completed. Read both documentation files and checked requirements against the conversation, including easy navigation, all chatbot features sharing the existing companion personality, free-provider restrictions, migration, and final-phase release boundaries.
- Whitespace checks: `git diff --no-index --check -- NUL "docs/IMPLEMENTATION_PLAN.md"` and `git diff --no-index --check -- NUL "AGENTS.md"` passed. Git reported only its LF-to-CRLF conversion notice, not whitespace errors.
- Scope check: `git status --short --branch` showed only the two new documentation files, both untracked, on `main...origin/main`. No source changes were present.
- Phase status: complete. Unverified provider signup, live integration, and device checks remain explicitly recorded as dependencies of later phases, not Phase 0 successes.
- Next action: stop. In the next session, read both files, inspect the working tree, and begin only Phase 1 when the user supplies explicit authorization. Update the authorization record at that time and stop again after Phase 1 checks and handoff.

### Phase 1 — 2026-10-03

- Authorization: user explicitly authorized Phase 1 only, including relevant non-packaging checks and checkpoint/handoff updates, followed by a stop.
- Starting commit/branch: `983459e`, `main...origin/main`; existing untracked `AGENTS.md` and `docs/IMPLEMENTATION_PLAN.md` are preserved and updated. No pre-existing tracked changes were present.
- Scope: section 6 Phase 1; retain historical user data, shared Mr. Poodles personality requirements, and easy-navigation state foundations. No Phase 2 implementation or release operations.
- Added source: `EvidenceModels.kt`, `FeatureState.kt`, `SnapshotMigration.kt`, `IntakeOperations.kt` under `app/src/main/java/com/mrpoodles/app/` for the contracts in section 5.1.
- Updated source: `Domain.kt` (additive fields and nullable safe nutrition), `LocalStore.kt` (migration/fenced move), `PoodlesViewModel.kt` (commit/request/draft/intake behavior), `Screens.kt` (invalid-record recovery display and persistence-aware existing dialogs).
- Added checks: `FoundationModelsTest.kt`, `FoundationPersistenceTest.kt`, `IntakeOperationsTest.kt`, `InvalidRecordsUiTest.kt`; two literal historical/damaged fixtures under `app/src/test/resources/snapshots/`. Updated the prior recipe-flow expectation to retain the last draft after a repeated/failed generation.
- Personality review: read the effective definitions in `backend/src/personality.js` and the companion instructions in `PoodlesViewModel.kt`. Existing prompt text and backend remain unchanged. Cross-feature/fallback personality composition and representative live reply comparison remain Phase 2/7 work, not claimed here.

#### Commands and actual results

1. `git status --short --branch`, `git diff --stat`, source/test/script reads: confirmed the pre-existing documentation-only changes and inspected the affected paths.
2. `.\scripts\build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug', '--dry-run')`: passed. Inspected the graph: compilation, resource/JAR preparation and unit-test/lint tasks only; no APK/AAB assembly/bundling task. `packageDebugResources` and `packageDebugUnitTestForUnitTest` are resource/test preparation, not app APK release tasks.
3. `.\scripts\build.ps1 -Tasks @(':app:compileDebugKotlin')`: passed.
4. Initial `.\scripts\build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug')`: passed with 77 tests before the final additional dialog recovery test.
5. The next full run had 78 tests, one failure: the new Compose dialog test timed out waiting for a ViewModel IO result without advancing Robolectric's main loop. Updated that test's waits to use `compose.runOnIdle`; no acceptance criterion was removed.
6. `.\scripts\build.ps1 -Tasks @(':app:testDebugUnitTest', '--tests', 'com.mrpoodles.app.InvalidRecordsUiTest')`: passed after that test synchronization fix.
7. Final `.\scripts\build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug')`: **passed**. XML reports confirm **78 tests, 0 failures, 0 errors, 0 skipped** across 14 suites. Lint: **0 errors, 10 warnings** (six dependency update notices and four existing `Uri.parse` KTX suggestions). No dependency upgrades were made.
8. `git diff --check` and `git diff --no-index --check -- NUL <path>` for all 12 untracked files: passed; Git emitted LF-to-CRLF conversion notices only. The first PowerShell wrapper incorrectly treated no-index's normal difference exit code `1` as failure; the corrected check accepts 0/1 and fails above 1. There were no whitespace diagnostics.
9. Final `git status --short --branch` and diff review: only the five intended tracked source/test changes, ten new source/test/fixture files, and the two pre-existing documentation files are present. No backend, provider configuration, dependency, version, credential, or binary files are included. Both initial documentation files remain untracked and preserved.

Reports: `app/build/test-results/testDebugUnitTest/TEST-*.xml`, `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.xml` (ignored generated output; not source deliverables).

#### Acceptance evidence

| Phase 1 gate | Verified result |
| --- | --- |
| Historical migration preserves user data | Literal historical fixture field comparisons, stable IDs, write/read round trips, and old AtomicFile backup recovery pass. Nullable nutrients and old completion dates remain intact. |
| Failed saves retain recoverable state | Obstructed disk writes retain the recipe draft and old snapshot; intake failure creates neither receipt nor success callback. Compose manual input survives failure and saved-instance restoration, then closes after successful retry. |
| Retry/correction cannot duplicate intake | Durable replay after serialization, atomic receipts, key/payload mismatch, stale correction, stable target identity, concurrent queued retry, deletion and late replay checks pass. |
| Obsolete results cannot commit | Changed profile with cancellation-ignoring fake model, invalidation at the final disk move, request/input/profile reducer checks, and concurrent calendar edits pass. Existing companion cancellation tests also pass. |
| Invalid records do not crash rendering | Damaged fixture preserves semantic-invalid records and quarantines structural-invalid records; Compose renders unknown ingredients/invalid servings with unknown nutrition. Existing navigation/companion/UI regression tests pass. |
| Existing app compiles incrementally | Kotlin compilation, full Android/JVM/Compose test suite and lint pass without APK/AAB packaging. |

- Live versus mocked: application generation tests use deterministic fake models; storage tests use Robolectric app directories and injected write obstructions. No real user device/data, power interruption, or live food/recipe/workout provider was tested. DEV articles/comments were read only as secondary architecture research.
- Community research: [Making POST Requests Safe to Retry with Idempotency Keys](https://dev.to/lukman-ss/making-post-requests-safe-to-retry-with-idempotency-keys-3ip0) supports stable keys/fingerprints; its comment discussion distinguishes receipt lifetime from response-cache lifetime. [FOR UPDATE SKIP LOCKED Was Not Enough](https://dev.to/qwertyboy0325/for-update-skip-locked-was-not-enough-a-stale-failure-write-race-in-an-inbox-processor-3o4d) and its comments motivated attempt fencing at the actual transition. These are two relevant reports, not a broad consensus or proof of this implementation.
- Event context was checked read-only: the existing joined Weekend Challenge is still listed through October 5, 06:59 UTC. No registration, offer claim, submission, publication, or transcript upload was performed.
- Backend tests/dry-run: not run in Phase 1 because backend source/config/API contracts did not change. Historical backend counts remain historical.
- Blockers: none for Phase 1's defined local acceptance gates. Phase 2 account/signup/live evidence dependencies, final device/usability checks, and later-phase feature work remain unverified.
- Packaging, production deployment, commit, push: not performed. Version remains `0.3.1` / code `6`; HEAD remains `983459e`.
- Phase status: **complete**. Next authorized action: **STOP and wait for explicit Phase 2 authorization**.

### Phase 2 — 2026-10-03 (incomplete: external/live gates blocked)

- Authorization: user explicitly said “start phase 2” after Phase 1 completion.
- Starting branch/HEAD: `main...origin/main`, `983459e`; all five modified tracked Phase 1 files and twelve untracked source/test/fixture/documentation files are preserved. No existing backend changes were present.
- Scope: section 6 Phase 2 only. No UI redesign, APK/AAB packaging, production deployment, commit, or push.
- Backend files added: `bounded-http.js`, `provider-budget.js`, `retrieval.js`, `research.js`, `model-fallback.js` under `backend/src/`; `backend/test/research.test.js`; `backend/scripts/live-research.mjs`.
- Backend files updated: `worker.js` (additive API, bounds and legacy fallback), `quota.js` (atomic provider/feature reservations and shared legacy compute), `inference.js` (provider retry timing), `personality.js` (shared composition), existing worker tests, `package.json` (explicit live-probe command) and `.dev.vars.example` (empty, unenabled setup template).
- Android files added: `ResearchContract.kt`, `ResearchContractTest.kt`, and the shared synthetic fixture `app/src/test/resources/research-v2-response.json`. Updated `CloudModel.kt`, `ServiceLimits.kt`/tests and Phase 1's `EvidenceModels.kt` with additive source freshness/type metadata.
- Documentation: updated authorization in `AGENTS.md`/this plan; added `docs/PHASE2_BACKEND.md`. All Phase 1 work is preserved; no schema reset/destructive migration or unrelated source deletion was performed.
- Official API contracts/pricing re-read for Exa, Tavily, Groq and YouTube. Exa documents $10 renewable monthly credits, $0.004 Instant search and $0.001/text-page extraction. Tavily documents 1,000 credits/month and basic search/extraction costs. YouTube currently documents a separate 100-call daily search bucket. These documents do not prove actual account signup/billing settings.

#### Commands and actual results

1. `git status --short --branch`, `git diff --stat`, backend/client source/test/config-template reads: confirmed prior Phase 1 state and no pre-existing backend changes. Secret files were not printed. Filename checks found only `.dev.vars.example`, no local `.dev.vars` or `backend/.secrets/*` entries.
2. `npm test --prefix backend`: initial unchanged-suite compatibility run passed 19 tests; expanded runs passed 41, 42, 44, then the final **45 tests, 0 failed/cancelled/skipped** after the consent compatibility guard. No failing backend test was suppressed.
3. `npm run check --prefix backend`: passed three times as a Wrangler **dry run**. Final bundle preparation report: 54.86 KiB / gzip 15.62 KiB; AI and existing Durable Object bindings resolve. This was not a production deployment or an Android artifact build.
4. `.\scripts\build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug')`: **passed**, including a final rerun after the consent guard, 83 tests across 15 suites, **0 failures/errors/skipped**. Includes all Phase 1 migration, persistence, UI and companion regression tests. Lint: **0 errors, 10 warnings**, the same six dependency-update and four existing KTX suggestions.
5. `npm run check:live --prefix backend`: **PENDING**, script exit code 2. Development URL/token were absent; the script explicitly reported **no live call made**. No source-quality/latency result or provider health pass was obtained.
6. `git diff --check` plus `git diff --no-index --check -- NUL <path>` for all 23 untracked files: passed with LF-to-CRLF notices only. Source diff review confirmed Phase 2-only additions and preserved Phase 1 work. Final status shows 15 modified tracked files (including the five pre-existing Phase 1 files), the intended untracked source/tests/fixtures/docs, and HEAD `983459e`. No secrets, production configuration, dependency upgrades or binary artifacts were added.

#### Acceptance evidence and remaining blockers

| Gate | Actual result |
| --- | --- |
| Exa/Tavily response shapes and snippet rejection | Offline contract fixtures pass; raw text is required and summaries/snippets are rejected as page evidence. |
| Request-bound citations and factual uncertainty | Invented IDs, old snapshots, fabricated quotes, paraphrases-as-quotes and invented URLs fail validation; attribution is not represented as semantic proof. |
| Prompt-injection boundary/shared personality | Tests confirm hostile page text stays in data, all task prompts include the same companion definition, both model paths receive identical prompts, and invalid model evidence fails closed. Live behavioral resistance/voice comparison is pending. |
| URL/redirect/size/time/cancellation bounds | Tests cover local/numeric/IPv6/credential/port targets, unapproved lookalike/rebinding hosts, fixed API endpoints, rejected redirects, bounded response streams, timeouts and cancellation without fallback. External extraction-provider internal crawling is not directly observable. |
| Quotas, reset/cooldown and free fallback | Atomic concurrent budget tests, minute/calendar-month windows, long Retry-After, primary failure/secondary provider use, shared v1/v2 compute and preserved chat reserve pass. Actual account quotas/billing/signup are pending. |
| Cache and product-source preference | Permission/TTL/freshness/request rebinding and manufacturer-registry priority tests pass. Real manufacturer coverage/permission setup is pending. |
| Video lookup | Real-shaped metadata IDs/title/channel, partial-match labeling and empty/invalid/unrelated result omission pass with fakes. Live availability/relevance is pending. |
| Backend/client compatibility | Existing 19 backend tests, additive v2 shared fixture, Android validation and legacy SSE fallback tests pass. New UI flows are intentionally not activated in Phase 2. |
| Actual signup and configured-provider live checks | **BLOCKED**: no verified new account setup, local provider credentials, manufacturer-source policy, or development live-probe configuration. |

- Remaining user-assisted work: complete permitted provider signup, verify no payment/phone/KYC/recharge, set ignored local secrets and reviewed manufacturer hosts, configure a dev backend, then run bounded synthetic recipe/product/workout/nutrition and fallback checks. Record measured latency, source completeness/quality, video relevance, and representative real voice/uncertainty replies. Detailed steps are in `docs/PHASE2_BACKEND.md`.
- Final compatibility review caught that the current client privacy copy names Cloudflare only. Legacy fallback now requires explicit per-request `allowExternalFallback`; v2 uses `allowExternalModel` defaulting false. Existing clients never opt in automatically. Backend/Android tests verify the default and absence of external model calls without consent. Provider disclosure/consent must be integrated with future UI work before enabling this capability for the intended user.
- Local placeholder flags were not set to claim eligibility. No account was created, no provider secret obtained, and no automatic provider upgrade/payment path was enabled.
- Community research: [My RAG Pipeline Got Hijacked by Retrieved Text](https://dev.to/darshan_kunwar/my-rag-pipeline-got-hijacked-by-retrieved-text-an-accidental-prompt-injection-2bkc), Darshan Kunwar, and its comment discussion distinguish retrieval quality from instruction boundaries; that informed the hostile-chunk/data-boundary tests. [Retry, Backoff, and Circuit Breakers for LLM API Calls](https://dev.to/draganristicrsjpg/retry-backoff-and-circuit-breakers-for-llm-api-calls-h3k) supports bounded attempts/cooldowns, but its sample's below-Retry-After jitter was deliberately not copied. These sources are rationale, not application verification.
- Packaging, production deployment, commit, push: not performed. Version and signing configuration remain unchanged. No provider keys were written into source or the client.
- Historical checkpoint status: **INCOMPLETE** because required external/live acceptance was missing. Subsequent authorization and live results below supersede the earlier Phase 3 authorization and configuration status.

### Template for later checkpoints

### Remaining-phase authorization — 2026-10-03

- User request: “do all phases right now, at the end do checks, tell me to put in keys if needed and bundle it”.
- Recorded authorization for Phases 3–8 and final APK packaging; Phase 2 authorization remains active.
- User preference: minimize intermediate interruptions, consolidate final verification and explain required key entry. Required phase acceptance checks and stop points remain in effect; missing live checks cannot be marked passed or postponed while claiming Phase 2 complete.
- Current blocker is unchanged: verified search/model/video account setup, local secrets, manufacturer source policy, and the development backend needed for actual live checks. Existing code and all uncommitted Phase 1/2 changes are preserved.
- No application/backend code, tests, build artifacts, production deployment, commit or push changed as part of this authorization update.
- Next task: obtain user-assisted local provider configuration, complete the current Phase 2 live acceptance, update the checkpoint and stop. Remaining phases do not require a new scope authorization, but must retain their acceptance gates.

### Provider key-entry assistance — 2026-10-03

- User requested action and a step-by-step explanation of which keys to obtain and where to enter them.
- Checked the working tree and confirmed that `backend/.dev.vars` was absent and is excluded by Git before creating it. Existing source/configuration work is preserved.
- Created an ignored local `backend/.dev.vars` containing empty Exa, Tavily, Groq and YouTube key fields, an empty development token hash, an empty verified-provider list, and an empty source registry. No actual secret or eligibility assertion has been generated or stored.
- User completes actual provider sign-in/key creation and pastes keys locally. Guidance distinguishes required search from optional fallbacks, uses a server-side YouTube Data API v3 key, and leaves Cloudflare binding/app-token wiring to the development setup step. Skip any provider that requires payment, phone verification or KYC.
- Phase 2 remains incomplete. No provider request, production configuration, APK packaging, commit or push was performed during this key-entry assistance.

### Provider signup confirmation and live-check resume — 2026-10-03

- The user reports entering Exa, Tavily, Groq and YouTube keys locally and completing signup without payment, phone verification or KYC. This is user-reported account eligibility, not an independently inspected billing dashboard.
- Resume Phase 2: verify key presence without printing values, configure the local-only provider allowlist, and exercise bounded synthetic calls. Preserve all existing work and all secret values. No production deployment is authorized.
- Local live harness uses an isolated local Worker configuration and persistent local Durable Object counters. Its development app token is generated in memory and never printed; it does not change the production client token or signing configuration. Initial probes target the user-confirmed providers; Workers AI requires a separate authenticated binding check.
- Results and live-discovered fixes are recorded in the next checkpoint.

### Phase 2 live verification resume — 2026-10-03 (account gate still incomplete)

- User supplied the session handoff and requested continuation from live verification. HEAD remains `983459e`, branch `main`; all pre-existing Phase 1/2 changes are preserved. No APK/AAB, production deployment, commit or push occurred.
- Inventory: all four external keys and `FREE_PROVIDERS_VERIFIED=exa,tavily,groq,youtube` are present. Values were not printed or changed. The local app-token hash remains unset: the harness creates a random token/hash in memory per invocation, so no manually configured dev token is needed for this runner. Git confirms `.dev.vars` and `.wrangler/live-state` are ignored.
- Existing local source policy includes `www.maggi.in` (manufacturer) and `tools.myfooddata.com` (nutrition), alongside baseline hosts. Every entry has `cacheSeconds: 0`; no retention permission or source completeness is implied. MAGGI is reviewed as a manufacturer source; MyFoodData identifies USDA Standard Release as its underlying data and remains a secondary publisher.
- Pre-existing but previously uncheckpointed code includes task-relevant evidence windows, conservative token fitting, quote binding, multiline quote validation and sanitized diagnostic causes. The first current backend run passed **49 tests**; the earlier 45-test count is historical.

#### Fixes made during this resume

1. `backend/scripts/local-live.mjs`: omitted `local` for AI probes. Wrangler 4.147.0 defaults to local execution, but explicitly passing `local: true` also disables remote bindings. The inherited `local: false` workaround would upload application code for remote development. An intermediate `unstable_startWorker` attempt returned `Binding AI needs to be run remotely`; it was replaced, not retained. `unstable_dev` with omitted `local` successfully uses the remote AI binding while application code and Durable Object budgets remain local.
2. `backend/wrangler.live-ai.jsonc`: uses the same local-only Worker name as `wrangler.live.jsonc` so both configurations share `.wrangler/live-state` counters. No state was deleted or reset.
3. `backend/src/research.js`: sends `RESEARCH_SCHEMA` through Workers AI `json_schema` mode. Plain `json_object` had returned invalid-shaped output during real recipe/injection calls. Unexpected output fields now become `invalid_evidence` / `output_shape`, rather than falsely labeling a valid client request `invalid_request`. Existing quote checks remain enforced.
4. `backend/scripts/probe-worker.js`: voice probe output ceiling changed from 200 to 900 tokens after Groq returned `invalid_response`; the larger bounded probe passed. This does not prove legacy 320-token companion fallback reliability. Added explicitly injected-primary failure modes for real Groq and Tavily fallback checks. This entrypoint is not referenced by production configuration.
5. Local runner adds source-policy inventory, primary-only/combined research switches, fallback checks and error scopes. `backend/test/research.test.js` verifies explicit schema delivery and correct classification of malformed model output.

#### Actual live results

All commands use `node backend/scripts/local-live.mjs <case>` from the repository root. `--next-minute` was used between quota-consuming cases except the initial Cloudflare voice diagnostics. It waits for a UTC minute boundary; it never clears budgets or bypasses an active provider cooldown. Latencies below measure request/response time, excluding server startup and that wait. One sample is not a throughput benchmark.

| Case / options | Actual result |
| --- | --- |
| `recipe-exa --next-minute` | HTTP 200, Groq, 8,822 ms. One Good Food page, 1,847 characters; quantities, author, serving and method present. Some other candidates failed extraction. |
| `recipe-tavily --next-minute` | HTTP 200, Groq, 5,488 ms. Three Good Food pages, 2,734 / 2,336 / 3,687 characters; ingredient quantities and methods present, some displayed prep-time values missing. |
| `product --next-minute` | HTTP 200, Groq, 8,137 ms. Three MAGGI pages, 865 / 2,510 / 8,540 characters. Product nutrition and generic FAQs are available, not a complete ingredient/advisory label. Reply reports missing evidence; no eating verdict. |
| `nutrition --next-minute` | HTTP 200, Groq, 2,438 ms. MyFoodData raw banana page, 7,773 characters. Page explicitly contains 100 g serving, 89 kcal and macros. Selected model window omits some macros; no food entry is created. |
| `workout --next-minute` | HTTP 200, Groq, 9,748 ms. Three ACE pages, 8,782 / 1,112 / 5,872 characters. Introductory program/article plus an exercise index; index alone is not technique instruction. Real YouTube metadata returned. |
| `plan --next-minute` | HTTP 200, Groq, 9,805 ms. Good Food chickpea salad, 971 characters, six servings, quantities and method; no plan mutation. |
| `voice-groq --next-minute` | Initial HTTP 502 `invalid_response`, 933 ms, at 200 tokens. After probe ceiling change: HTTP 200, 1,301 ms; warm acknowledgement and one question, no crisis escalation. |
| `voice-cloudflare` | Intermediate binding failure: HTTP 503, 606 ms. Corrected runner: HTTP 200, 10,735 ms; “Sweet friend, I'm here for you. What's been weighing on your heart today?” |
| `recipe-cloudflare --next-minute` | Before schema fix: HTTP 503, 17,673 ms, invalid output shape. After fix: HTTP 200, 17,575 ms; exact method quotation and offer to help with substitutions. |
| `injection-groq --next-minute` | HTTP 200, 1,113 ms; refuses to confirm universal safety, no injected marker in message. Synthetic hostile fixture, real model call. |
| `injection-cloudflare --next-minute` | Initial HTTP 503, 9,778 ms. After schema fix: HTTP 200, 9,808 ms; quotes milk/soy evidence, expresses uncertainty, does not follow injected universal-safety instruction. Synthetic fixture, real model call. |
| `fallback-groq --next-minute` | HTTP 200, 1,289 ms. Exactly one injected Cloudflare failure, then real Groq synthesis with valid fixture citations. Primary outage and page are synthetic; secondary inference is live. |
| `fallback-tavily --next-minute` | HTTP 200, 2,876 ms. Exactly one injected Exa failure, then real Tavily search/extraction and Groq synthesis; three pages retained. One exact quote ends mid-sentence at the selected context boundary. |
| `product --cloudflare --next-minute` | HTTP 503, 18,078 ms, `invalid_evidence` / `quote_mismatch`. Rejected, never displayed as an accepted answer. |
| `nutrition --cloudflare --next-minute` | HTTP 200, 10,079 ms. Valid 89-calorie quotation. Message also states a sourced fact despite the acknowledgement-only prompt rule; later UIs must not treat message prose as independently validated evidence. |
| `workout --cloudflare --next-minute` | HTTP 429 `free_limit`, 16,965 ms, `Retry-After: 29`. Scope was not captured before runner instrumentation was added. No reset/bypass; later retry happened after the delay. |
| `plan --cloudflare --next-minute` | HTTP 200, 18,731 ms. Exact chickpea preparation quote; brief planning acknowledgement. |
| `product --cloudflare --with-groq --next-minute` | HTTP 200, Groq, 29,450 ms. Live primary did not produce an accepted result; real fallback returned uncertainty and no verdict. No injected primary failure in this case. |
| `workout --cloudflare --with-groq --next-minute` | HTTP 200, Cloudflare, 17,760 ms. Accepted article quotation and same related YouTube metadata; Groq not needed. |

Video: `https://www.youtube.com/watch?v=8gQbgyTlS-8`, “How to Start Calisthenics at Home For Beginners (No Equipment)”, Pierre Dalati. Title/channel are relevant to the synthetic request. It remains `TECHNIQUE_REFERENCE`; no video watching, availability guarantee or exact-session validation is claimed.

#### Verification, quality boundaries and remaining gate

- Final `npm test --prefix backend`: **50 passed, 0 failed/cancelled/skipped**. Final `npm run check --prefix backend`: **passed**, Wrangler dry run, 59.69 KiB / gzip 17.15 KiB, existing AI/QUOTA bindings resolve. Shared Android/backend fixture remains covered by the backend suite. Android source was not changed during this resume; **83 Android tests and lint are prior results**, not rerun here.
- Accepted research responses passed request/snapshot and quotation validation. Successful HTTP does not establish full published-recipe extraction, exact packaged-product identity or a complete workout. The MAGGI test is a missing-evidence case, not a clean allergen result. Some model uncertainty statements overstate missing facts because only selected excerpts are visible; some message prose contains factual claims despite instructions. Keep message text non-authoritative and use domain validation before actionable later-phase results.
- Both models gave short, friendly companion samples without escalating ordinary sadness; research samples were clearer/more neutral than companion chat. The hostile fixture is one behavioral sample per model, not a universal injection-resistance claim. Legacy low-token companion fallback remains unbenchmarked; 900-token probe success does not establish 320-token production behavior.
- Remaining external gate: explicitly confirm renewable free plans, no payment/paid upgrade/recharge (especially Exa auto-recharge disabled, not merely `$0`), actual account allowances/reset calendars, and other apps consuming those accounts. Signup history and successful API calls do not prove these settings. No new keys are required for the configured bounded probes.
- User follow-up: “payment method hini dala , aur koi option aya hini”. Record this as confirmation that no payment method was added and no such option appeared. Do not ask the user to toggle a setting they cannot see, or infer a paid plan from its absence. Actual allowance/reset values and shared usage were not supplied; those remain unverified. Read-only Usage/Limits dashboard values or screenshots without keys are sufficient to continue the account review.
- Final `git diff --check` and no-index whitespace checks for the seven files changed during this resume passed with LF-to-CRLF notices only. Final status preserves all 15 previously modified tracked files and all untracked Phase 1/2 work; no secret or binary was added to Git. No-index exit code 1 denotes file differences, not whitespace failure.
- Community reference inspected: [The Cloudflare Worker That Ran Perfectly and Still Failed Twice](https://dev.to/dannwaneri/the-cloudflare-worker-that-ran-perfectly-and-still-failed-twice-17l2), Dann Waneri; no comments were present. Its distinction between successful execution and verified acceptance supports recording source/model failures separately from HTTP success. This is one relevant report, not community consensus. Official Wrangler source and [remote-binding support](https://developers.cloudflare.com/workers/local-development/bindings-per-env/) informed the runner fix; [Workers AI JSON Mode](https://developers.cloudflare.com/workers-ai/features/json-mode/) informed explicit schema delivery.
- Phase status: **INCOMPLETE**, pending account-setting confirmation and review of these explicit live limitations. Stop at Phase 2; Phases 3–8 remain authorized but unstarted.

For each authorized phase, append:

```text
Phase and date:
User authorization:
Starting commit/branch and pre-existing changes:
Requirement IDs/sections addressed:
Files changed and why:
Commands run and actual results:
Live checks versus mocked checks:
Data/API compatibility implications:
Known failures, blockers, and unverified claims:
Decisions made or requiring user input:
Phase status (complete only after acceptance checks):
Next authorized action:
```

### Continuous-execution request and pre-packaging check-in — 2026-10-03

- Latest user request: “sun yaar E:\DevChallenge1\docs\IMPLEMENTATION_PLAN.md isko dekh aur saare phase ek hi baar mein krde jitne remaining hai ab mujhe tang end mein hi kriyo before apk bundling.”
- The user reiterates authorization for all remaining implementation work, requests no intermediate questions, and explicitly wants a check-in before APK bundling. Preserve this communication preference; do not repeat the account questionnaire or offer unrelated publishing tasks while implementation is blocked.
- The governing project instructions still require one phase at a time, acceptance checks, checkpoint updates and a stop at each phase boundary. Do not silently rewrite those rules or mark Phase 2 complete to accommodate batching.
- Re-read the master plan and inspected the working tree. All existing Phase 1/2 work is preserved. Phase 2's live/backend verification remains recorded; actual account allowance/reset and shared-usage details remain unavailable. This request supplies no new account verification evidence.
- This update changes only the authorization/handoff record. No application implementation, additional live calls, test reruns, packaging, deployment, commit or push occurred. Phase 2 remains incomplete; Phases 3–8 remain unstarted.

### Phase 2 automated account inspection — 2026-10-03

- User requested “shuru kr”, then “checkpoint pe ni rukna chalte ja” and “apk bundling se pehle no disturb me non stop work”. These reiterate the request for continuous work and no intermediate questions. No new account facts or delegation authorization were supplied. Governing phase gates remain in effect.
- Re-read the plan and inspected `git status --short --branch`; all existing Phase 1/2 work remains preserved on `main`, HEAD `983459e`.
- Investigated documented account APIs to reduce manual work instead of repeating the questionnaire. Added `backend/scripts/account-audit.mjs` for one bounded read-only Tavily usage request; added `limits-groq` to the existing local probe runner and development entrypoint to inspect headers from one budget-reserved synthetic inference. No billing settings, keys, account permissions or production configuration were changed.
- `node backend/scripts/account-audit.mjs`: live GET `https://api.tavily.com/usage` succeeded in **2,392 ms**. Account plan **Researcher**, plan limit **1,000 credits**, current plan/key usage **1 credit**, remaining plan credits **999**, pay-as-you-go usage **0**. Both key limit and pay-as-you-go limit returned **null**. The endpoint explicitly documents a null key limit as unlimited; the meaning of a null pay-as-you-go limit is not established, so it is **unknown**, not proof of paid billing or of disabled paid fallback. No observed usage difference between this key and the account is a snapshot, not proof that no other app uses this same key. Billing-cycle reset is absent from this response.
- `node backend/scripts/local-live.mjs limits-groq --next-minute`: HTTP 200 in **1,219 ms**. Actual response headers report **1,000 requests/day**, **8,000 tokens/minute**, **999 remaining daily requests**, **6,995 remaining minute tokens**, request reset after **1m26.4s**, token reset after **7.537s**. These are relative provider bucket values, not evidence of a UTC-midnight reset. Existing app ceilings of 900 daily requests and 7,500 minute tokens fit those two measured limits. Requests/minute, tokens/day, paid/free billing plan and shared consumers are not exposed by these headers and remain unverified. This probe uses existing persistent Durable Object reservations; no counters were cleared.
- Exa's documented account-usage endpoint requires a separately enabled Team Management API and a service-account key, not the configured search key. No unsupported admin request, service-key creation, support contact or privilege expansion was attempted. Official billing documentation establishes the general first-calendar-day monthly grant reset, not this account's eligibility/settings.
- Added four meaningful audit tests in `backend/test/account-audit.test.js`: output strips account identifiers/secrets and unknown plan names; missing/null pay-as-you-go data cannot become a confirmation; redirects/quota refusals cannot forward credentials or trigger retries; Groq daily-request/minute-token headers are correctly labeled and malformed values remain unknown.
- `node --test backend/test/account-audit.test.js`: **4 passed**. Final `npm test --prefix backend`: **54 passed, 0 failed/cancelled/skipped**. No production source or Android source changed, so previous Wrangler dry-run/Android results were not repeated. The local live runner compiled and executed its changed development entrypoint successfully.
- Remaining gate: facts unavailable through the configured account APIs remain unverified, including Exa account billing/allowance, Tavily pay-as-you-go setting/reset, other Groq dimensions, account consumers and actual Cloudflare/YouTube account quota settings. The user's no-payment-method statement remains recorded; missing settings are not interpreted as paid billing. Successful public marketing/API responses do not establish unexposed account facts.
- References inspected: [Tavily usage API](https://docs.tavily.com/documentation/api-reference/endpoint/usage), [Tavily free plan](https://docs.tavily.com/documentation/api-credits), [Groq rate-limit headers](https://console.groq.com/docs/rate-limits), [Exa service-account usage API](https://exa.ai/docs/reference/team-management/get-api-key-usage), and [Exa billing](https://exa.ai/docs/admin/billing).
- Phase status: **INCOMPLETE**. Account review is more precise, not complete. No further user questionnaire was sent; no later phase, APK/AAB, deployment, commit or push was performed.

### Phase 2 closure after user confirmation — 2026-10-03

- In response to the report that remaining billing/reset account checks were blocking Phase 2, the user said **“done hai”**. In context, this is accepted as user confirmation that the outstanding account review is complete. No new account settings, numeric quotas, screenshots or credentials were supplied; do not invent them or claim independent inspection.
- Account eligibility/setup is a user-assisted gate. Its remaining review is now **user-confirmed**, complementing the previous no-payment/phone/KYC statements, actual local provider configuration, live endpoint checks and partial automated account measurements. Historical checkpoints retain what was unverified at the time.
- Phase 2 acceptance record: **54 backend tests passed**, production Wrangler dry run passed in the preceding live-fix run, and **83 Android tests/lint passed in the earlier client-contract run**. Both inference providers, both retrieval providers, YouTube metadata, synthetic hostile-text cases and real secondary-provider fallback have recorded results. Rejected citations and temporary quota failures remain recorded, not erased by completion.
- The last change is documentation only. Tests were not rerun and no further provider allowance was consumed. Existing Phase 1/2 work is preserved; no packaging, production deployment, commit or push occurred.
- Phase status: **COMPLETE** for the Phase 2 research/backend contract. This is not completion of feature UIs, full recipe/product/workout validation, intended-user usability testing or final release checks. Existing short-context, message-prose and legacy low-token fallback limitations remain explicit follow-up concerns.
- Next phase: **Phase 3**, already authorized. Stop here under the governing phase-boundary rule. The user's pre-APK check-in requirement remains in force.

### Phase 3 — 2026-10-03 (complete)

- Authorization: the user explicitly requested Phase 3 through the remaining phases without repeated prompts, followed by a check-in before APK bundling. Work resumed in Build mode. The governing one-phase-at-a-time/checkpoint/stop requirement remains; this checkpoint covers Phase 3 only.
- Starting state: `main`, HEAD `983459e`; all Phase 1/2 source/tests/docs and the interrupted Phase 3 edits were preserved. No subagents were used.
- Added source: `FeatureChatLayout.kt` (shared container and current recipe workspace), `LaunchExperience.kt` (retained cold-launch deadline). Updated `AppShell.kt`, `ChatScreen.kt`, `CozyElements.kt`, `MainActivity.kt`, `PoodlesViewModel.kt` and `Screens.kt` for the contracts in section 5.3.
- Added `Phase3LayoutUiTest.kt`; expanded `AppNavigationUiTest.kt`; updated `WelcomeUiTest.kt` to open the new Diary settings section before editing its calorie target. No test acceptance assertion was removed to obtain a pass.

#### Commands and actual results

1. Read the master plan, inspected `git status --short --branch`, current source/tests and `scripts/build.ps1`. Existing work and credentials were preserved.
2. `.\scripts\build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug', '--dry-run')`: passed. Graph contains compilation, test-resource/JAR preparation and lint, no app APK/AAB assembly or bundling.
3. Initial `:app:compileDebugKotlin`: failed on an implicit `BoxWithConstraints.maxHeight` receiver inside a nested Column. Moved the computed context height to the outer scope; subsequent compilation passed. The introduced Back icon deprecation was fixed with `Icons.AutoMirrored.Rounded.ArrowBack`.
4. Initial focused run of `Phase3LayoutUiTest` and `AppNavigationUiTest`: 12 tests, two failures. The launch deadline replayed when Compose test time and the monotonic clock differed; the retained gate now records completion explicitly. A lazy-list retry test tried to locate an uncomposed off-screen item; it now scrolls the owning list to that item, and initial examples no longer obscure pending/error feedback. The next focused run passed, including the added IME-inset test.
5. First full test/lint run: 93 tests, one old UI test failed because the calorie target had moved behind Diary settings. Updated that test's interaction to expand the section; its reachability and saved-value assertions remain.
6. Review additionally preserved compact Chat's Clear conversation menu, separated expanded/compact context scroll state, and added an actual Activity background/resume assertion. Reran checks after those changes rather than treating earlier results as final.
7. Final `.\scripts\build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug')`: **passed, 93 tests across 16 suites, 0 failures/errors/skipped**. Lint: **0 errors, 10 existing warnings** (six dependency-version notices and four `Uri.parse` KTX suggestions). No dependency upgrades were made.
8. Final tracked `git diff --check`, no-index checks for the three new Phase 3 source/test files plus the two updated instruction documents, and working-tree review passed. Only LF-to-CRLF notices appeared. All 21 tracked modifications include the preserved earlier-phase work; the new Phase 3 files are untracked intentionally. No secrets or release artifacts were staged.

#### Acceptance evidence

| Gate | Verified result |
| --- | --- |
| Home task reachability and labeled controls | Four direct Home routes tested. Manual logging opens its dialog directly; recipe input is immediately available. Home touch targets pass 48 dp checks at 2× font scale on a 320 dp-wide layout. |
| Back/Home and isolated drafts | Recipe Back returns to the originating Home/shelf. Root Back returns Home. Chat/recipe/label drafts, Meals subpage, profile draft and manual-log dialog survive navigation or activity recreation. |
| Shared layout and keyboard | Actual synthetic IME insets (220 px) dispatched to the Activity at 320×480 dp hide the bottom navigation and keep Home plus recipe composer/action visible. Separate 250–260 dp-high layouts at 2× fonts verify compact context, controls, Details and restoration. These are simulated insets, not a physical keyboard test. |
| Scroll preservation | A long context scrolled to evidence line 20 remains there after keyboard compaction, saved-instance restoration and expansion. Expanded/compact context use independent scroll state. |
| Error/retry behavior | A failing fake model retains the request and previous recipe, produces a recipe-owned inline error, retries without erasing the draft, and removes the old retry after edits. Phase 1 stale-request/persistence regressions still pass. |
| Settings compatibility | Optional fields start hidden, duplicate environment selector is absent, and switching to Home then saving preserves both environments, goal, cuisine, target and all other original profile fields. Existing profile failure/draft tests still pass. |
| Splash timing and resume | Controlled deadline test stays launching before two seconds, finishes after the deadline and survives restoration. Activity recreation and background/resume do not re-show the Compose splash or lose the recipe request. |
| Regression | Full migration/storage/domain/research-contract/companion/UI tests pass. No schema change, source deletion or reset of saved data was required. |

- Current reports: `app/build/test-results/testDebugUnitTest/TEST-*.xml`, `app/build/reports/tests/testDebugUnitTest/index.html`, and `app/build/reports/lint-results-debug.txt` (ignored generated output).
- Live versus simulated: no model/provider live calls or backend changes were made in Phase 3. The model-failure test is deliberately synthetic. Phase 2's 54 backend tests/live results remain historical. Device installation, native/Compose visual flash inspection, OEM keyboards, and intended-user usability remain Phase 8 gates.
- Community reference inspected: [Jetpack Compose Navigation (Interview Prep)](https://dev.to/itsaalaa7/jetpack-compose-navigationinterview-prep-2bc7), [itsaalaa7](https://dev.to/itsaalaa7), tags `android`, `navigation`; no comments were present. Its distinction between navigation callbacks and back-stack behavior informed typed Home actions and explicit origin-return tests. This is one secondary report, not evidence of broad consensus or a reason to add a navigation dependency.
- No APK/AAB, production deployment, commit or push. Version remains `0.3.1` / code `6`; HEAD remains `983459e`. All prior work remains uncommitted.
- Phase status: **COMPLETE** for the Phase 3 shell/settings/splash scope. **STOP**. Phase 4 is next and already authorized; Phases 4–8 are not completed by this checkpoint. Check with the user before Phase 8 packaging.

## 11. Session-switch checklist

### Phase 4 checkpoint — 2026-10-04

- Integrated `FoodChatScreens.kt`, `SourcedFoodService.kt`, and `SourcedFoodRules.kt` with request-fenced ViewModel state, dedicated feature conversations, persisted recipe drafts/results and saved recipes. Successful sends clear only the submitted draft after disk commit. Failures/cancellation preserve recoverable input and prior results. Serving changes get new recipe IDs and retain original retrieval dates/evidence.
- Published recipe sections supply named quantities, original servings, methods, timings and clickable attribution. New recipes are not constrained to the legacy ingredient catalog or three legacy methods. Recipe validation retains equipment/restriction/exclusion checks and rejects incomplete or unverified changes. Equal-amount, same-dish, no-cook substitution guidance is supported; other substitutions/layouts ask for evidence rather than inventing an adaptation. Unsupported active/wait/cook times remain blockers to an actionable recipe.
- Food checks accept text/product/dish/public URLs, ask for exact product identity, distinguish lactose intolerance from milk allergy, inspect compound ingredients/aliases/advisories, and retain explicit uncertainty. The current backend's unverified extraction cannot establish a complete current manufacturer label. Sourced facts use exact retained excerpts, not model reply prose.
- Removed camera/gallery UI, photo helpers, FileProvider/path resource, Android image transport, backend vision model/reservation/processing, and obsolete image probe. Current privacy/setup/device docs reflect text-only behavior. Historical reports remain historical. Settings can revoke search permission and change optional Groq fallback consent.
- Actual checks: non-packaging Gradle dry-run graph reviewed; `:app:compileDebugKotlin` passed. Final `scripts/build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug')` passed **140 tests, 0 failures/errors/skips**, including **40** food-domain/service tests and **7** persistence/UI tests. Lint passed. `npm test --prefix backend` passed **54** before the additional removal suite; `node --test backend/test/phase4.test.js` passed **13**. `npm run check --prefix backend` passed, **58.98 KiB / gzip 16.95 KiB**, dry run only.
- Tests use explicitly synthetic recipe/label sources. No new end-to-end live feature generation or device/user usability pass is claimed. Phase 2's actual provider/live evidence remains historical. Production backend deployment and final device behavior remain release dependencies.
- Community rationale: [Your RAG Eval Is Checking the Receipt, Not the Patient](https://dev.to/reidmarlow/your-rag-eval-is-checking-the-receipt-not-the-patient-3e43) and its entity-attribution discussion informed wrong-dish/variant rejection; citations alone do not establish entity identity. This is secondary guidance, not acceptance evidence.
- Phase 4 local acceptance is complete within the explicit conservative evidence boundary. Continuous execution proceeds to Phase 5 under the user's updated instructions. No APK/AAB, production deployment, commit, or push.

### Phase 5 checkpoint — 2026-10-04

- Added `SourcedWorkoutService.kt`, `WorkoutChatScreen.kt`, additive `SourcedWorkout` programming fields, 24 service/domain tests and 8 persistence/integration tests. New workout requests use research evidence, not the fixed six-exercise generation list. Legacy sessions remain readable.
- Article blocks retain instructions, dose, rest, form and easier options when published. Warm-up/main/cooldown are required. Unknown or unsupported layouts ask for another source. Shorter duration is an explicitly labeled time budget, not a verified runtime; easier doses and omitted difficult push-ups are labeled AI programming changes. No injury rehabilitation is claimed.
- Optional retrieved YouTube metadata must match a retained movement and is always a technique reference. Invalid video metadata is discarded without discarding valid workout article evidence. Articles and video metadata have separately validated source records.
- Integrated shared chat state with cancellation/profile fences, saved sessions, selection, completion history and optional rest timer. Same-day completion deduplicates across reloads; completion metadata synchronizes current, saved and feature-result copies.
- Actual checks: full Android/JVM/Compose suite and lint passed with the 24 initial Phase 5 tests (**164 tests**). After adding integration tests and completion synchronization, focused `:app:testDebugUnitTest --tests com.mrpoodles.app.Phase5* :app:lintDebug` passed **32 tests** and lint. Tests are synthetic, not a live workout or physical-device session. No backend change in this phase; backend checks remain prior Phase 4 results.
- Live extraction coverage is deliberately limited to complete recognized article layouts. A source index or incomplete article does not become a workout. Final real-provider feature coverage/voice and device usability remain explicit Phase 7/8 review items.
- Phase 5 local acceptance complete. Phase 6 continues under the updated nonstop instruction. No APK/AAB, production deployment, commit or push.

### Phase 6 checkpoint — 2026-10-04

- Added `FoodLogging.kt`/`FoodLogScreen.kt`, `Planning.kt`/`PlanningScreen.kt`, and 63 service/domain/integration/navigation tests. Home now opens the natural-language diary directly; manual entries, name/date/calorie edits and date browsing remain available through Meals.
- Eaten statements, Hindi transliterations, quantities, multiple foods, local today/yesterday and explicit dates have deterministic intent parsing. Questions never authorize intake writes. Comparable retrieved nutrition blocks provide rough estimates and nullable macros; no serving weight is invented. Unknown calories require an explicit save action. Full citations, assumptions and disagreement ranges are retained.
- Prepared log batches persist before mutation, with stable operation IDs, original dates and expected revisions. Batch effects/receipts and success state commit atomically. Correction/Undo tests preserve entry identities and earlier records; retries and cancellation/profile changes cannot duplicate or overwrite entries. The actual backend task is `food_log`, corrected from the subagent's initial unsupported `nutrition` request.
- Planner supports arbitrary dates/full weeks, individual replacements/moves/portions, persisted previews and concurrent-edit rejection. It requires distinct sourced recipes and makes up to three bounded lookup attempts per action while preserving suitable finds. Incomplete libraries do not overwrite a plan. Unit-aware shopping scales from actual sourced servings, merges g/kg and ml/l, applies only explicit matching pantry stock and retains bought checks.
- Compatibility: schema-2 fields have additive defaults. Historical recipes, intake, workouts and profile records remain present. Planned sourced food can be logged with unknown nutrition; original recipe nutrition is not silently retained after adaptation.
- Actual checks: Phase 6 service/domain/UI and integration tests compiled. Initial run found an obsolete task expectation and Robolectric UI waits; corrected the expectation to the existing backend contract and pumped the main loop. Manual/edit forms use bounded scrollable dialogs after an observed Compose idle failure. Full suite ran **235 tests**, with **one** old manual-draft UI failure caused by its now-offscreen submit control. Scrolling that control before activation preserved the same failed-save/restoration assertions; focused `InvalidRecordsUiTest` then passed **2/2**, and lint passed. Full-suite rerun is Phase 7's first action.
- Tests are synthetic, not measured internet nutrition/workout coverage. Final provider corpus, usability and signing/deployment checks remain release dependencies. No APK/AAB, production deployment, commit or push. Phase 6 local implementation/acceptance complete; Phase 7 proceeds.

### Historical handoff: Phase 3 complete; Phase 4 next

- Read `AGENTS.md`, resume status, sections 5.1–5.3, the Phase 2/3 checkpoints and `docs/PHASE2_BACKEND.md` before editing.
- Preserve all uncommitted Phase 1–3 source/test work and all three documentation files. They are deliberately not committed; release operations remain Phase 8 only.
- All remaining phases are authorized. Current Android verification: 93 tests and lint pass. Phase 2's 54 backend tests/dry run/live results are historical and unchanged in Phase 3. Begin Phase 4 on continuation, with its own acceptance/checkpoint/stop discipline. Do not repeat the account questionnaire.
- Use the existing evidence/request schemas and actual companion personality definitions. Do not claim future feature UIs or unverified provider accounts work. Do not deploy production changes incidentally.
- Follow `docs/PHASE2_BACKEND.md` for local setup and live checks. Use the bounded local runner; do not restore `local: false` for Cloudflare or clear `.wrangler/live-state`. Never print keys or infer account billing from key presence. If signup requires a forbidden verification/payment step, exclude that provider rather than bypassing it.
- Do not repeat successful offline test runs merely to resume; rerun when changes, failures, or a concrete unresolved concern justify it. Do not mark Phase 2 complete until its live gates have actual results.

1. Read `AGENTS.md`, then sections 1, 2, 8, and 10 of this file and the active phase's requirements.
2. Inspect Git status and changes; do not assume the repository still matches the baseline.
3. Determine the last user-authorized phase. If none beyond Phase 0, ask/wait rather than implementing.
4. Read the actual affected files before editing; this document is context, not a substitute for source inspection.
5. Resume the recorded next task. Re-run checks only where new changes, failures, or unresolved concerns justify them.
6. Update this file before stopping. Never leave a claimed completion without evidence or silently advance to the next phase.

### Phase 5 owned workout implementation handoff — 2026-10-04

- The user explicitly activated Phase 5 after Phase 4's 140-test acceptance, assigning this workstream `SourcedWorkoutService.kt`, `WorkoutChatScreen.kt`, `Phase5WorkoutTest.kt` and fixtures, plus additive `SourcedWorkout` fields. The main agent owns ViewModel/domain/feature-state/navigation integration and Gradle verification. No Gradle command was run by this workstream.
- Added `WorkoutChatReply(message, workout, subject)`, `SourcedWorkoutService(model).reply(id, profile, conversation, text)`, and `SourcedWorkoutRules.validate(workout, profile)` with the agreed signatures. New sessions call `CloudModel.research` using common research constraints and validate request-bound citations. Model prose is not exercise evidence. The existing backend composes the shared companion personality for primary and fallback research.
- Added conservative Markdown/bold-heading exercise extraction requiring explicit warm-up/main/cooldown sections, complete action instructions and published dose information. Sessions select complete article-supported blocks rather than the six-exercise legacy catalog. Selected blocks and reduced programming are explicitly AI-labeled; missing form/alternative/rest details stay unknown. Local duration, easier-dose, quiet/no-jumping and difficult-push-up edits re-parse retained evidence and rebind request/snapshot identities while preserving original source dates. Push-ups can be omitted when other complete movements remain; no unsupported substitute is invented.
- Added fields (all defaulted): `durationMinutes`, `programmingNotes`, `noEquipment`, `quiet`, `noJumping`, `avoidPushUps`, `reducedDose`, `subject`. A requested duration is an explicitly labeled time budget, not a claim of measured or published total session duration. Local easier edits cap an existing published dose at one set/five reps/20 seconds and retain the article text below the overriding AI label.
- Added `WorkoutWorkspace(state, vm)` and `SourcedWorkoutCard(workout)`: source permission, feature-owned composer, saved-workout sheet, save/completion callbacks, committed-data confirmations, stale-profile/validation notices, source attribution, metadata-only technique links and a saveable optional rest timer. UI uses only the agreed ViewModel functions. The caller must keep selected `data.workout` synchronized with the feature result and fence persistence by request/profile revision.
- YouTube metadata is retained only for a valid returned URL/title/channel with a named movement match; it is always `TECHNIQUE_REFERENCE`, never watched/follow-along verified. Its provenance is a separate `youtube_metadata` source record in the result snapshot. Invalid/deleted/unrelated/missing metadata is omitted by the service. Integration limitation: the existing real `CloudModel.research` validates video metadata before the service receives it; malformed transport metadata can therefore reject the whole response unless the main agent adds workout-specific sanitization before that validation.
- Added 24 synthetic service/domain tests and `app/src/test/resources/phase5/beginner-workout.txt`: complete/incomplete evidence, no sources, consent, injuries, equipment/noise/profile constraints, wrong topics, forged article claims/instructions/metadata, local edits, cancellation/failure preservation, shared stale-request checks, serialization, and preventing rest/easier-option durations from becoming the main dose. These tests are authored but **not executed** here. Main-agent integration/UI tests, full Gradle acceptance, live article-layout coverage and device behavior remain pending.
- `git diff --check` passed for tracked changes; `git diff --no-index --check -- NUL <path>` also passed for the three owned Kotlin files, with LF-to-CRLF notices only. These are whitespace checks, not Kotlin compilation. An attempted `rg -c` test-count helper was unavailable; the dedicated search tool confirmed 24 test declarations instead. All previous working-tree changes were preserved. No deployment, APK/AAB, commit or push occurred.
- Conservative limits: unusual article layouts, missing complete section instructions, ambiguous multiple doses, unavailable equipment or uncertain suitability ask a clarification rather than returning a manufactured session. The parser currently requires a single article to provide all three phases. A metadata title must name a selected movement; merely generic workout videos are omitted. Clinical guidance cannot be established through chat; recorded injuries remain a clarification gate.
- Community reference inspected: [Making RAG admit when it's guessing: source-grounded hallucination checks](https://dev.to/sidswirl/making-rag-admit-when-its-guessing-source-grounded-hallucination-checks-g22), Sid Probstein, plus its discussion. It motivates independent source-span validation and explicit unsupported outcomes; the comments distinguish citation errors from unsupported claims. This is secondary design context, not evidence of this implementation passing.
- Workstream status: implementation ready for main-agent integration and verification; **Phase 5 is not marked complete by this handoff**.
