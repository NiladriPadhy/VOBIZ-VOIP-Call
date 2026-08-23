# Vobiz Android POC setup guide

For Android Settings values and app-to-app contracts (dial, call state, call
log) see [CONFIGURATION_AND_INTEGRATION.md](CONFIGURATION_AND_INTEGRATION.md).

## 1. Prerequisites

- JDK 17;
- Android SDK 36 and an Android 12+ physical device;
- Node.js 22+ and npm;
- a secure tunnel such as ngrok or Cloudflare Tunnel;
- one or more Vobiz numbers in E.164 format;
- one dedicated Vobiz SIP endpoint per Android device;
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

Both files are mandatory for inbound calling in the background or terminated
state. Without `app/google-services.json` the device never obtains a Firebase
Installation ID, and without `backend/serviceAccountKey.json` the backend logs
`Firebase Admin unavailable; push is disabled` and cannot wake the app. `GET
/health` reports `"firebase": true` only when the service-account key loaded.

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

Attach every Vobiz number and every SIP endpoint to this same Voice Application.
One application and one webhook serve all devices; the backend routes by SIP
user (outbound) and by called DID (inbound).

Do not expose the real webhook token in screenshots or shared logs.

## 6. Build and install Android

```bash
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app and enter:

- this device's SIP endpoint username and password;
- `wss://registrar.vobiz.ai:5063/`;
- SIP domain `registrar.vobiz.ai`;
- tunnel HTTPS URL;
- the same `DEVICE_TOKEN` as the backend;
- Vobiz caller ID in E.164 format.

Credentials are encrypted at rest with an Android Keystore AES-GCM key.

Grant microphone, notification, and nearby-device/Bluetooth permissions.

## 7. Test order

1. Confirm the app shows `Ready`.
2. Place an outbound call to a separate PSTN phone.
3. Confirm ringing, answer, two-way audio, mute, speaker, DTMF, and hangup.
4. Call the Vobiz number while the app is foregrounded. The backend logs
   `Inbound PSTN parked in conference; waking device via FCM`, the phone shows a
   full-screen incoming call, and answering bridges two-way audio.
5. Repeat while the app is backgrounded.
6. Swipe the app away from Recents and repeat. The high-priority FCM message
   wakes the process, shows the incoming-call notification, and answering
   registers on demand and joins the conference.
7. Repeat on cellular data.
8. For a second device: open that app so it registers, then call *its* caller ID
   (not device 1's). The log `endpoint` should be that device's SIP username.
   Outbound from that device should show `SIP-originated answer webhook` with
   the same username — not an inbound park on the other device.

The caller hears silence (or `CONFERENCE_WAIT_SOUND`) for the few seconds
between answering the push and the app joining the conference. If nobody
answers within 30 seconds the pending call expires and the caller is released.

Do not test by calling the configured Vobiz caller ID from itself.

## 8. Current POC constraints

- The native SIP implementation intentionally covers the Vobiz call path, not the
  complete SIP RFC surface.
- Live Vobiz interoperability must be validated with sanitized SIP traces.
- Every inbound PSTN call is bridged through a short-lived Vobiz conference so it
  works identically whether the app is foreground, background, or terminated. The
  app answers the FCM push, registers on demand, and dials the DID to join the
  same room. There is no longer a direct `<Dial><User>` inbound path.
- Each device registers its SIP username, caller ID, and Firebase Installation
  ID with the backend. Inbound calls to a DID wake the device that registered
  that DID. Outbound SIP legs are recognized by SIP username. If the backend
  has no mapping for a DID it rejects the call and logs `No device registered
  this DID`.
- FCM wake-ups are keyed on the registered endpoint; a missing installation ID
  means the log shows `Inbound call cannot wake device`.
- Every device re-registers its Firebase Installation ID on app launch, so a
  backend restart is recovered the next time that app is opened. Open every
  device after a backend restart before testing inbound.
- The backend holds pending calls and FCM installation IDs in memory; restarting it clears them.
- Multiple endpoints and numbers on one Voice Application are supported. One
  concurrent pending inbound per device is the supported POC target.
- A TURN service is required before restrictive-network behavior can be accepted.
- Android full-screen call notifications remain subject to OS and Play policy.
- Swiping the app from Recents still delivers FCM, but a *force-stopped* app (via
  App Info, or aggressive OEM battery managers) cannot receive FCM until the user
  launches it again; exempt the app from battery optimization on such devices.
  This is an Android platform restriction, not a Vobiz behavior.
