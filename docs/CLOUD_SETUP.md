# Connect Mr. Poodles — owner setup

The app uses Cloudflare Workers AI through a Worker binding. The recipient does not enter keys or choose models.

## Free usage

Keep the Cloudflare account on **Workers Free**. Workers AI provides **10,000 neurons/day** without a paid upgrade. On Workers Free, exceeding the provider allocation fails rather than charging for overage. This project does not enable AI Gateway billing or paid-model fallbacks.

The app reserves separate conservative compute budgets:

| Category | Daily request ceiling | Per-minute ceiling | Reserved neurons/day |
| --- | ---: | ---: | ---: |
| Chat | 120 | 30 | 7,000 |
| Recipes, plans and other help | 40 | 15 | 1,800 |
| Label photos | 6 | 6 | 1,000 |

The request ceilings and compute ceilings both apply. Large image requests can consume the image compute allocation before six photos. Chat context/output are bounded so 100 normal-sized messages fit the reserved chat budget; a unit test exercises the conservative bound after tool usage. Actual availability still depends on Cloudflare's account-wide allocation and capacity. Other projects on the same account share the provider allocation.

The counter contains category totals only, not messages or photos. Resets occur at 00:00 UTC. `Retry-After` and scoped errors tell the app what is exhausted; retries during cooldown do not make another inference request.

## Deploy

Use Node.js 22+ and a Cloudflare account:

```powershell
npm ci --prefix backend
```

From the `backend` directory:

```powershell
npx wrangler login
npm test
npm run check
npm run deploy
```

`wrangler.jsonc` declares the `AI` binding and the SQLite Durable Object. No third-party AI-provider key is required. Existing unused provider secrets do not participate in inference.

## Configure the app credential

From the project root, use the Worker origin printed by deployment:

```powershell
python scripts/configure-cloud.py --url https://mr-poodles.YOUR-SUBDOMAIN.workers.dev
```

This writes ignored `.poodles.properties` and `backend/.secrets/app-token-sha256.txt`. Upload the hash from `backend`:

```powershell
Get-Content -Raw .secrets/app-token-sha256.txt | npx wrangler secret put APP_TOKEN_SHA256
```

The APK contains a limited app credential, not account-level credentials. Keep the personal APK private. For a public release, introduce per-user authentication and fair per-user allowances.

## Build and verify

From the project root:

```powershell
.\scripts\prepare-assets.ps1
.\scripts\build.ps1
python scripts/audit-apk.py
python scripts/service-status.py
```

The status call reads counters without consuming an AI request. Use live tests sparingly because they consume the same free allocation:

```powershell
python scripts/test-cloud-features.py --only distress
python scripts/test-cloud-features.py --only image
python scripts/test-recipe-variety.py
python scripts/test-week-plan.py
```

Install `app/build/outputs/apk/debug/app-debug.apk` over the existing app with the same signing key. Do not uninstall if saved data must survive.

## Privacy and operations

Cloudflare's [Workers AI data policy](https://developers.cloudflare.com/workers-ai/platform/data-usage/) says customer content is not used for model training or service improvements without explicit consent. This backend disables observability, stores only allowance counters, and does not use an AI Gateway content log. Photos are resized and stripped of original location metadata before upload.

Models, their sampling parameters, and conservative neuron estimates are in `backend/src/inference.js`. Recipes and plans use schema-constrained JSON. Raw output still goes through application/domain validation.

Rotate app access with `configure-cloud.py --rotate`, upload the new hash, and rebuild. Never put account tokens or app credentials into Git.

References: [Workers AI pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/), [JSON mode](https://developers.cloudflare.com/workers-ai/features/json-mode/).
