# Android device validation — current development checklist

**Physical-device acceptance remains unperformed for 0.4.0 / code 7.** Phase 8 packaging and authorized backend deployment are done; APK/signature/automated checks pass. The old and new APK signing certificates match. `adb devices -l` returned no attached devices, so no installation or unaided user test is claimed. See [the current release record](RELEASE_0.4.0.md) for artifact/hash and actual results. None of the device checks below is marked passed by source inspection or Robolectric tests.

Use synthetic chat, known ingredient lists and reviewed public sources. Record device/OS, app artifact hash, backend revision, date, actual result and any blocker for each completed check. An unavailable provider, phone or intended user is pending, not a pass.

## Navigation and layout

1. Install the APK over the previous version using the same signing key. Confirm the existing profile, recipes, food diary, and plans remain.
2. On a fresh install, review the welcome disclosure and continue. Verify the branded cold-launch experience has its two-second minimum while local data loads; no network wait or native/Compose flash. Rotation, ordinary resume and tab changes must not replay the delay.
3. **Reproduce the reported sequence:** go directly from Home through Check, Meals and Move, then Home, without first opening Chat. Tap both icons and labels. The selected destination and visible screen must agree. Home's four labeled actions must open the checker, recipe workspace, workout screen and natural-language food-log workspace directly. Recipe and food-log Back return to their Home/Meals origin; Home returns Home. Manual entry remains reachable through Meals → Food diary.
4. Enter recipe, food-check, workout, food-log and companion drafts. Switch tabs repeatedly; all drafts, selected meal/diary dates, Meals subpage, results and scroll positions should remain. Repeat after activity recreation. Feature conversations must remain separate from companion history. Include restoration from an older SavedState snapshot after a newer food operation commits to disk (release finding R3).
5. Repeat navigation after opening Chat, after opening/closing the keyboard, and after returning from About you. Check Android Back, rapid taps, rotation, and background/resume.
6. Test the OnePlus keyboard, small windows, landscape, large fonts, TalkBack labels, 48dp minimum touch targets, and visible composer/Save controls.
7. Turn gentle movement off and disable system animations. Verify scene motion stops. Background the app and verify animations pause. Sounds start off and respect silent mode and music playback.

## Companion and memory

1. Greet Poodles, vent, celebrate, request advice, and ask for quiet company. Check warmth, brevity, specific acknowledgment, and appropriate expressions.
2. Tell Poodles to use shorter replies or stop pet names. Current instructions should apply immediately; uncertain patterns must not become fixed preferences after one request.
3. Save comfort memories without saving chat history, restart, and verify only the preferences remain as conversational memory; recipe/intake evidence has a separate persistence purpose. Disable comfort-memory storage and verify it is absent after restart. Enable feature chat history, complete an exchange, then disable it and restore with nonempty SavedState; old messages must not reappear (release finding R4). Verify deletion scope across feature histories as well as companion Chat.
4. Inspect and remove an individual memory, then remove all memories. The previous conversation should clear to prevent deleted context from being sent again.
5. Check rare imaginary rose/chocolate gestures, their dismissal, cooldown, and absence during serious distress. Gifts must not replay their entrance on every tab switch.
6. Interrupt a reply with a network failure. Retry should not duplicate the user message. Stop a reply and clear the conversation during generation; a late reply must not resurrect it.
7. Submit and reject a profile-edit proposal, then explicitly accept a different proposal. General conversation must not silently change food restrictions.

## Source lookup and privacy

1. Upgrade an existing profile and confirm both new source/model consent flags default off. Without source consent, pasted ingredients still run locally. No provider account/key entry belongs in ordinary app use.
2. Review **Allow source lookup**: food/workout terms go to Exa/Tavily; workout video terms also go to YouTube; relevant request/preferences go to Cloudflare. **Also allow Groq if Cloudflare is unavailable** starts unchecked. Enabling source lookup alone must not permit Groq. Check the disclosure when permission is first enabled from each feature, and verify saved choices after restart.
3. Use a controlled development fixture to fail Cloudflare. Without Groq consent, preserve draft/result and show a real failure; with consent and an eligible configured free account, validate the fallback's citations and shared companion policy. Do not exhaust a real account to simulate failure. Enabling a backend key must not opt an old client in.
4. Inspect sanitized development request metadata: unrelated companion history, comfort memories and private events must not be sent to source lookup. Do not capture secrets or personal content in shared diagnostics.

## Text-only food checks

1. Confirm no camera/gallery button, image preview, rotate/re-read control, OCR setting or photo permission prompt remains. Enter a dish, exact packaged product, public manufacturer URL and pasted ingredients through text.
2. Packaged-food lookup must ask for missing brand, exact variant and country. A dish recipe is only typical ingredients, not a verified label for the actual dish. Wrong variants/countries and unavailable manufacturer pages must not yield an all-clear.
3. With matching enabled restrictions, `rice, SOYA protein, whey, cayenne` should expose the detected conflicts and restriction types. `Rice, spices, natural flavors`, `[unclear]`, unfamiliar aliases and untranslated text must retain uncertainty. Compare findings and quoted advisory statements with the actual supplied source.
4. Compare lactose intolerance with milk allergy using `lactose-free milk`, whey, casein and a plant-milk label. A lactose-free claim must not suppress milk-allergy findings. Check compound ingredients, negative claims and cross-contact warnings.
5. Expect **Avoid** for identified conflicts in the reviewed food, **No listed conflict found** only for sufficiently complete reviewed information, and **Need more information** for missing/conflicting evidence. None means guaranteed safe. Missing ingredients/advisories, ambiguous identity or a failed lookup must not become a clean result.
6. Confirm the complete-label checkbox applies to the submitted ingredients, not a newly edited product request. Change product details, label text and profile restrictions while a request is pending; obsolete results must not replace the current request. Follow-up clarification must keep the identity and source being discussed clear.
7. Open source links and source text; verify publisher/author where available, retrieval date, product identity/version and the evidence behind findings. Conflicting manufacturer labels must be surfaced. Retry an unavailable source while preserving the previous result and recoverable draft.

## Sourced recipes

1. Request `tiramisu recipe` with lactose/soy restrictions. Require real retrieved ingredient quantities, servings and ordered method, plus source attribution and separate active/cooking/chilling times where stated. Unsupported or incomplete layouts must ask for another source, preserving the previous recipe; model prose must not fill missing facts.
2. Try **No coconut**, **Make two servings** and **Use hostel equipment**. Confirm exclusions and replacement conflicts are rechecked, supported quantities scale consistently, and unsuitable equipment is explained. Current support is conservative: serving edits and limited explicitly cited equal-amount no-cook substitutions, not arbitrary recipe rewriting.
3. For an adaptation, show original ingredient, replacement, reason and supporting source. Label it as adapted, not the author's original. Recipe/substitution references must identify actual retrieved text; forged references and conflicting guidance must fail closed in controlled fixtures. Missing evidence must not trigger a guessed substitution.
4. Save and restart. The sourced recipe, adaptation, source dates and nullable nutrition must survive. Show save confirmation only after persistence; on storage failure retain the draft. A profile change requires revalidation. Newly sourced ingredients are not restricted to the legacy 34-item catalog or three old preparation methods.
5. Original nutrition must not silently survive portion/ingredient changes; current sourced recipe nutrition is unknown. Legacy catalog recipes remain readable. Plan a sourced recipe, change its planned servings and verify shopping scales by sourced servings, including fractional recipe serving counts.

## Natural-language diary and durable retry

1. Try `Pyaaz kachori khayi hai`, `I ate two bananas yesterday`, several foods, and explicit dates. Confirm visible portion assumptions and date, nullable macros, citations and rough-estimate wording. Date browsing must not silently change an unsent log's date. These are recognized parser patterns, not a promise of unrestricted language understanding.
2. Ask `How many calories in kachori?`; verify no entry or receipt is written. Send `I ate two`, then `Half`, after a single-food log; change the same entry rather than creating duplicates. Multiple-food corrections must ask which food. Added chutney must be a separate food.
3. Unknown nutrition requires explicit **Save with unknown nutrition**; it must never become zero or show saved before disk success. Compare gram/piece bases without inventing weight. Reject wrong-food/compound-title evidence and grouped-number truncation (release findings R1/R2).
4. Test offline/quota failures without exhausting a real account. The current service converts these failures to unknown nutrition; confirm the eventual fix preserves actionable cause/reset information. Premature retries must not bypass backend budgets.
5. Interrupt after preparation, fail an effect save, restore after process death, and retry with the exact original operation/date/revision/source payload. Specifically restore older instance state over a newer disk commit. Verify one effect, a retained success state and usable Undo. Check stale corrections/deleted entries cannot resurrect data.
6. Use Edit/Undo and explicit-date manual entries. Log a restriction-conflicting food as actually eaten, with a separate warning. Totals with unknown calories must say incomplete. Preserve historical intake and operation receipts through upgrade.

## Planning and shopping

1. Browse arbitrary past/future dates and complete weeks. Add, replace, move, remove and change portions for individual meals. An occupied replacement must not erase sibling meals. Log half a portion from a legacy or sourced meal and update that same linked diary entry without duplication.
2. Preview a day/week from distinct suitable sourced recipes (three/twenty-one). A short library must keep the existing plan. Verify failed first-batch candidates do not starve later discovery indefinitely (release finding R5). Legacy catalog recipes remain manually plannable; they do not establish sourced-library variety.
3. Edit meals or change profile while generation is pending, then try Apply. Stale previews must be rejected. Review the explicit notice that replacement covers all target-date meals, including snacks; diary history remains separate.
4. Verify day/week shopping uses ingredient amount divided by sourced recipe servings times planned servings. Merge g/kg and ml/l; keep unsupported conversions separate. Apply only exact-name, compatible-unit pantry stock; duplicate ambiguous pantry entries must not be added together.
5. Bought checks are shared across dates for the same ingredient/unit, as labelled. They do not create pantry stock. Check persistence after restart and visible unknown quantities.

## Availability and emotional-support regression

1. Use three recipe requests followed by five chat messages. They must not hit the old shared eight-request ceiling.
2. Test a scoped tool limit. Chat should remain available. Inspect `service-status.py` for separate counters.
3. Test a real 429 response. The app should show the category and wait time, preserve the draft/message and disable premature chat retries.
4. "I'm crying" should receive acknowledgment and a gentle question about what happened, without helplines or assumed danger.
5. "I'm crying because my friend ignored me" should receive contextual support. Concrete present danger must still receive calm, appropriate urgent help.
6. Compare recipe, food-check, workout, food-log and planner clarification/confirmation text with companion Chat's gentle Mr. Poodles voice. Feature replies are mostly local templates; compare real primary/fallback research separately. Warmth must not obscure conflicts or uncertainty. Synthetic prompt-composition tests do not establish live feature reply quality.

## Backend/client rollout compatibility

The local updated Worker rejects `task: "vision"` and any top-level JSON `image` field before quota/provider work. V1 bodies over 160,000 bytes fail with HTTP 413; v2 retains its 6,000-byte cap. Text/SSE envelopes remain compatible. `/v1/status` exposes chat/assistance only; same-day stored historical vision usage is retained without making it reservable. Ordinary UTC daily rollover remains.

No production rollout has occurred. Test old-client text/chat against a controlled updated backend; old-client photo actions are expected to fail **only once that backend is deployed**. Coordinate the text-only client and explicitly authorized future deployment. Test the new client against an older backend missing `/v2/research`: show a real source-unavailable error and preserve drafts/results. Do not present a model-only fallback as sourced evidence or reset Durable Object counters during rollout.

## Sourced movement and persistence

1. Retrieve a beginner no-equipment session. Require a recognized article with complete warm-up/main/cooldown instructions and explicit doses. An index or incomplete article must not become a fabricated routine. Rest/form/easier options may be unstated; show this honestly.
2. Try `Only 15 minutes`, `No jumping, quiet hostel`, `Push-ups are difficult` and `Make it easier`. Verify supported local edits and explicit programming labels. A time budget is not measured duration. Record an injury and verify generation pauses rather than claiming rehabilitation.
3. Check storage failure: Save must report the error and keep the draft. Restart after successful writes and verify the newest data, not merely the in-memory UI.
4. Confirm personal content does not appear in shared diagnostics. Do not uninstall as a troubleshooting step unless you intend to erase app data.
5. Open/save/reopen a workout, mark completion twice on the same day, then change environment. Keep one same-day completion, synchronize shelf/current result and show stale-profile review. Check optional timer behavior across navigation/recreation.
6. Open a returned YouTube link and verify title/channel/movement relevance. Metadata is a technique reference only; no watched or exact-session claim. Missing/deleted/unrelated video metadata must not discard valid article evidence.

## Intended-user release gate

In Phase 8, ask the intended user to attempt one recipe, food check, workout and food log without step-by-step coaching. Record hesitation, missed controls and recovery difficulties, then fix the affected flows. This requires her participation; automated checks or a developer walkthrough cannot mark it passed.
