# Play Store submission materials

Everything here is prep for a Play Console listing — nothing in this folder gets built into the app.

- **[listing.md](listing.md)** — title, short/full description, category, screenshots to use, content rating and data safety answers. Paste directly into Play Console.
- **[../privacy-policy.html](../privacy-policy.html)** — served live at https://lynksdomain.github.io/retrotube/privacy-policy.html via GitHub Pages (enabled on this repo, building from `docs/` on `main`). Use that URL in Play Console's privacy policy field.
- **[../assets/icon_512.png](../assets/icon_512.png)** — 512×512 app icon for the store listing.
- **[../assets/feature_graphic.png](../assets/feature_graphic.png)** — 1024×500 feature graphic.
- **[../screenshots/](../screenshots/)** — the same screenshots used in the main README, reused here as Play Store listing screenshots.

## What still needs a human

- A Google Play Console developer account ($25 one-time fee).
- Uploading a signed release build (App Bundle preferred — `./gradlew :app:bundleRelease` with the same release signing config already used for GitHub releases) and setting up Play App Signing.
- Filling in the content rating questionnaire and data safety form (answers drafted in `listing.md`).
- Choosing a release track (internal testing is the fastest way to get the app "Play Protect certified" without going public).
