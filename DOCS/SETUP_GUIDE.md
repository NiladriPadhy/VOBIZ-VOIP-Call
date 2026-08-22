# Vobiz Android POC setup guide

## 1. Prerequisites

- JDK 17;
- Android SDK 36 and an Android 12+ physical device;
- Node.js 22+ and npm;
- a secure tunnel such as ngrok or Cloudflare Tunnel;
- one Vobiz number in E.164 format;
- one dedicated Vobiz SIP endpoint;
- a dedicated Firebase Android project for `com.enetro.vobizvoip`;
- a separate PSTN phone for final inbound/outbound verification.

## 2. Prepare backend secrets

```bash
cd backend
cp .env.example .env
openssl rand -hex 32
openssl rand -hex 32
```

Use separate generated values for `DEVICE_TOKEN` and `WEBHOOK_TOKEN`.

Populate:

- `PUBLIC_URL` with the tunnel's HTTPS origin;
- `SIP_ENDPOINT` with `sip:<endpoint-user>@registrar.vobiz.ai`;
- `VOBIZ_AUTH_ID` and `VOBIZ_AUTH_TOKEN` only in this backend file;
- `FIREBASE_SERVICE_ACCOUNT_PATH` with the local Firebase Admin key location.

Do not set a server-wide caller ID. Each device supplies its own Vobiz DID in the
app. Inbound CLI uses the webhook `To` number that was actually called.

Never put the Vobiz Auth ID or Auth Token in Android configuration.

## 3. Configure Firebase

1. Create a dedicated Firebase project.
2. Add an Android application with package `com.enetro.vobizvoip`.
3. Download `google-services.json` to `app/google-services.json`.
4. Generate a Firebase Admin service-account key.
5. Save it as `backend/serviceAccountKey.json`.
6. Do not commit either file; both locations are ignored.

The Google Services Gradle plugin is applied only when `app/google-services.json`
exists, so the Android project can still compile before Firebase is configured.

## 4. Run the backend

```bash
cd backend
npm install
npm run dev
```

Expose port 3000 using the selected tunnel. For example:

```bash
ngrok http 3000
```

Update `PUBLIC_URL` whenever the tunnel URL changes, then restart the backend.

Verify:

```bash
curl https://your-tunnel.example/health
```

## 5. Configure the Vobiz Voice Application

Set these URLs using the `WEBHOOK_TOKEN` from `backend/.env`:

```text
Answer URL:
https://your-tunnel.example/webhooks/vobiz/<WEBHOOK_TOKEN>/answer

Hangup URL:
https://your-tunnel.example/webhooks/vobiz/<WEBHOOK_TOKEN>/hangup

Method: POST
```

Attach the Vobiz number to this Voice Application and confirm that the dedicated
SIP endpoint is active.

Do not expose the real webhook token in screenshots or shared logs.

## 6. Build and install Android

```bash
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app and enter:

- SIP endpoint username and password;
- `wss://registrar.vobiz.ai:5063/`;
- SIP domain `registrar.vobiz.ai`;
- tunnel HTTPS URL;
- the same `DEVICE_TOKEN` as the backend;
- Vobiz caller ID in E.164 format;
- TURN URL and credentials when supplied.

Credentials are encrypted at rest with an Android Keystore AES-GCM key.

Grant microphone, notification, and nearby-device/Bluetooth permissions.

## 7. Test order

1. Confirm the app shows `Ready`.
2. Place an outbound call to a separate PSTN phone.
3. Confirm ringing, answer, two-way audio, mute, speaker, DTMF, and hangup.
4. Call the Vobiz number while the app is foregrounded.
5. Repeat while the app is backgrounded.
6. Force-stop/terminate the app and repeat after confirming FCM delivery behavior.
7. Repeat on cellular data.

Do not test by calling the configured Vobiz caller ID from itself.

## 8. Current POC constraints

- The native SIP implementation intentionally covers the Vobiz call path, not the
  complete SIP RFC surface.
- Live Vobiz interoperability must be validated with sanitized SIP traces.
- The backend holds pending calls and FCM installation IDs in memory; restarting it clears them.
- One endpoint/device and one concurrent pending call are the supported POC target.
- A TURN service is required before restrictive-network behavior can be accepted.
- Android full-screen call notifications remain subject to OS and Play policy.
- A force-stopped Android app cannot receive FCM until the user launches it again;
  this is an Android platform restriction, not a Vobiz behavior.
