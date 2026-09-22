# Building the Android App

Native Kotlin + Jetpack Compose app in `app/`. No Python, no WebView: it talks to
the router's CGI directly over OkHttp. Toolchain details are in
[`ANDROID_NOTES.md`](ANDROID_NOTES.md).

## Requirements
- Android Studio (its bundled JDK is fine) with the Android SDK for API 37.
- A phone on Android 7.0+ (API 24).

## Build and install from Android Studio
1. `File → Open…` and pick the repository root. Let Gradle sync finish.
2. Enable **USB debugging** on the phone (Settings → About phone → tap
   *Build number* 7×, then Developer options → USB debugging) and plug it in.
3. Pick the phone in the device dropdown and press **▶ Run**.

## Build from the command line
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # or any JDK 17+
.\gradlew.bat :app:testDebugUnitTest    # JVM tests against a fake router
.\gradlew.bat :app:installDebug          # debug build onto a connected phone
.\gradlew.bat :app:assembleRelease       # R8-shrunk release, app/build/outputs/apk/release/
```
On macOS/Linux use `./gradlew` instead.

The release APK comes out unsigned. Sign it with your own keystore (or use
`Build → Generate Signed App Bundle / APK…` in Android Studio) before installing:
```
apksigner sign --ks my-release.jks --out app-release.apk app-release-unsigned.apk
```

## Must-know
- **The phone has to be on the router's Wi-Fi** for live data; the app reaches
  the router at `192.168.1.1`. On mobile data it can't see it.
- On Android 16+ the app asks for the **Local network** permission on first launch.
  Without it every request to the router is blocked.
- Polling runs only while the app is in the foreground.
