# Research backend contract and setup — Phase 2 foundation, Phase 4–6 integration

Status (2026-10-03): **Phase 2 complete** after recorded implementation/live checks and the user's confirmation that the outstanding account review is done. Account details not exposed by APIs are user-confirmed, not independently inspected. The 54 backend tests, preceding production Wrangler dry run, bounded provider/fallback probes and partial automated account audit are recorded in `IMPLEMENTATION_PLAN.md`. No production deployment has taken place.

Release update (2026-10-04): **0.4.0 / code 7 is built and its backend is deployed after explicit Phase 8 authorization.** Android recipe, food-check, workout and food-log services consume this transport; planner discovery calls the recipe service. Supported parsers/transformations remain bounded. Production research and companion probes pass; signing continuity is verified. Physical-device acceptance remains unperformed. Verification/account measurements in the Phase 2 history below remain historical. See [the current release record](RELEASE_0.4.0.md).

## Compatibility and phase boundary

- Existing clients continue to use `POST /v1/help`, `GET /v1/status`, and `/health`. Text and SSE response shapes, fixed catalog schemas and legacy quota keys remain compatible. Vision and its status category are intentionally retired in the local Phase 4 backend; this requires a coordinated client/backend release.
- `composePersonality` applies the existing companion voice to every legacy task and every new research task. Task/schema rules and explicit uncertainty remain mandatory.
- `POST /v2/research` remains an additive research-preparation API, not a finished recipe, food verdict, workout, intake entry or plan. `SourcedFoodService`/`SourcedFoodRules`, `SourcedWorkoutService` and `FoodLogging` parse retained pages locally. `PlanningRules` validates sourced-library previews; planner discovery currently uses research task `recipe`, while food logging uses `food_log`.
- Legacy text/chat/structured calls can use explicitly eligible Groq after a primary Workers AI failure, before any stream is delivered, **only when the client explicitly sends `allowExternalFallback: true` after appropriate provider consent**. Existing clients omit this and remain Cloudflare-only. A Groq chat fallback is buffered and emitted in the existing SSE shape. Legacy application quota refusals do not trigger fallback or bypass their limits.
- `/v1/status` exposes only active chat/assistance counters; it is not a dashboard for retired vision or new provider budgets. Active reservations preserve same-day historical buckets in `quota-v2`, including vision; ordinary next-day rollover still applies. Removing the vision allocation does not increase active budgets. No provider key, prompt or article text appears in budget records or status responses.
- The 0.4.0 backend is now deployed. Production `/v2/research`, authenticated status and text companion checks passed; an old backend's missing research route must still be treated as a real error, never fabricated evidence.
- The local `/v1/help` rejects `task: "vision"` and every top-level JSON `image` value, including null/false/empty values, with HTTP 400 `invalid_request` before quota reservation or inference. Declared or streamed bodies over **160,000 UTF-8 bytes** return HTTP 413; the v2 cap remains 6,000 bytes. No vision entry remains in `MODELS` or reservable quota limits. Old clients' photo actions fail only after this update is actually deployed. Coordinate the text-only Android update with explicit future deployment authorization; preserve Durable Object identity/storage and signing continuity.

### Current feature integration and consent

- Food and recipe workspaces share the context-above/chat-below layout. Source lookup is optional; local pasted-ingredient checks require neither search nor model inference. Dish/product names and approved public source URLs use retrieval after consent. Packaged-food lookup asks for brand, variant and country; typical recipes do not verify the actual dish or product.
- **Allow source lookup** discloses Exa/Tavily search and relevant request/preferences sent to Cloudflare. The separate **Also allow Groq if Cloudflare is unavailable** choice starts unchecked. Both stored consent flags default false for older profiles. Server account eligibility is a separate gate; provider keys alone never supply user consent.
- Recipe integration parses explicit ingredient quantities, servings and methods from retained page text. Unsupported/incomplete layouts are rejected. Supported serving edits and limited explicitly cited equal-amount no-cook substitutions preserve attribution, distinguish adaptations and keep nutrition unknown. Arbitrary conversational adaptation is not complete. Newly sourced ingredients/methods are not gated by the legacy catalog; legacy records remain supported.
- Food outcomes distinguish **Avoid**, **No listed conflict found** and **Need more information**. Incomplete/conflicting labels, ambiguous identity, missing aliases and cross-contact cannot become unconditional permission to eat. Research message prose remains non-authoritative. Actual Phase 4 acceptance and unverified device/live cases belong in the master plan.
- Workout parsing requires recognized article blocks covering warm-up, main movements and cooldown, with published dose information. Limited local edits are labelled programming changes. Related video lookup sends terms to YouTube; client validation requires a named movement match and labels the result metadata-only, not a watched follow-along.
- Food logging deterministically distinguishes recognized eaten statements from questions, parses comparable nutrition blocks and prepares local durable operations. Unknown calories require explicit save. Backend requests never write the diary. Source identity, number parsing and SavedState reconciliation have open review findings; the existence of receipts is not a complete end-to-end retry guarantee.
- Planning previews select distinct suitable sourced recipes and protect intervening calendar/profile edits. Bounded discovery is not a general conversational planning model. Current repeated-candidate starvation is recorded in the release review.
- Feature-visible replies are largely local templates, not the backend research `answer.message`. Shared personality on both model providers therefore does not establish final cross-feature conversational quality. Current chat-history revocation/restore limitations are also recorded in the release review.

## Request

Use the existing app access token in the `Authorization: Bearer ...` header. Do not put provider keys in Android or in requests from Android.

```json
{
  "requestId": "client-request-identity",
  "profileRevision": 3,
  "task": "recipe",
  "subject": "overnight oats",
  "constraints": {
    "restrictions": [{"name": "Milk", "kind": "Allergy", "aliases": "whey, casein", "notes": "User-reported"}],
    "equipment": ["Fridge"]
  }
}
```

- Tasks: `recipe`, `food_check`, `workout`, `food_log`, `plan`.
- Optional identity fields: `brand`, `variant`, `country`. Optional `url` must be HTTPS and match the operator-reviewed source registry for that task.
- Optional constraints: restriction name/type/aliases/notes, diet, equipment, experience, injuries, duration minutes. Allergy, intolerance and suspected triggers remain distinct.
- Optional `allowExternalModel` defaults false and enables Groq only with explicit client consent plus verified server-side provider eligibility. The Android DTO defaults false; integrated research services pass the stored external-model choice. Existing clients remain opted out. No existing companion context is silently sent to a new model provider by enabling a server key. The current Android legacy companion transport still omits `allowExternalFallback` and remains Cloudflare-only.
- Do not send the full profile, companion history, names, comfort memories or unrelated private events. Unknown top-level/constraint fields are rejected. Search query construction uses only subject/product identity plus a fixed task suffix; health constraints go only to the model as data.
- Body limit: 6,000 bytes; subject 160 characters; bounded field and array lengths. Request parsing has a five-second deadline.
- `requestId` is not a billing deduplication token. Every research attempt can consume its own reserved provider allowance. Intake operation idempotency remains the separate Phase 1 contract.

## Response and evidence

The response contains `apiVersion: 2`, echoed request/profile identity, a new retrieval `snapshot`, optional video metadata, research `answer`, model provider name, and explicit limitations. The shared example used by both test suites is `app/src/test/resources/research-v2-response.json`; its content and URLs are synthetic fixtures, not live retrieval results.

- Search metadata is discovery only. Exa `/contents` or Tavily `/extract` supplies the actual text; search snippets, generated summaries and answer fields are not evidence.
- Each source has a local source ID, URL, title, optional author/publication date, publisher host, retrieval timestamp, content hash, extracted text, kind, cached flag and `completeness: unverified`.
- A lookup's snapshot ID is generated server-side and binds source IDs to that request. Client-supplied snapshots are not accepted.
- Source claims must contain exactly one verbatim quote, used as both claim text and evidence excerpt, with a matching snapshot/source ID. An invented ID, a different snapshot, a fabricated quote or a paraphrase presented as a quotation fails validation.
- AI adjustments are separately tagged and have no source evidence. They are not verified facts or nutrition estimates. The system prompt forbids invented quantities/nutrition in these adjustments. Current feature parsers/validators use retained extracted text and supported transformations rather than treating model adjustments as finished recipes, workouts or nutrition estimates.
- The model sees task-selected windows of up to 600 characters per extracted source; up to 12,000 characters per source remain in the response. Source count/window length is reduced as needed to fit a conservative 7,500-token Groq reservation, preserving personality and constraints. An input that cannot fit fails with 413. Both providers receive the research schema in their provider-specific format. This is not a full recipe/workout endpoint; feature validation must check adequate complete content. Phase 4 parses the retained page text, not just the model's short window.
- Source-claim text is rebound to its exact verified evidence quote before validation, discarding unsupported model paraphrases. Multiline quotes are allowed, control characters remain rejected. Invalid model output shape is an evidence failure, not an invalid client request. Model message prose is not independently evidence-validated: live samples sometimes put facts there despite the acknowledgement-only prompt. Do not use that prose as an eating verdict or authoritative nutrition/recipe data.
- Exact quote matching verifies attribution, not the page's truth, current product identity, completeness, author identity, or medical suitability. Neither a quoted page nor the model's conversational message is an eating verdict.
- YouTube returns only a real API video ID/title/channel with a basic subject/title match. Every result is a `TECHNIQUE_REFERENCE`; it has not been watched and is not an exact-session claim. Empty/invalid/unrelated metadata is omitted. The video may later become unavailable.
- Android independently checks request/profile/snapshot identity, quote attribution, source URLs and video format before returning the DTO. No feature state or user data is persisted by the transport itself.

## Retrieval and egress boundaries

- Only fixed HTTPS API endpoints can receive provider credentials. HTTP redirects from those APIs are rejected; credentials are never forwarded to a redirect destination.
- The Worker does **not** fetch arbitrary publisher pages directly. It sends approved public URLs to the configured extraction provider. That provider owns its internal crawl/redirect implementation. The adapter accepts only a returned URL equal to the requested normalized URL; it cannot attest to hidden provider-side redirect behavior.
- Source URLs reject IP literals (including normalized numeric/IPv6 forms), local/internal names, userinfo, nonstandard ports, non-HTTPS schemes, control characters, backslashes and credential-like query parameters.
- Exact reviewed host matching additionally excludes attacker-controlled lookalikes and arbitrary DNS-rebinding hostnames. No wildcard source hosts are supported. This constrained source coverage is deliberate; an approved host is still not proof of a product match or a claim's accuracy.
- Baseline recipe/workout/nutrition hosts are in `retrieval.js`. **Manufacturer hosts must be explicitly configured and reviewed before live food-check acceptance.** An unsupported source remains unavailable, never an all-clear assessment. Do not turn ordinary app settings into a provider/source setup form.
- Provider JSON is capped at 256,000 bytes; extracted pages at 12,000 characters, with short/empty extraction rejected; candidate pages at three. Provider HTTP calls have ten-second deadlines, Workers model calls fifteen seconds, and v2 research has a 45-second overall deadline.
- Cancellation is terminal and does not trigger fallback. An uncooperative Workers AI binding may still finish remotely after the deadline, so reservations are not refunded on timeout/cancellation.
- Retrieved content stays in JSON user/data messages, never system instructions. There are no model tools, arbitrary fetch instructions or provider keys in the prompt. Tests verify this boundary and reject invalid resulting evidence; they do not prove that a live model is immune to every prompt injection.

## Free-provider eligibility and limits

No new provider is enabled merely by having a key. `FREE_PROVIDERS_VERIFIED` must explicitly list each eligible configured provider. This is an operator attestation **after** actual signup/account checks, not evidence that the agent performed those checks.

Before enabling a provider, verify the actual account:

1. Email/Google sign-in completed without payment/card/bank information, phone verification or KYC. Skip a provider that requires any excluded step.
2. Renewable free plan active; no paid upgrade, pay-as-you-go or automatic recharge. For Exa, `$0` is not an off switch for recharge; disable recharge explicitly.
3. Confirm the account's actual allowance/reset schedule and other consumers. Local app counters do not observe unrelated applications using the same account. Use provider-side free caps and reduce/partition app allocations before sharing a provider account; do not rotate keys/accounts to bypass limits.
4. Confirm source retention/terms and provider data handling before permitting caching or sending real personal constraints. Live probes should use synthetic requests first.

Implemented provider order: Exa then Tavily for search/extraction; Workers AI then Groq for research synthesis; YouTube Data API for video metadata. SerpApi and Jina remain optional candidates, not implemented or configured fallbacks.

| Provider | App ceilings | Reservation per actual attempt |
| --- | --- | --- |
| Exa | 10 requests/minute; 2,000 requests and $8/month | Instant search: 4,000 micro-USD; one text extraction: 1,000 micro-USD |
| Tavily | 10 requests/minute; 800 requests and credits/month | One credit for basic search; conservatively one credit per single-URL basic extract |
| Groq | 25 requests and 7,500 tokens/minute; 900 requests and 180,000 tokens/day | UTF-8 serialized input/schema byte bound + max output tokens + 512-token margin |
| Workers AI research | 15 requests/minute; 100 requests and 1,800 neurons/day | Existing conservative neuron estimator; also reserves from the same v1 assistance compute pool |
| YouTube | 5 requests/minute; 80 requests/search units/day | One search request/unit |

Each research feature also has an app ceiling of 100 requests/day and 10/minute. These are ceilings, not guaranteed free daily capacity. Provider budgets and the shared legacy assistance allowance can stop work much earlier. Oversized Groq token reservations are rejected rather than underestimated.

Counters use UTC minute/day and calendar-month windows. These are local app windows; actual provider reset calendars (especially YouTube/account-specific limits) must be checked during setup. Provider refusals and `Retry-After` remain authoritative, and free-only account configuration prevents a local-window mismatch from becoming paid overage.

Reservations and cooldowns use atomic Durable Object transactions in `provider-budgets-v1`. V2 Workers AI reservations also debit `quota-v2` assistance in the same transaction; the existing 7,000-neuron companion reserve cannot be consumed by research. Failed/ambiguous attempts stay charged conservatively.

Only one attempt per eligible provider per operation is made; no same-provider retry loops. Provider failure opens a persisted cooldown; later calls fail fast until it expires. `Retry-After` seconds and HTTP dates are honored without early jitter, including long monthly waits. No automatic response-cache expiry or retry resets budget history. New `research_*` error scopes are understood by Android, while old scope names remain valid.

## Cache policy

Caching is off by default. `SOURCE_REGISTRY_JSON` can set `cacheSeconds` only after source/provider permission is reviewed. Example shape (placeholder domain, not a pre-approved source):

```json
[{"host":"www.actual-manufacturer-domain.com","kind":"manufacturer","cacheSeconds":0}]
```

Kinds: `recipe`, `workout`, `nutrition`, `manufacturer`. Maximum TTL is 900 seconds for manufacturer data and 86,400 for other pages. A later explicit entry overrides a default entry for the same host/kind.

The per-isolate cache has at most 32 public pages, keyed by a URL/policy hash. It stores no query, profile, history or generated answer. A cache hit preserves its original retrieval timestamp and receives a new request snapshot. Cache loss affects cost/availability, not correctness. No stale page is served after TTL expiry.

## Local setup and live checks

1. Complete the account checks above. Keep a private record of eligibility and actual quota settings; never put keys in this document or chat.
2. For a new setup, copy `backend/.dev.vars.example` to ignored `backend/.dev.vars` and enter only eligible keys and verified providers. Preserve an existing file. Configure reviewed source hosts, including manufacturer sources. This workspace now has four keys and source entries; never overwrite them with the template.
3. Start the development backend with the existing `npm run dev --prefix backend`. Confirm its actual AI binding mode and provider allowance before sending inference. Do not run the production `deploy` script.
4. In the terminal environment, set `POODLES_TEST_URL` to the dev server origin and `POODLES_TEST_TOKEN` to its matching app token. Keep shell history and output free of literal secrets.
5. Run `npm run check:live --prefix backend`. The script sends **one** synthetic overnight-oats request, validates references, and prints only latency, source sizes/cache counts and claim counts. Without configuration it exits with `PENDING` and makes no request. It never prints raw responses, keys, headers or private profile content.
6. Manually inspect actual source quality for recipe quantities/method, exact manufacturer product/country/allergen statements, workout article quality, and video relevance. Run similarly bounded synthetic checks for each configured adapter and its fallback. Record measured latency, provider/model, source completeness, failures and the actual signup constraints in the master checkpoint. Quote matching alone is not a quality pass.
7. Compare representative real replies, including forced free-provider fallback, with the existing companion voice; include hostile retrieved-text cases and truthful uncertainty. Bounded samples now have results in the master plan; this does not prove universal model compliance.

### Self-contained bounded local runner

`backend/scripts/local-live.mjs` reads the ignored configuration and generates the app token/hash in memory. This runner does not require `POODLES_TEST_URL`, `POODLES_TEST_TOKEN` or a stored app-token hash. It never prints keys; it does print public source excerpts and synthetic model replies for quality review.

```powershell
node backend/scripts/local-live.mjs inventory
node backend/scripts/local-live.mjs recipe-exa --next-minute
node backend/scripts/local-live.mjs recipe-tavily --next-minute
node backend/scripts/local-live.mjs recipe-cloudflare --next-minute
node backend/scripts/local-live.mjs product --cloudflare --with-groq --next-minute
```

Other cases: `workout`, `nutrition`, `plan`, `voice-groq`, `voice-cloudflare`, `injection-groq`, `injection-cloudflare`, `fallback-groq`, `fallback-tavily`. Run cases individually, respecting `Retry-After`; `--next-minute` is not permission to ignore a longer cooldown. For research cases, `--cloudflare` selects Workers AI and `--with-groq` additionally permits Groq fallback. Fallback probes inject a primary failure and call real secondary services; they are explicitly labeled synthetic.

- Both live Wrangler configurations use the same local-only Worker name and `.wrangler/live-state` Durable Object counters. Do not delete/reset this directory to avoid limits.
- For Cloudflare probes, omit the `unstable_dev` `local` option: omitted means local application execution with configured remote AI bindings. Explicit `local: true` disables remote bindings; `local: false` moves application execution to remote development. The corrected runner uses neither of those for AI probes. Remote AI calls still consume the account's real free allowance.
- `scripts/probe-worker.js` is development-only; production configuration never references its synthetic routes. No production deploy is performed.
- Local reviewed source additions are `www.maggi.in` (manufacturer) and `tools.myfooddata.com` (nutrition); all cache TTLs remain zero.

Observed source limitations: MAGGI extraction contains nutrition/FAQs but no complete ingredient/advisory label; missing evidence produces uncertainty, not permission to eat. ACE search can return an exercise index instead of technique instructions. Short model windows can omit retained page facts or end mid-sentence. Actual feature generation must use adequate evidence and domain validation. YouTube returned a relevant real title/channel, retained as a technique reference only.

### Bounded account audit

```powershell
node backend/scripts/account-audit.mjs
node backend/scripts/local-live.mjs limits-groq --next-minute
```

The first command performs one read-only Tavily `/usage` GET, with a ten-second timeout, 32 KB response limit, no redirects or retries, and numeric/known-plan output only. The second makes one synthetic Groq call through the existing persistent budget and outputs only documented limit/reset headers. It consumes inference allowance; do not repeat successful audits unnecessarily.

Measured Tavily account: Researcher plan, 1,000 credits, 1 used, 999 remaining; pay-as-you-go usage 0, pay-as-you-go limit null. Null does not establish whether pay-as-you-go is enabled. Measured Groq headers: 1,000 requests/day and 8,000 tokens/minute; relative resets 1m26.4s and 7.537s for that response. Other limit dimensions and shared usage remain unverified. Exa's administrative usage API requires separate service-account access; the ordinary search key is not a substitute. Full details and limitations are in the master checkpoint.

Backend dry-run checks are local compilation/binding checks; their `Total Upload` line does not mean a production upload occurred. No APK/AAB packaging, production deployment, commit or push is authorized here.

## Focused Phase 4 backend verification — 2026-10-04

`node --test backend/test/phase4.test.js`: **13 passed, 0 failed/cancelled/skipped**. The resumed tests preserve the preceding eight checks and add exact-boundary acceptance, retired quota rejection, UTC rollover, nonstream task compatibility and exhausted-assistance fallback prevention.

- Authenticated retired image requests and image fields on surviving v1/v2 routes cause no quota request, storage write, Workers AI inference or external-provider call. No model or reservable quota entry remains for vision.
- Declared/actual oversized and streamed bodies are rejected; reading an oversized stream is cancelled. A valid text JSON body padded to exactly 160,000 UTF-8 bytes is accepted. Field limits still apply independently.
- Active requests preserve same-day historical vision/chat usage; rejected requests do not rewrite even prior-day state. A valid next-day request retains the ordinary UTC reset. V2 compute shares the assistance reserve and cannot reset exhausted history.
- Text/structured/recipe/plan retain the v1 `text` envelope and shared personality. Chat retains normalized SSE; Groq receives the same prompt only with explicit consent. Exhausted app assistance prevents both primary and consented fallback inference.

All provider responses are mocked and Durable Object storage is a cloned in-memory fixture, not deployed storage. No live calls, Android tests, deployment, packaging, commit or push were performed for this focused review. Full Phase 4 acceptance is owned by the main implementation checkpoint. The current Python feature script has removed its image probe; its surviving legacy checks still do not validate the later Android source parsers, planner or durable food-log integration.

## Historical verification recorded in Phase 2

- Phase 2's final `npm test --prefix backend`: **54 passed**, 0 failed/cancelled/skipped; includes four account-audit tests plus the existing research/legacy checks. This is not a current Phase 4 full-suite result.
- Preceding `npm run check --prefix backend`: passed (`wrangler deploy --dry-run`); 59.69 KiB / gzip 17.15 KiB, no deployment. Not rerun for the development-only account audit.
- Historical `scripts/build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug')`: 83 passed, 0 failed/errors/skipped; lint 0 errors and 10 existing advisories. Android was unchanged and not rerun during the live-verification resume.
- Earlier `npm run check:live --prefix backend` had no development URL/token and made no live request. The self-contained local runner now supplies actual live results: Groq-backed research cases 2,438–9,805 ms; accepted Cloudflare research cases 10,079–18,731 ms; combined product fallback 29,450 ms. See the master plan for every failed probe, fix, retry and exact result.
- Both models passed the one hostile-text fixture after schema correction; both produced warm ordinary-distress voice samples. Injected-primary Groq/Tavily fallback calls passed; a real rejected Cloudflare product response also fell back to Groq. Invalid primary citations remain rejected.
- User-reported signup without payment/phone/KYC and local keys are recorded. The user then confirmed no payment method was added and no additional option appeared. Finally, in response to the remaining account-review blocker, the user said **“done hai”**. This closes the user-assisted review gate as **user-confirmed**, not independent billing-dashboard verification. No additional numeric limits or account settings are invented. Phase 2 is complete; the source/model limitations above remain applicable to later feature work.

Official API contracts/pricing re-read on 2026-10-03: [Exa search](https://exa.ai/docs/reference/search), [Exa pricing](https://exa.ai/pricing), [Tavily search](https://docs.tavily.com/documentation/api-reference/endpoint/search), [Tavily credits](https://docs.tavily.com/documentation/api-credits), [Groq compatibility](https://console.groq.com/docs/openai), [Groq structured outputs](https://console.groq.com/docs/structured-outputs), [YouTube search](https://developers.google.com/youtube/v3/docs/search/list). Public documentation is not verification of this user's signup flow or account.
