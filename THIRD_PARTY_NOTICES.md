# Third-party materials

The application code is MIT licensed. Each dependency retains its own license.

- **AndroidX, Jetpack Compose, Kotlin, kotlinx and OkHttp:** Apache License 2.0. Dependency coordinates are in the Gradle files. The full Apache license is included in `app/src/main/assets/NOTICES.txt` and accessible from the app.
- **Nutrition:** public-domain USDA FoodData Central SR Legacy, April 2018. Exact FDC IDs, food descriptions, sources, and nutrient values are retained in `nutrition.json`. The reproducible selection and extraction scripts are under `scripts/`.
- **Mascot, scenery, gift illustrations and chime:** original project assets. The cozy visual direction is inspired by Cats & Soup; no game artwork, screenshots, music, or code are bundled.
- **Hosted inference:** the service uses Cloudflare Workers AI's free allocation. Llama 3.1 and Llama 4 use Meta's respective model licenses; Qwen3 uses its upstream model license. Models are selected in `backend/src/inference.js`; weights are not redistributed with the APK. Cloudflare and model terms apply to service use.

`scripts/licenses.py` regenerates the bundled dependency notices. Provider availability is documented in `docs/BUILD_STATUS.md`.
