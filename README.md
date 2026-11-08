# Greenback Terminal apps

Android (APK) and Windows (installer) apps for https://greenbackterminal.com.
Both apps open the live website in their own window, so website updates appear in the apps automatically.

Build: **Actions → Build apps → Run workflow** (enter a version like `1.0.0`).
The finished files are published under **Releases**, which the website's Download page links to:

- `releases/latest/download/GreenbackTerminal.apk`
- `releases/latest/download/GreenbackTerminal-Setup.exe`

Required repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`.
