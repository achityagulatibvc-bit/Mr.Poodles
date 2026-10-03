# Android device validation — 0.3.1

These are physical-device checks, not claims of completed phone testing. Use synthetic chat and known ingredient labels.

## Navigation and layout

1. Install the APK over the previous version using the same signing key. Confirm the existing profile, recipes, food diary, and plans remain.
2. On a fresh install, review the welcome disclosure and continue. Verify the launch splash does not impose an artificial wait.
3. **Reproduce the reported sequence:** go directly from Today to Check, Meals, Move, and Today without first opening Chat. Tap both icons and labels. The selected destination and visible screen must agree.
4. Enter a recipe wish, label text, and chat draft. Switch tabs repeatedly; all drafts, the selected meal date, recipe subtab, and scroll positions should remain.
5. Repeat navigation after opening Chat, after opening/closing the keyboard, and after returning from About you. Check Android Back, rapid taps, rotation, and background/resume.
6. Test the OnePlus keyboard, small windows, landscape, large fonts, TalkBack labels, 48dp minimum touch targets, and visible composer/Save controls.
7. Turn gentle movement off and disable system animations. Verify scene motion stops. Background the app and verify animations pause. Sounds start off and respect silent mode and music playback.

## Companion and memory

1. Greet Poodles, vent, celebrate, request advice, and ask for quiet company. Check warmth, brevity, specific acknowledgment, and appropriate expressions.
2. Tell Poodles to use shorter replies or stop pet names. Current instructions should apply immediately; uncertain patterns must not become fixed preferences after one request.
3. Save comfort memories without saving chat history, restart, and verify only the preferences remain. Disable comfort-memory storage and verify it is absent after restart.
4. Inspect and remove an individual memory, then remove all memories. The previous conversation should clear to prevent deleted context from being sent again.
5. Check rare imaginary rose/chocolate gestures, their dismissal, cooldown, and absence during serious distress. Gifts must not replay their entrance on every tab switch.
6. Interrupt a reply with a network failure. Retry should not duplicate the user message. Stop a reply and clear the conversation during generation; a late reply must not resurrect it.
7. Submit and reject a profile-edit proposal, then explicitly accept a different proposal. General conversation must not silently change food restrictions.

## Photos and food

1. Select a gallery image and capture a camera image. Confirm the preview, rotation, metadata-stripped request, and retry. Synthetic service transcription passed; the real phone camera/gallery still needs validation.
2. Test glare, blur, tiny print, rotation, and each supported label language. Compare every ingredient, negation, quantity, and advisory statement with the source.
3. Typed `rice, SOYA protein, whey, cayenne` should flag the three initial intolerances. `Rice, spices, natural flavors` should request clarification. `Lactose-free milk` should retain conservative dairy matching.
4. Add a restriction and confirm that recipe and meal validation uses it. Incomplete labels must not imply safety.
5. Create and save a recipe, place it on a date, log half a portion, then log again with a different portion. The existing food entry should update, not duplicate.
6. Add a custom food with unknown calories. Totals must say they are incomplete. Verify day/week planning, recipe details, and shopping quantities.
7. Ask for pasta, then a potato sandwich, then a different savory meal. The result must use the requested ingredients and avoid a renamed repeat. Try explicit exclusions such as "without spinach".
8. Generate a week from saved recipes. Check all seven days and confirm breakfast, lunch and dinner differ within each day. A fresh shelf should generate new AI recipes rather than insert preset meals.

## Availability and emotional-support regression

1. Use three recipe requests followed by five chat messages. They must not hit the old shared eight-request ceiling.
2. Test a scoped tool limit. Chat should remain available. Inspect `service-status.py` for separate counters.
3. Test a real 429 response. The app should show the category and wait time, preserve the draft/message and disable premature chat retries.
4. "I'm crying" should receive acknowledgment and a gentle question about what happened, without helplines or assumed danger.
5. "I'm crying because my friend ignored me" should receive contextual support. Concrete present danger must still receive calm, appropriate urgent help.

## Movement and persistence

1. Generate a routine, mark completion, then change environment. Old routines/plans must be flagged for review.
2. Record an injury and verify automatic routine generation pauses.
3. Check storage failure: Save must report the error and keep the draft. Restart after successful writes and verify the newest data, not merely the in-memory UI.
4. Confirm personal content does not appear in shared diagnostics. Do not uninstall as a troubleshooting step unless you intend to erase app data.
