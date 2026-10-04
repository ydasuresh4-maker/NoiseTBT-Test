# Noise TBT Test — GitHub APK Build

This project is prepared so GitHub Actions can build the APK automatically.

## Build without Android Studio

1. Create/sign in to GitHub.
2. Create a new repository, e.g. `NoiseTBT-Test`.
3. Upload all files from this folder to the repository.
4. Open the **Actions** tab.
5. Select **Build Noise TBT APK**.
6. Press **Run workflow**.
7. After the workflow finishes, open the run and download the artifact named:
   `NoiseTBT-Test-debug`
8. Extract it and install `app-debug.apk` on the Android phone.

The first test app targets:
- Watch name: `PRO 5_518D`
- MAC: `00:00:00:D1:51:8D`
- BLE service: `16186f00-0000-1000-8000-00807f9b34fb`
- Command characteristic: `16186f02-0000-1000-8000-00807f9b34fb`
- ACK/notify characteristic: `16186f01-0000-1000-8000-00807f9b34fb`

The test notification is:
`TBT TEST 100m -> RIGHT`

Protocol reference:
https://hacktheprotocol.ddns.net/posts/noisefit-ble-reverse-engineering/

This is an experimental third-party app, not an official Noise application.
