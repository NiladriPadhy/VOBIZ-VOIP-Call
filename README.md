# Vobiz VoIP POC

Native Kotlin Android proof of concept for inbound and outbound PSTN calls through
Vobiz using SIP over secure WebSocket and WebRTC audio.

The repository contains:

- `app/` — Android 12+ Jetpack Compose application;
- `backend/` — local TypeScript Answer URL, call-state, and FCM service;
- `DOCS/VOBIZ_ANDROID_POC_DESIGN.md` — approved design;
- `DOCS/SETUP_GUIDE.md` — configuration and run instructions.

## Screenshots

| Keypad | Recents | Settings — status | Settings — backend |
| --- | --- | --- | --- |
| <img src="screenshots/keypad.png" width="200" alt="Keypad screen with the dialer and SIP connected status" /> | <img src="screenshots/recents.png" width="200" alt="Recents screen listing recent outgoing calls" /> | <img src="screenshots/settings-status.png" width="200" alt="Settings screen showing SIP endpoint and backend status" /> | <img src="screenshots/settings-backend.png" width="200" alt="Settings screen showing backend configuration and call recording toggle" /> |

## Local verification

```bash
./gradlew testDebugUnitTest assembleDebug
cd backend
npm install
npm run typecheck
npm audit --omit=dev
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

Do not commit Vobiz credentials, Firebase files, TURN credentials, `.env` files, or
signing keys.
