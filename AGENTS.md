# Project instructions

## Read before doing any work

Read `docs/IMPLEMENTATION_PLAN.md` first. It is the persistent source of project requirements, phase boundaries, decisions, verification results, and session handoff state. Inspect the current working tree before changing anything; unfinished changes may belong to the user or an earlier session.

## Phase discipline

- **The user completed the Phase 8 check-in and authorized packaging, deployment, commit and push. Version 0.4.0/code 7 is built and source-pushed; physical-device acceptance remains pending because no device was attached.** Read the current release handoff before any further work. Record new requirements in the master plan when received.
- Work on one authorized phase at a time. Complete its checks and update the master plan's checkpoint and handoff, then continue to the next authorized phase without an intermediate permission prompt. Stop for the required pre-Phase-8 check-in or an actual blocker; never mark unperformed acceptance as passed.
- If a phase cannot pass its acceptance criteria, record the blocker and leave it incomplete. Do not substitute a claim of success for an unperformed check.
- Keep the user's new requirements in the master plan so they survive session switches.
- spawn subagents whenever needed
- work nonstop until the user explicitly authorizes stopping do all phases as fast as possible like uve got 12 am deadline and its 11:50pm now go! go! go! don't stop until done!

## Release boundaries

- **Do not package, assemble, or bundle any new APK/AAB before Phase 8.** Compilation, appropriate unit/Compose tests, lint, and backend checks are allowed in their authorized phases.
- `scripts/build.ps1` defaults to `:app:assembleDebug`; do not invoke it without explicit non-packaging tasks before Phase 8. Inspect unfamiliar Gradle task graphs before running them.
- **Do not commit or push before Phase 8.** The user authorized final-phase APK packaging, commit, and push, not intermediate commits.
- Do not deploy production backend changes outside an explicitly authorized release/deployment step. Backend deployment must be coordinated with client compatibility.
- Do not force-add ignored APKs, keys, credentials, signing files, or local configuration to Git. Deliver the final APK separately from source commits unless the user explicitly changes this policy.

## Product priorities

- The intended user is not technically confident. Easy navigation is a release requirement: clear labeled actions, large controls, predictable Back/Home behavior, preserved drafts, and no technical setup in ordinary use.
- Every chatbot feature must reuse the existing companion Chat's Mr. Poodles personality, including free-provider fallbacks. See section 3.2.1 of the master plan; task-specific instructions must preserve evidence, accuracy, and uncertainty.
- Recipes, food checks, workouts, and natural-language food logging must use real retrieved evidence where they claim internet sourcing. Never fabricate citations, video links, ingredient facts, or nutrition values.
- Missing evidence is uncertainty, not a clean allergen result. An AI adaptation is not the original published recipe.
- Preserve existing profiles, saved recipes, plans, and food logs through migrations.
- Providers must work without payment details, paid upgrades, or automatic recharge. Email/Google sign-in is acceptable; providers that require phone verification or KYC are excluded. Public marketing claims do not prove the complete signup flow.

## Communication and records

The user prefers terse, clear Hinglish in this session. Keep technical terms and exact commands intact. Write repository documentation, comments, commit messages, and other persistent material in normal English. Record what actually changed and what actually passed; distinguish historical results from current verification.
