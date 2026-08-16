# Building the Airtel Monitor Android App (Chaquopy + WebView)

This turns the monitor into a **real installable app**. It embeds Python (via
Chaquopy) to run our existing server inside the app, and shows the dashboard in
a WebView. No laptop, no Termux, no CORS problem.

**Files I already prepared** (in this `android/` folder):
- `app/src/main/python/airtel_monitor.py`  — the whole app (server + UI), bound to localhost
- `app/src/main/python/start.py`            — launches the server thread
- `app/src/main/java/com/airtel/monitor/MainActivity.kt` — the WebView host
- `gradle-reference/` — copy/paste snippets for the Gradle + manifest edits

You'll create a fresh project with Android Studio's wizard (so icons, theme and
version numbers match your Android Studio), then drop these files in.

---

## Step 0 — Install
Install **Android Studio** and let it finish downloading the Android SDK on first
launch (accept the default components).

## Step 1 — New project
`File → New → New Project…`
- Template: **Empty Views Activity**  (NOT "Empty Activity"/Compose)
- Name: **Airtel Monitor**
- Package name: **com.airtel.monitor**   ← must match exactly
- Language: **Kotlin**
- Minimum SDK: **API 24**
- Build configuration language: **Groovy DSL (build.gradle)**  ← important, so the
  snippets below match. (If you only see Kotlin DSL, tell me and I'll give `.kts` versions.)

Let the first Gradle sync finish.

## Step 2 — Add the Chaquopy plugin
Open `gradle-reference/` and mirror the three edits:

1. **Project root `build.gradle`** → add the one Chaquopy line
   (see `build.gradle-ROOT.txt`).
2. **`app/build.gradle`** → add the plugin id, the `ndk { abiFilters … }` block,
   the `python { version "3.11" }` block, and the `activity-ktx` dependency
   (see `build.gradle-APP.txt`).
3. **`settings.gradle`** → confirm `mavenCentral()` is in both repo blocks
   (it already is by default; see `settings.gradle`).

Click **Sync Now**. The first sync with Chaquopy downloads Python and takes a few
minutes — that's normal.

## Step 3 — Drop in the Python code
- In the Project view, create the folder `app/src/main/python` (right-click
  `app/src/main` → New → Directory → type `python`).
- Copy **both** `airtel_monitor.py` and `start.py` from
  `android/app/src/main/python/` (this folder) into it.

## Step 4 — Replace MainActivity + manifest
- Replace the generated `app/src/main/java/com/airtel/monitor/MainActivity.kt`
  with the one in this folder (same path).
- Edit `app/src/main/AndroidManifest.xml` to add the 3 permissions,
  `android:usesCleartextTraffic="true"`, and the NoActionBar theme — see
  `gradle-reference/AndroidManifest-additions.xml`.

**Sync** once more. There should be no red errors.

## Step 5 — Run it
Two ways to get it on your phone:

**A) Direct install (recommended)**
1. On the phone: Settings → About phone → tap "Build number" 7× to enable
   Developer options → turn on **USB debugging**.
2. Plug the phone into the PC; accept the "Allow USB debugging" prompt.
3. In Android Studio pick your phone in the device dropdown → click **▶ Run**.
4. The app installs and opens straight into the dashboard.

**B) Build an APK to keep/share**
- `Build → Build Bundle(s) / APK(s) → Build APK(s)`.
- Click "locate" in the popup → copy `app-debug.apk` to the phone → tap to
  install (allow "install from unknown sources").

For a proper shareable release build: `Build → Generate Signed Bundle / APK →
APK`, create a keystore when prompted, choose **release**.

---

## Must-know
- **The phone has to be on the router's Wi-Fi (Rengoku_5_GHz)** for live data —
  that's how the embedded Python reaches 192.168.1.1. On mobile data it can't see
  the router.
- Everything works inside the app: live speed, device list, rename, the cell-lock
  panel, and reboot.
- The app runs the server only while it's open (foreground). Reopen it to resume.

## If Gradle sync complains about versions
Chaquopy 16.0.0 supports Android Gradle Plugin 7.0–8.7. If the wizard created a
newer AGP and sync fails, either:
- set the AGP version to `8.5.2` in the **root** `build.gradle` plugins block, or
- check https://chaquo.com for the newest Chaquopy version and use that number in
  the root `build.gradle` Chaquopy line.

Tell me the exact error text and I'll pin a matching set of versions for you.
