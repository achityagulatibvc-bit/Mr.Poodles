# Connect Mr. Poodles — owner setup

The app uses Cloudflare Workers AI through a Worker binding, with optional Exa/Tavily source lookup and consent-gated Groq fallback. The recipient does not enter keys or choose models. The 0.4.0 integration is built and deployed; see `RELEASE_0.4.0.md` for measured results and unperformed device checks.

The user completed the pre-Phase-8 check-in and authorized the recorded production deployment and APK packaging. Future deployment or credential rotation must remain an explicit release operation, separate from ordinary development checks.

## Free usage

Keep the Cloudflare account on **Workers Free**. Workers AI provides **10,000 neurons/day** without a paid upgrade. On Workers Free, exceeding the provider allocation fails rather than charging for overage. This project does not enable AI Gateway billing or paid-model fallbacks.

The app reserves separate conservative compute budgets:

| Category | Daily request ceiling | Per-minute ceiling | Reserved neurons/day |
| --- | ---: | ---: | ---: |
| Chat | 120 | 30 | 7,000 |
| Recipes, plans and other help | 40 | 15 | 1,800 |

The request ceilings and compute ceilings both apply. The retired image allocation is not transferred to chat or assistance. V2 Workers research also reserves from the existing assistance pool; its separate feature/provider ceilings do not create additional compute. Chat context/output are bounded so 100 normal-sized messages fit the reserved chat budget; a preceding unit test exercises the conservative bound after tool usage. Actual availability still depends on Cloudflare's account-wide allocation and capacity. Other projects on the same account share the provider allocation.

The counter contains category totals only, not message content. Legacy daily windows reset at 00:00 UTC. Existing same-day `quota-v2` records, including historical vision usage, are preserved by active reservations; the ordinary next-day reset still applies. `/v1/status` exposes only active chat/assistance counters, not retired vision or new provider budgets. `Retry-After` and scoped errors tell the app what is exhausted; retries during cooldown do not make another inference request.

## Optional research providers and consent

- Exa is the primary search/extraction provider; Tavily is the fallback. Use ignored `backend/.dev.vars` locally and backend secrets in an authorized release. Preserve existing credentials; `.dev.vars.example` is an empty setup template.
- `FREE_PROVIDERS_VERIFIED` must explicitly attest eligible configured accounts. Email/Google signup is acceptable; exclude payment/card/bank, phone verification and KYC requirements. Keep renewable free plans with no paid upgrade or automatic recharge. Exa's `$0` recharge field is not proof that recharge is disabled.
- In-app **Allow source lookup** discloses food terms sent to Exa/Tavily and relevant requests/preferences sent to Cloudflare. The separate **Also allow Groq if Cloudflare is unavailable** choice starts unchecked. Local pasted-ingredient checks do not require source lookup.
- Server eligibility and user consent are independent gates. V2 uses `allowExternalModel`; v1 uses `allowExternalFallback`. Both default false. Adding a Groq key never opts an old client into external inference.
- Review exact manufacturer hosts in `SOURCE_REGISTRY_JSON`; unknown hosts fail closed. Do not enable page caching without reviewed permission. Missing product labels stay uncertain.

See [PHASE2_BACKEND.md](PHASE2_BACKEND.md) for separate provider request/token/credit budgets, local probes, account measurements and source-quality limits. Sourced recipes, workouts, natural-language food logging and sourced planning are integrated; supported layouts and transformations remain bounded rather than unrestricted adaptations.

## Development checks

Use Node.js 22+ and a Cloudflare account:

```powershell
npm ci --prefix backend
```

From the `backend` directory:

```powershell
npx wrangler login
npm test
npm run check
```

`npm run check` runs `wrangler deploy --dry-run`; it does not deploy production. `wrangler.jsonc` declares the `AI` binding and the SQLite Durable Object. Legacy Workers-only text inference needs no third-party key; source research needs an eligible search provider, and optional Groq needs its own eligible key. No vision model or endpoint remains.

## Coordinated release — requires explicit authorization

The updated `/v1/help` accepts text tasks only. `task: "vision"` and any top-level JSON `image` field return HTTP 400 `invalid_request` before quota reservation or provider calls. V1 bodies over 160,000 UTF-8 bytes return HTTP 413, whether declared by `Content-Length` or discovered while reading; `/v2/research` retains its 6,000-byte limit. Text/SSE response shapes remain compatible, but legacy clients' camera/gallery actions will fail after this backend update is deployed. Their missing `/v1/status.vision` category must not be interpreted as image availability.

Coordinate delivery of the text-only Android client with backend rollout. The new client's research actions also require `/v2/research`; an older backend returning 404 is a real failure. Keep the existing Durable Object identity/storage, app credential and signing continuity unless a separately planned change requires rotation. Do not reset counters during rollout.

Only in an explicitly authorized deployment step, after client compatibility review, run deployment. The 0.4.0 rollout used `node backend/scripts/release.mjs deploy` from the project root: it verifies the account/Worker/client origin before uploading selected configuration through stdin and invoking Wrangler. It preserves the existing app credential.

## Configure the app credential

During authorized provisioning, use the intended Worker origin from the project root. An existing installation already has a credential; do not regenerate or rotate it as an incidental test:

```powershell
python scripts/configure-cloud.py --url https://mr-poodles.YOUR-SUBDOMAIN.workers.dev
```

This writes ignored `.poodles.properties` and `backend/.secrets/app-token-sha256.txt`. Upload the hash from `backend`:

```powershell
Get-Content -Raw .secrets/app-token-sha256.txt | npx wrangler secret put APP_TOKEN_SHA256
```

The APK contains a limited app credential, not account-level credentials. Keep the personal APK private. For a public release, introduce per-user authentication and fair per-user allowances.

## Non-packaging verification

From the project root:

```powershell
.\scripts\build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug')
node --test backend/test/phase4.test.js
python scripts/service-status.py
```

The status call reads the configured backend's counters without consuming an AI request. Verify its target first. Use live tests sparingly because they consume the same free allocation. The feature-probe script is now text-only; select individual cases explicitly:

```powershell
python scripts/test-cloud-features.py --only distress
python scripts/test-recipe-variety.py
python scripts/test-week-plan.py
```

Final Phase 8 packaging uses the approved variant/signing setup after the required user check-in. Then audit the newly produced APK and install it over the existing app with the same signing key. Do not uninstall if saved data must survive. An existing `app/build/outputs/apk/debug/app-debug.apk` is not proof of a new release.

## Privacy and operations

Cloudflare's [Workers AI data policy](https://developers.cloudflare.com/workers-ai/platform/data-usage/) says customer content is not used for model training or service improvements without explicit consent. The backend disables observability and does not use an AI Gateway content log. Durable storage contains allowance counters, not chats. Optional permitted page caching holds only public extracted text in memory, is off by default, and does not cache prompts or profiles. Exa/Tavily and Groq have their own data policies; Cloudflare's policy is not a promise about those services. Photo upload is removed.

Fixed legacy models, sampling parameters and neuron estimates are in `backend/src/inference.js`. Research, retrieval and fallback contracts live in `research.js`, `retrieval.js` and `model-fallback.js`. All provider model paths use the shared Mr. Poodles personality; schema and evidence rules retain priority. Research prose is not an eating verdict, full recipe or nutrition estimate. Application/domain validation remains required.

Rotate app access with `configure-cloud.py --rotate`, upload the new hash, and rebuild. Never put account tokens or app credentials into Git.

References: [Workers AI pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/), [JSON mode](https://developers.cloudflare.com/workers-ai/features/json-mode/).
