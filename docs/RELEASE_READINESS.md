# Phase 7 independent source review

**Release update (2026-10-04):** the user subsequently authorized Phase 8. APK 0.4.0/code 7 is built, signature continuity and audit pass, and the backend is deployed with successful production contract/research/companion checks. See [the current release record](RELEASE_0.4.0.md). The Phase 7 review below is historical; physical-device and intended-user acceptance remain unperformed because no device was attached.

Review date: 2026-10-04. Scope: the current, uncommitted Phase 4–6 implementation, its integration and historical migration/retry contracts. This is a source review, not a release approval. Line references describe the reviewed working tree and may move after fixes.

## Release state

- **R1–R5 have been fixed and regression-tested.** Further duration-programming and observed source-layout gaps are also fixed. The original findings below remain historical evidence; the final master checkpoint records the newer fixes.
- **Three actual-evidence replay checks now pass:** banana nutrition through logging/correction/Undo; a three-source NHS timed workout through save/completion; and a Garden tomato recipe through range-preserving serving changes/restriction checks. These use actual captured provider evidence but substitute Android HTTP. A live Android-to-deployed-backend/device acceptance pass remains pending.
- **The redesign backend has not been deployed. Production deployment requires explicit permission** and coordinated client compatibility. A backend missing `/v2/research` cannot serve these features; the new backend retires old-client image requests.
- **No new APK/AAB was generated.** Version metadata is still 0.3.1 / code 6. An old artifact is not this release. The pre-Phase-8 user check-in remains required.
- Final signing-key continuity, upgrade installation on the prior app's data, physical-device/keyboard/splash checks and intended-user unaided usability are **unperformed**.
- Final post-resume full run: **324 Android/JVM/Compose tests passed, 0 failed/ignored**, with the three replay captures explicitly configured. Lint: **0 errors, 6 dependency-version warnings**. Unchanged backend production code retains its prior **67-test** and Wrangler dry-run passes. See the final master checkpoint for exact commands, earlier failures and provider measurements.
- Documentation whitespace checks passed (`git diff --check` for the tracked documents and `git diff --no-index --check` for new documents); Git emitted only line-ending conversion notices. The regex cross-check is described under R2. Neither check establishes Android execution or device behavior.

## Resolved source-review findings

The evidence, line numbers and remediation requests in these findings describe the pre-fix tree. Resolution paragraphs describe the current implementation. Passing these regressions does not establish complete real-world source coverage.

### R1 — Nutrition can be attributed to a different food (high)

**Resolved:** `FoodLogging.kt` now requires matching normalized food identity in the source title and relevant body headings, retains preparation distinctions, and limits eligible source kinds. Bounded MyFoodData handling additionally checks the exact host, dataset, food heading and first nutrition table. `Phase7NutritionTest` covers fruit versus prepared foods, body/title contradictions, pyaaz/onion equivalence, plural names, and the wrong kachori/puri/kolachi matches. All 21 tests passed in the final full run.

**Evidence:** [`FoodLogging.kt:115–120`](../app/src/main/java/com/mrpoodles/app/FoodLogging.kt#L115) accepts a source whenever all requested food tokens appear in its title. [`FoodLogging.kt:137–159`](../app/src/main/java/com/mrpoodles/app/FoodLogging.kt#L137) then accepts a generic gram or piece basis without binding that block to the requested food's identity/preparation.

**Source-derived case:** `I ate 100 g banana` can accept a nutrition source titled `Banana bread nutrition`, containing `Per 100 g` and a labelled calorie value. `banana` is in the title; both bases are grams. Bread nutrition becomes the banana entry. The same problem applies to raw/cooked or other preparations sharing title tokens. Request IDs and exact quotation checks do not detect the wrong entity.

**Impact:** non-null calories automatically commit through [`PoodlesViewModel.kt:428–444`](../app/src/main/java/com/mrpoodles/app/PoodlesViewModel.kt#L428), with a misleading sourced estimate rather than a clarification.

**Needed:** bind nutrition to the actual food/preparation and selected block; reject ambiguous compound titles. Add negative banana-versus-banana-bread and raw-versus-cooked cases before claiming source integrity.

### R2 — Grouped calorie numbers are silently truncated (high)

**Resolved:** whole numeric tokens are captured and validated before conversion; valid thousands grouping retains the complete value, and unsupported numeric forms cannot become suffixes. `Phase7NutritionTest` covers 1,200/1,000, malformed tokens, grouped serving bases, explicit zero, decimals and table metrics. Quotes retain the original source spelling. Early regressions in plural-food recognition and prose/heading classification were corrected before the passing 285-test run.

**Evidence:** [`FoodLogging.kt:113,123–132`](../app/src/main/java/com/mrpoodles/app/FoodLogging.kt#L113) uses an ungrouped numeric pattern plus a reverse search that can start after a comma. [`FoodLogging.kt:167–187`](../app/src/main/java/com/mrpoodles/app/FoodLogging.kt#L167) scales and publishes the parsed number as estimated calories.

**Source-derived case:** a matching `Serving size: 1 pizza` block with `Calories: 1,200 kcal` yields **200**, while `Calories: 1,000 kcal` yields **0**. The forward parser rejects the grouped value, but the reverse parser accepts its suffix; there is no conflicting second value to make the result unknown. Zero then qualifies for automatic saving.

**Verification performed:** an in-memory PowerShell/.NET regex probe of the reverse pattern captured `200`, `000`, and `200` from `1,200 kcal`, `1,000 kcal`, and `200 kcal` respectively. This is a regex cross-check, not execution of the Kotlin service. An initial Node inline probe failed on shell quoting and supplied no result.

**Needed:** either parse supported grouping formats explicitly or reject the entire numeric token; never match a suffix of an unsupported number. Cover grouped and ambiguous decimal formats at the service boundary.

### R3 — Stale SavedState can replace the durable food-operation state (high)

**Resolved:** startup calls `FeatureRestoration.restore`, reconciling disk receipts/preparations with saved-instance state. Committed completion and Undo survive stale pending snapshots; newer typed drafts remain, and prepared retries retain exact operation bytes, dates and revisions. `Phase7RestorationTest` (16 tests) and `Phase7StateFlowTest` (7 tests) include the actual snapshot-before-commit ordering and verify no repeated research.

**Evidence:** [`PoodlesViewModel.kt:68–71`](../app/src/main/java/com/mrpoodles/app/PoodlesViewModel.kt#L68) unconditionally prefers a decoded `SavedStateHandle` feature over the freshly loaded disk feature. [`PoodlesViewModel.kt:406–427`](../app/src/main/java/com/mrpoodles/app/PoodlesViewModel.kt#L406) reuses an exact preparation only if it is present in that selected feature. [`IntakeOperations.kt:16–21`](../app/src/main/java/com/mrpoodles/app/IntakeOperations.kt#L16) correctly rejects reused operation IDs with different payloads.

**Source-derived case:** Android captures instance state while nutrition lookup is pending, before `preparedLog` exists. The task subsequently commits its prepared payload and food entry to disk while backgrounded. After process death, restoring the older instance state resurrects the pending draft instead of the completed disk feature. Retry keeps the old operation ID but researches again with a new request/source origin. Its new payload fails the already-committed receipt fingerprint check. The durable entry survives, but the recovered flow loses its committed success/Undo state and cannot complete that retry normally.

**Needed:** reconcile instance state with the durable feature/operation ledger before resuming, and preserve the exact prepared payload when its operation has already progressed on disk. Test an older saved-instance snapshot against a newer committed disk snapshot; the existing restart test uses an empty `SavedStateHandle` (`Phase6FoodFlowTest.kt:71`) and does not cover this ordering.

### R4 — Turning chat-history storage off does not sanitize restored feature history (privacy)

**Resolved:** restoration applies current storage consent. Successful profile saves sanitize memory and disk histories and rewrite every feature SavedState key while preserving current drafts/results. Tests inspect raw disk, the original SavedState handle and in-memory state. Settings copy now distinguishes companion Chat deletion from disabling stored conversation histories; saved result and diary evidence remain separate.

**Evidence:** [`PoodlesViewModel.kt:93–97`](../app/src/main/java/com/mrpoodles/app/PoodlesViewModel.kt#L93) strips disk message lists when `rememberChats` is false, but [`PoodlesViewModel.kt:127–144`](../app/src/main/java/com/mrpoodles/app/PoodlesViewModel.kt#L127) does not rewrite existing feature SavedState values. [`PoodlesViewModel.kt:68–71`](../app/src/main/java/com/mrpoodles/app/PoodlesViewModel.kt#L68) restores those values without applying the new consent policy. [`PoodlesViewModel.kt:262–265`](../app/src/main/java/com/mrpoodles/app/PoodlesViewModel.kt#L262) publishes the unsanitized value in memory even when its newly serialized copy is stripped.

**Source-derived case:** with Remember our chats enabled, complete a recipe/workout exchange, disable the setting and save, then restore the activity/process with its saved state. The disk copy has no messages, yet the old feature messages become visible again from SavedState. Separately, the advertised Chat clear action only clears companion `data.messages` (`PoodlesViewModel.kt:653–656`), not the other feature histories.

**Needed:** apply consent when restoring and when revoking storage across all history-bearing state stores; make deletion scope accurate and provide a feature-history deletion path. Preserve necessary recipe/intake evidence separately from optional conversational history. Verify revocation with nonempty SavedState, not only a cold disk read.

### R5 — Planner discovery can repeatedly spend allowance without exploring later dishes (functional)

**Resolved:** `AppData.planSearchOffset` is persisted before each bounded candidate attempt, including unsuccessful attempts. The next request and recreation proceed to later candidates. The integration regression checks the on-disk cursor inside each lookup and then verifies a successful later preview. This avoids the first-three-candidates trap; it does not guarantee that the reviewed corpus contains 21 suitable distinct meals.

**Evidence:** [`PoodlesViewModel.kt:506–518`](../app/src/main/java/com/mrpoodles/app/PoodlesViewModel.kt#L506) always takes the first three dish names absent from successful library titles. Failed/unusable attempts are not recorded. [`Planning.kt:62–87`](../app/src/main/java/com/mrpoodles/app/Planning.kt#L62) requires three distinct suitable recipes for a day, twenty-one for a week, and throws while the library is short.

**Source-derived case:** an empty library receives incomplete or restriction-incompatible results for overnight oats, chickpea salad and rice salad. No recipes enter the library. Every subsequent preview looks up exactly those three again, even if suitable later dishes are available. There is no cursor, failed-attempt history or query variation to reach the remainder. Fewer failures can similarly occupy the remaining search slots once other titles have succeeded.

**Needed:** bounded, persistent discovery progress or explicit candidate rotation/variation while preserving request/profile fences. Verify a failed first batch followed by usable later candidates. Continue to honor provider limits; this finding is wasted allowed requests, not a server quota bypass.

## Supported implementation and limits

| Area | Source-supported behavior and boundary |
| --- | --- |
| New feature services | These are **strict parsers and supported local transformations, not general-purpose AI feature generation**. The backend calls AI for research notes; the client does not turn arbitrary model prose into recipes, verdicts, exercises or nutrition. Visible replies are largely client-authored templates. Shared backend personality composition alone does not prove equivalent conversational capability or live voice quality. |
| Recipes | Explicit ingredient, serving, method and timing formats; title relevance; retained quotes; local portion scaling; narrowly supported same-dish, equal-amount, no-cook substitutions. Unsupported layouts or substitutions fail/clarify. Source recipe nutrition is currently unknown, including unadapted recipes. See `SourcedFoodRules.kt:116–205,208–260,293–341`. |
| Food checks | Supplied labels may be checked locally. Manufacturer lookup requires brand/variant/country and identity evidence. The backend emits `completeness: unverified`; headings do not establish a complete current label. Typical dish evidence is not the user's dish. See `SourcedFoodService.kt:132–185`, `retrieval.js:130–131`. |
| Workouts | One recognized article must provide complete warm-up/main/cooldown blocks with explicit doses; optional rest/form/easier details may remain unstated. Limited time-budget/easier/no-push-up edits are labelled programming changes. Injuries pause generation; generic routines are not rehabilitation. Video metadata must name a retained movement and is only a technique reference, not watched/exact follow-along evidence. See `SourcedWorkoutService.kt:10–58,160–232`. |
| Food logging | Deterministic English/transliterated-Hindi intent and date recognition; not unrestricted language understanding. Comparable gram/piece bases only; most cup/bowl/spoon requests remain unknown. Unknown calories require explicit save. Prepared batches and receipts share atomic disk persistence, with the restoration reconciliation in R3. Specific service-limit messages/reset waits now remain in the unknown-estimate explanation; transport failure is explicitly labeled. |
| Planning and shopping | Deterministic source-library selection, bounded lookup batches, preview-before-apply and concurrent-meal/profile checks. No general conversational planning service. Shopping scales by sourced servings and merges g/kg or ml/l, retaining incompatible units. Bought checks are shared by ingredient/unit across dates; pantry is explicit stock, not inferred free text. See `Planning.kt:104–129,170–242`, `PlanningScreen.kt:152`. |
| Migration | Additive schema-2 defaults and read-only migration preserve historical records; undecodable list entries are quarantined with raw content. Broken whole snapshots/feature state/operation ledgers block writes. This is not device-upgrade, power-loss or downgrade compatibility proof. See `SnapshotMigration.kt`, `LocalStore.kt`. |
| Privacy and freshness | Research excludes unrelated companion history structurally; relevant task text and selected constraints still leave the phone after source consent. Exa/Tavily perform search/extraction, YouTube performs workout-video lookup, Cloudflare generates notes, and optional Groq requires client and server eligibility. Cache is off by default. Retained local recipe/workout evidence keeps its old retrieval date; reusing it is not a fresh lookup. History revocation/restoration is covered by R4's regressions; necessary saved evidence is distinct from optional history. |
| Navigation | Source routes connect all four Home actions, recipe/food-log Back-to-origin, Meals planner/diary and workout shelves. This review did not execute reachability, keyboard, large-font, process-death or intended-user tests. Source reachability alone is not usability acceptance. |

## Remaining acceptance evidence

**Current decision:** supported-flow pre-packaging checks pass. Stop for the required Phase 8 confirmation. The older evidence requests below are retained for context; actual-evidence replays and the 324-test run now satisfy the local source/integration portions, not deployed-client or physical-device acceptance. Known unsupported/missing evidence remains an explicit limitation rather than a fabricated result. No release artifact has been created.

1. R1–R5 fixes and regression cases are complete; retain the findings and their resolutions for traceability.
2. Full Android regression passed 285 tests, followed by the five affected UI checks after dialog sizing. Backend passed 67 tests; dry run passed. Latest lint: 0 errors, 6 existing dependency warnings. Focused Gradle runs overwrite current reports with their subset; the master checkpoint retains the preceding full-run result.
3. Exercise actual retrieved pages through each final client parser and persistence path, including realistic failed-source/limit behavior. Bounded synthetic live checks are already authorized, but must honor persisted provider cooldowns and free budgets. Keep rejection/coverage rates and latency distinct from an HTTP-success count. The tiramisu probe returned a lactose-containing recipe with unresolved compound labels/waiting time; the kachori probe returned other foods; the workout probe hit a Groq limit. These are unresolved coverage/acceptance cases, not successful final features.
4. Before Phase 8, obtain the required user check-in. Before a production backend rollout, obtain explicit deployment permission and check v1/v2 client compatibility and configured free-provider eligibility.
5. Verify signing continuity, then the authorized final artifact and upgrade migration. Record actual physical-device and unaided intended-user results. Until performed, these remain pending.

## Community Wisdom — historical rationale only

The master plan already records [citation-to-source validation](https://dev.to/ranknod/check-ai-citations-against-retrieved-source-ids-3hi8) and [citation accuracy beyond a real source ID](https://dev.to/enhanciar_ai_96a4ba4877e3/how-we-make-an-llm-cite-every-answer-down-to-fileline-and-check-the-citation-is-telling-the-truth-hmj). Their distinction is relevant to R1: a real quote can still describe the wrong food. These are previously recorded secondary references, not newly fetched evidence, consensus or proof of the findings. No live/community calls were made for this source-only review.
