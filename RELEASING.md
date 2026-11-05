# Releasing Quiet Home, in-app updates and Downloader install

> **Status:** source code is on GitHub at <https://github.com/GabrielAstilla/quiet-home> (public).
> First stable release: **v1.1.0**. No Downloader code has been registered yet: `NNNNNN` below is a **placeholder**.

## 1. How it fits together

```
GitHub repo (PUBLIC)  github.com/GabrielAstilla/quiet-home
 └─ Releases (stable = not draft, not prerelease)
     └─ v1.2.0
         ├─ QuietHome.apk    the universal APK (same file name every release)
         └─ QuietHome.json   metadata: packageName, versionCode, versionName, sha256, minSdk

In-app updater   → GET https://api.github.com/repos/GabrielAstilla/quiet-home/releases   (no token, 60 requests/hour per network)
Downloader code  → https://github.com/GabrielAstilla/quiet-home/releases/latest/download/QuietHome.apk   (stable link, never changes)
```

- **The repository must be public.** The TV and Downloader fetch releases without signing in, and the app contains
  no GitHub token. A private repo would need a separate public download location (for example a public "releases"
  repo or another HTTPS host), and the updater would have to point there instead.
- **One universal APK.** The app has no native code and no ABI or density splits, so one APK works on every
  Google TV and no variant chooser or download page is needed.

## 2. Repository setting

`gradle.properties` contains:
```properties
quietHome.updateRepo=GabrielAstilla/quiet-home
```
It is compiled into `BuildConfig.UPDATE_REPO`. Builds with it empty show "Updates aren't set up for this build yet"
in Settings › About & Updates › Updates, and make no network requests.

## 3. Versioning

In `app/build.gradle.kts`:
```kotlin
versionCode = 2          // integer; MUST increase every release (the updater compares this number)
versionName = "1.1.0"    // shown to people; never compared
```
Tag releases `v<versionName>` (e.g. `v1.2.0`). The tag is only a label: the updater reads the version code from
`QuietHome.json`. If that file is missing, it reads the version code from the downloaded APK itself.

## 4. Signing (important)

Android installs an update **only if it is signed with the same key** as the installed app. Quiet Home checks
this before offering to install, and explains the problem if the keys don't match.

- **In use since v1.1.0:** a dedicated release key, kept outside the project and never committed:
  - key file `~/.android/quiethome-release.jks` (alias `quiethome`, RSA 4096, valid until 2054)
  - passwords in `~/.gradle/gradle.properties` (`quietHome.keystore`, `quietHome.keystorePassword`,
    `quietHome.keyAlias`, `quietHome.keyPassword`)
  - certificate SHA-256 `ca8ce5b3dc07f58771f55d3dcd025802bfb3a1f831abdc3e0e235a06888f63a9`

  **Back up the .jks file and its password together.** Without them no future update can be installed over
  existing installs. If the properties are missing, the build falls back to the debug key, which can't update
  installs made from GitHub releases. Always check before publishing:
  ```sh
  apksigner verify --print-certs app/build/release/QuietHome.apk | grep SHA-256   # must show ca8ce5b3…63a9
  ```
- Builds signed with a different key (for example the earlier debug-key builds) can't be updated in place:
  uninstall them once, then install the release.

## 5. Build the release assets (local)

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest releaseAssets
# → app/build/release/QuietHome.apk
# → app/build/release/QuietHome.json   (includes the APK's SHA-256)
```

## 6. Publish (only when you authorise it)

```sh
git push origin main
gh release create v1.2.0 app/build/release/QuietHome.apk app/build/release/QuietHome.json \
   --title "Quiet Home 1.2.0" --notes-file CHANGELOG-1.2.0.md   # stable (not draft, not prerelease)
```
Check that the stable link serves the new APK:
```sh
curl -sIL https://github.com/GabrielAstilla/quiet-home/releases/latest/download/QuietHome.apk | grep -iE "^HTTP|content-type|content-length"
# expect: 302, 302, then 200 with content-type application/octet-stream and the APK's size
```
For a test build, use `gh release create … --prerelease`: the updater and the stable link both ignore prereleases.

## 7. Register the Downloader code (after publishing)

1. Open <https://go.aftvnews.com> and submit exactly
   `https://github.com/GabrielAstilla/quiet-home/releases/latest/download/QuietHome.apk`.
2. It returns a numeric code (placeholder: `NNNNNN`) and a short link `aftv.news/NNNNNN`.
3. Notes from AFTVnews (checked 2026-11-05):
   - Codes are permanent.
   - Submitted URLs are publicly viewable.
   - A short redirect-info page (sometimes with an ad) appears before the download.
   - The announcement post doesn't describe a way to change a code's destination.

   This is why the code must point at the stable `latest/download` link: it never changes, while GitHub moves
   it to each new stable release. **The same code then installs future releases too.**
4. **Test it on the TV** before sharing the code (see §8). Redirects and the APK content type were checked with
   curl against GitHub's real `latest/download` link pattern. Whether Downloader itself follows them on your
   TV still has to be confirmed in the app.

## 8. Installing on the TCL Google TV with Downloader

1. Install **Downloader by AFTVnews** from the Google Play Store on the TV.
2. Allow it to install apps: **Settings › Apps › Security & restrictions › Unknown sources › Downloader** → On.
   On some firmware it's under *Settings › Privacy › Security › Install unknown apps*.
3. Open Downloader, select the URL field, type the code `NNNNNN` (placeholder) and choose **Go**.
4. Wait for the short AFTVnews redirect page. Downloader then downloads `QuietHome.apk`.
5. Choose **Install** and confirm on Android's installer screen.
6. Choose **Open**, or find **Quiet Home** in the Google TV Apps row. To use it as your Home screen, see
   "Making it the Home screen" in README.md.
7. Optional: in Downloader, choose **Delete** to remove the APK file after installing.

## 9. Later updates

- **In the app:** Settings › About & Updates › Updates › **Check for Updates** › **Download Update** › **Install Update** ›
  confirm on Android's installer. The first time, Android asks you to allow Quiet Home to install apps; the
  Updates screen opens that setting for you. Pinned apps, wallpapers and settings are kept, because the package
  name and signing key are the same.
- **Optional automatic check:** "Check automatically once a day" (off by default). It only shows a notice on
  Home; downloading and installing always need your action.
- **Or with Downloader:** enter the same code again. The stable link always serves the newest stable release.

## 10. What the updater checks before installing

| Check | If it fails |
|---|---|
| Stable releases only (drafts and prereleases skipped), newest by date | — |
| `QuietHome.apk` asset present (or exactly one `.apk`) | "The latest release has no QuietHome.apk" |
| `QuietHome.json` valid: same package, integer versionCode, 64-hex sha256 | "Release metadata …" message |
| versionCode compared **numerically** against the installed app | "Quiet Home is up to date" |
| Download over HTTPS (release builds refuse anything else), progress, cancel, retry | network or timeout message, Retry |
| APK parses, package name matches | "isn't a valid Android app" or "is a different app" |
| APK version code is newer, and matches the metadata | rejected with both numbers |
| SHA-256 matches the metadata | "Checksum mismatch" |
| minSdk ≤ the TV's Android version | rejected |
| Same signing certificate (or a rotated key that includes it) | "signed with a different key …" |
| Install-unknown-apps permission | opens the system setting, with guidance |
| Android's own confirmation screen | Cancel keeps the download for another try |

Rate limiting (60 unauthenticated API requests per hour per IP), a missing or private repository (404), server
errors, no network, timeouts and TLS errors each get a plain-language message. Partial downloads are deleted
on failure or cancel, and leftover APKs are removed at the next start.

## 11. Migration: renamed package (v1.2.0) and the bridge release

v1.2.0 changes the application ID to **`io.github.gabrielastilla.quiethome`**. v1.1.0, the only published release,
used the previous ID. Consequences:

| | v1.1.0 installs (previous ID) | v1.2.0+ (new ID) |
|---|---|---|
| In-app updater | Finds the v1.2.0 release, then **rejects** it: "a different app". Nothing breaks, it just never updates. | Normal updates from v1.2.0 on |
| Downloader code (stable link) | — | The `latest/download/QuietHome.apk` link serves v1.2.0, which installs **next to** v1.1.0 |
| Data | Stays in the old app | Needs Import (below) |

**Bridge release (optional, recommended once):** an update of the *old* package that adds the handover provider, so
the renamed app can import pins and settings directly on the TV.
- Built from the current source with an override; the old ID appears only in this command, not in the source:
  ```sh
  ./gradlew releaseAssets -PquietHome.applicationId=<previous application ID> \
      -PquietHome.versionCode=3 -PquietHome.versionName=1.1.1-bridge
  mv app/build/release/QuietHome.apk  app/build/release/QuietHome-bridge.apk   # NOT QuietHome.apk
  ```
- Signed with the same release key, so it installs over v1.1.0 in place. Tested on the emulator:
  v1.1.0 → bridge, then the renamed app imported its data with *Import settings › From previous Quiet Home*.
- Publish it as an **older-dated or separate** release asset named `QuietHome-bridge.apk`, never as `QuietHome.apk`,
  so the stable link keeps serving the renamed app. Install it with Downloader using that asset's own URL:
  `https://github.com/GabrielAstilla/quiet-home/releases/download/v1.1.1-bridge/QuietHome-bridge.apk`
  (placeholder until published).

**Order when publishing (do these together):**
1. Release `v1.2.0` with `QuietHome.apk` and `QuietHome.json` (new package). It becomes "latest".
2. Optionally release `v1.1.1-bridge` with `QuietHome-bridge.apk` only. Mark it as a **pre-release** so neither the
   stable link nor the new app's updater ever picks it. Users install it once via its direct link.
3. Update the README install steps if the Downloader code is registered (it keeps pointing at the stable link).

## 12. Renaming the GitHub repository (not done)

The repository name `quiet-home` already uses the app's neutral name, so the rebranding needs no repository rename. If you rename it later
(e.g. to `owner/new-name`):
- **Change together, in one release:** `quietHome.updateRepo` in `gradle.properties`, every
  `github.com/GabrielAstilla/quiet-home` link in README.md and RELEASING.md, and the release notes template.
- GitHub redirects the old repository URL, including API and `releases/latest/download/...` requests, as long as no
  new repository is created under the old name. Installed apps and an existing Downloader code would keep working
  through that redirect. Not verified here, because no rename was made; test it with the `curl -sIL` check in §6 after renaming.
- Never create a new repository with the old name after renaming: that breaks the redirect for every installed copy.
