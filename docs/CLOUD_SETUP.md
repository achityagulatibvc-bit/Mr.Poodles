# Connect Mr. Poodles (owner setup only)

Your friend does not create accounts, enter keys or choose models. You do this once, then send her the configured APK. AI replies and photo transcription require a working service connection.

## What you need

1. A free Cloudflare account: https://dash.cloudflare.com/sign-up
2. A free OpenRouter account: https://openrouter.ai/
3. One OpenRouter API key. **Do not paste it into chat, Android code, Gradle properties or Git.**

No purchased domain is needed. Use Cloudflare's free `workers.dev` address. Keep the Workers account on the Free plan and do not enable automatic top-ups or paid model fallbacks.

## 1. Create the OpenRouter key

- Visit https://openrouter.ai/settings/keys and choose **Create API Key**.
- Name it `Mr Poodles`. Copy it into your password manager temporarily.
- Do not buy credits for this setup. Only explicitly free model IDs are allowed by the backend.
- In https://openrouter.ai/settings/privacy, keep prompt/completion logging and training opt-ins disabled. The backend also requests `data_collection: deny` and `zdr: true`.

Not every free provider satisfies those privacy settings. Requests fail when no compatible endpoint is available. Check current live availability with the synthetic scripts; see `BUILD_STATUS.md` for measured results.

## 2. Sign in to Cloudflare and deploy the backend

Open PowerShell in `E:\DevChallenge1\backend` (or your own checkout's `backend` folder):

```powershell
npm ci
npx wrangler login
npm run deploy
```

Finish Cloudflare sign-in in the browser. If asked to create a Workers subdomain, choose any available name. The deployment prints a URL like:

```text
https://mr-poodles.YOUR-SUBDOMAIN.workers.dev
```

This URL is public, not a secret. You may send this URL to your coding assistant. `/health` should return the app name and version. Chat is intentionally unavailable until the next steps are complete.

The deployment creates a SQLite-backed Durable Object for a small quota counter. Cloudflare supports these on the Workers Free plan. It stores counters only, not chats or photos.

## 3. Put the provider key in Cloudflare, not the APK

Still in `backend`:

```powershell
npx wrangler secret put OPENROUTER_API_KEY
```

Paste the OpenRouter key **into that terminal prompt**. Wrangler stores it as an encrypted Worker secret. This command creates/deploys a Worker version.

Dashboard equivalent: Cloudflare → Workers & Pages → `mr-poodles` → Settings → Variables and Secrets → Add → **Secret**, name `OPENROUTER_API_KEY`, paste the value, Deploy.

## 4. Create the app's limited access token

Open PowerShell in `E:\DevChallenge1` and replace the example URL:

```powershell
python scripts/configure-cloud.py --url https://mr-poodles.YOUR-SUBDOMAIN.workers.dev
```

This creates two ignored local files:

- `.poodles.properties`: your Worker URL and a random **app access token**. This is not your OpenRouter key.
- `backend/.secrets/app-token-sha256.txt`: only the SHA-256 hash of that app token.

Then, from `backend`, upload the hash without printing either credential:

```powershell
Get-Content -Raw .secrets/app-token-sha256.txt | npx wrangler secret put APP_TOKEN_SHA256
```

The app token is bundled into this personal APK. It can be extracted by someone with the APK. Its authority is deliberately limited: only fixed free models through this backend, at most **45 attempted requests per UTC day and 8 per minute**, shared by all copies. It cannot access your provider account. Keep this personal APK private. To distribute publicly, add proper per-user enrollment and authentication.

## 5. Build the configured APK

From the project root:

```powershell
.\scripts\build.ps1
python scripts/audit-apk.py
```

Result: `app/build/outputs/apk/debug/app-debug.apk`.

The app shows no URL, model picker or key entry. If `.poodles.properties` was absent during compilation, that APK is a UI preview and cannot connect. Rebuild after configuring it.

## 6. Check the connection before giving it to her

```powershell
python scripts/test-cloud.py
```

This uses only the restricted app token. It sends one non-personal “Hello” request, consuming one free request. It never prints keys. A failure does not trigger a paid fallback or weaken privacy settings.

Then install the APK and review the welcome privacy notice. Check a greeting, a recipe, a profile change, a known label, and a selected photo. Measure response times; a free tier is not a latency guarantee.

Install over the earlier app using the same debug signing key to preserve her profile and food logs. Do not uninstall unless you intend to erase private records.

## Limits and operations

- OpenRouter currently documents 50 free requests/day without credit purchases and 20/minute. The backend stays below that at 45 attempts/day and 8/minute. Usage elsewhere on your OpenRouter account still counts against the provider's shared limit.
- Failed/blocked attempts consume the backend quota to prevent retry storms. The app does not silently retry requests or buy credits.
- The backend currently chooses `inclusionai/ling-3.0-flash-sante:free` for text/chat and JSON tasks, and `qwen/qwen3.8-27b:free` for images. The original NVIDIA free endpoints failed the required no-training/no-retention routing checks. Exact IDs live in `backend/src/worker.js`, not in any app settings screen. JSON is checked by the app; these free endpoints do not advertise `response_format` support.
- Free model availability can change. When changing a model, keep the explicit `:free` variant, confirm its open-weight license and modalities, and retest. Never replace a free ID with a paid base ID by accident.
- Your Worker has no deliberate request-body or response-content logs. OpenRouter/Cloudflare still handle request metadata according to their policies. Conversations sent for replies leave the phone.
- To revoke this APK's access, change/delete `APP_TOKEN_SHA256`. To rotate, run `configure-cloud.py --url YOUR_URL --rotate`, upload the new hash, rebuild and replace the APK. Old copies lose service access.
- Revoke/replace `OPENROUTER_API_KEY` in Cloudflare if the provider key leaks. It never belongs in `.poodles.properties`.

## Troubleshooting

- `/health` fails: deployment or URL is wrong.
- Connection needs an update: app token and Worker hash do not match, or the APK predates configuration.
- Connection unavailable: check Worker secret names, OpenRouter key validity, model availability and privacy-compatible free endpoints. Do not loosen privacy rules automatically.
- Free limit: wait for quota reset. Additional API keys do not expand OpenRouter's account-wide allowance.
- A build passes but a phone keyboard still jumps: report screen, focused field, Android version and whether the keyboard is visible. Unit/UI-host tests do not fully simulate an OEM keyboard.
