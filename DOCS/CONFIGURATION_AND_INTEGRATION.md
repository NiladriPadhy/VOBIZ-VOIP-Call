# Enetro VoIP — Configuration and Integration

End-user settings for **Enetro VoIP** (`com.enetro.vobizvoip`) and the contracts
other Android apps use to open the dialer, place a call, watch call state, and
read the VoIP call log.

The app is a SIP/WebRTC softphone. Its calls never write to Android's system
`CallLog`. Use the app's own Settings and the integration APIs below.

For install, tunnel, Firebase, and Vobiz console steps see
[SETUP_GUIDE.md](SETUP_GUIDE.md).

---

## Configuration

On first launch the same fields appear on **Set up your endpoint**. After that,
open them from the side menu (hamburger) → **Settings**.

Tap **Save and connect** after editing. Credentials are encrypted with the
Android Keystore and stay on the device.

**Save and connect** stays disabled until every required field is valid.

### Status (read-only)

These rows are not typed in. They confirm that the values below actually
connected.

| Row | What a healthy value looks like |
| --- | --- |
| **SIP endpoint** | `Registered and ready for calls` |
| **Backend** | `Online`, with `Firebase ready` if inbound wake-up is configured |

Use the refresh icons to reconnect SIP or re-check the backend.

### SIP endpoint

| Settings field | What to enter | Example |
| --- | --- | --- |
| **SIP username** | The user part of this device's Vobiz SIP endpoint. Each device must use its own endpoint. Required. | `endpoint-user` |
| **SIP password** | Password for that SIP endpoint. Required. Masked. | *(from the Vobiz console)* |
| **Registrar WSS URL** | Secure WebSocket registrar. Must start with `wss://`. Default is already filled. | `wss://registrar.vobiz.ai:5063/` |
| **SIP domain** | SIP domain for REGISTER / INVITE. Default is already filled. | `registrar.vobiz.ai` |

Do not put the Vobiz REST Auth ID or Auth Token in these fields. Those belong
only in `backend/.env`.

### Backend

| Settings field | What to enter | Example |
| --- | --- | --- |
| **Public backend HTTPS URL** | Public HTTPS origin of the local Answer URL service (tunnel URL). Must start with `https://`. A trailing `/` is stripped on save. | `https://your-tunnel.example` |
| **POC device token** | Same value as `DEVICE_TOKEN` in `backend/.env` (at least 32 characters). Required. Masked. | *(from `.env`)* |
| **Vobiz caller ID (E.164)** | The Vobiz DID this device presents as Caller ID. Must start with `+`. Do not call this number from itself when testing. | `+919876543210` |

Each device supplies its own caller ID. There is no server-wide caller ID.
The backend maps that DID to this device's SIP username when the app registers,
so inbound calls to that number wake this device only.

### Dialing

| Settings field | What to enter |
| --- | --- |
| **Default country** | Country used to turn a national number into E.164 when the device has **no SIM**. Choose **Auto (detect from SIM)** (default) or a country such as `India (+91)`. |

How numbers are normalized before a call is placed (from the keypad or from
another app):

| What the user / integrator typed | What is dialed |
| --- | --- |
| Already international (`+9198…`) | Unchanged (`+` plus digits) |
| International prefix (`0091…`) | `00` replaced with `+` |
| National trunk prefix (`098…`) | Leading `0` replaced with `+<country code>` |
| Bare national number (`987…`) | `+<country code>` prepended |
| Feature / USSD codes (`*` or `#`) | Left as entered |

Country-code order: SIM ISO → this **Default country** → `IN` (`91`).

### Call recording

| Settings field | What to enter |
| --- | --- |
| **Record calls** | On (default) records each connected call and makes playback available from Recents and the call-log provider. Off skips recording. |

### Permissions the app asks for

Grant these when prompted; inbound audio and background wake-up depend on them.

| Permission | Why |
| --- | --- |
| Microphone | WebRTC audio |
| Nearby devices / Bluetooth | Headset audio |
| Contacts | Names on Recents and in the call-log provider |
| Phone | Mute the VoIP mic while a native GSM/CDMA call is off-hook |
| Notifications (Android 13+) | Incoming-call and in-call notifications |

### Where the Settings values come from

| Value in Settings | Source |
| --- | --- |
| SIP username / password / domain / WSS URL | This device's Vobiz SIP endpoint |
| Public backend HTTPS URL | Tunnel in front of the local backend (`PUBLIC_URL`) |
| POC device token | `DEVICE_TOKEN` in `backend/.env` (shared by every device using this backend) |
| Vobiz caller ID | The Vobiz number this device should present and receive |

Vobiz Voice Application URLs (not entered in the Android app):

```text
Answer URL:  https://<PUBLIC_URL>/webhooks/vobiz/<WEBHOOK_TOKEN>/answer
Hangup URL:  https://<PUBLIC_URL>/webhooks/vobiz/<WEBHOOK_TOKEN>/hangup
Method: POST
```

---

## Integration

Package: `com.enetro.vobizvoip`. Minimum SDK: Android 12 (API 31).

The app is **not** the OS default dialer. Calls go over SIP/WebRTC, not the
cellular stack. There is no `sip:` scheme, no custom URL scheme, and no AIDL
SDK. Public entry points are **intents** on `MainActivity` and the **call-log
content provider**.

### Invoke the dialer from another app

Numbers are normalized with the same rules as the keypad (see **Dialing**).

#### Open the keypad (do not place the call)

`ACTION_DIAL` opens Enetro VoIP on the Keypad tab. A `tel:` URI pre-fills the
number. The user taps the call button.

```kotlin
startActivity(
    Intent(Intent.ACTION_DIAL, Uri.parse("tel:+919876543210")).apply {
        setPackage("com.enetro.vobizvoip")
    },
)
```

```bash
adb shell am start -a android.intent.action.DIAL \
  -n com.enetro.vobizvoip/.MainActivity -d tel:+919876543210
```

`ACTION_DIAL` without a `tel:` URI just opens the keypad.

#### Place a call immediately

Any of these start the outbound call as soon as the activity handles the
intent. Prefer the explicit package so the chooser is skipped.

```kotlin
// 1) Custom action + EXTRA_NUMBER (recommended for app-to-app)
startActivity(
    Intent("com.enetro.vobizvoip.action.CALL").apply {
        setPackage("com.enetro.vobizvoip")
        putExtra("number", "09876543210") // normalized via SIM / Default country
    },
)

// 2) Standard tel: link (ACTION_VIEW or ACTION_CALL)
startActivity(
    Intent(Intent.ACTION_VIEW, Uri.parse("tel:+919876543210")).apply {
        setPackage("com.enetro.vobizvoip")
    },
)
```

```bash
adb shell am start -a com.enetro.vobizvoip.action.CALL \
  -n com.enetro.vobizvoip/.MainActivity --es number "09876543210"

adb shell am start -a android.intent.action.VIEW \
  -n com.enetro.vobizvoip/.MainActivity -d tel:+919876543210
```

| Action | Data / extra | Behavior |
| --- | --- | --- |
| `android.intent.action.DIAL` | optional `tel:<number>` | Open keypad; pre-fill; do **not** auto-call |
| `android.intent.action.VIEW` | `tel:<number>` | Place the call |
| `android.intent.action.CALL` | `tel:<number>` | Place the call |
| `com.enetro.vobizvoip.action.CALL` | extra `number` (string), or `tel:` data | Place the call |

The number is taken from extra `number` first, then from the `tel:` URI. Empty
or missing numbers are ignored for auto-call actions.

Web pages and other apps can also hand off `tel:` links; the activity is
`BROWSABLE` for that scheme.

### Monitor call states

There are two different “call” notions. Integrators usually want the first
for a native phone call and the second for history after an Enetro VoIP call
ends.

#### 1. Cellular (GSM/CDMA) call state

Enetro VoIP watches the device's **native** telephony state with
`TelephonyCallback` (API 31+) and mutes its own microphone while that call is
off-hook. It does **not** broadcast this state. Another app that holds
`READ_PHONE_STATE` can register the same callback:

```kotlin
class MyTelephonyCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
    override fun onCallStateChanged(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> { /* idle or ended */ }
            TelephonyManager.CALL_STATE_RINGING -> { /* ringing */ }
            TelephonyManager.CALL_STATE_OFFHOOK -> { /* active or held */ }
        }
    }
}

val telephonyManager = getSystemService(TelephonyManager::class.java)
val callback = MyTelephonyCallback()
telephonyManager.registerTelephonyCallback(mainExecutor, callback)
// later:
telephonyManager.unregisterTelephonyCallback(callback)
```

| `TelephonyManager` state | Meaning |
| --- | --- |
| `CALL_STATE_IDLE` | No native call |
| `CALL_STATE_RINGING` | Incoming native call |
| `CALL_STATE_OFFHOOK` | Native call active or on hold |

#### 2. Enetro VoIP call state

Live VoIP phases (`IDLE`, `OUTGOING`, `RINGING`, `INCOMING`, `CONNECTING`,
`ACTIVE`, `ENDING`, `FAILED`) stay inside the app. There is no exported
broadcast, ContentObserver that fires on phase change, or AIDL callback for
ringing / answered / hung up.

To see that a VoIP call **finished**, query the call-log provider (next
section). Completed, missed, declined, canceled, and failed calls appear there
with `result`, `type`, `date`, and `duration`.

A `ContentObserver` on
`content://com.enetro.vobizvoip.provider.calllog/calls` is reserved for a
future notify; the current store does not call `notifyChange()`, so poll the
provider after you expect a call to have ended.

### Query the call log

`CallLogProvider` exposes this app's VoIP history — including a recording path
when **Record calls** is on and a recording was matched.

#### Permission

The calling app must declare:

```xml
<uses-permission android:name="com.enetro.vobizvoip.permission.READ_CALL_LOG" />
```

Protection level is `normal` (granted at install on standard Android).

#### URIs

| URI | Returns |
| --- | --- |
| `content://com.enetro.vobizvoip.provider.calllog/calls` | All entries (newest first, up to 200) |
| `content://com.enetro.vobizvoip.provider.calllog/calls/{entry_id}` | One entry |
| `content://com.enetro.vobizvoip.provider.calllog/recordings/{id}/audio` | Read-only audio stream |

The provider is read-only. Insert, update, and delete throw
`UnsupportedOperationException`.

#### Columns

| Column | Type | Meaning |
| --- | --- | --- |
| `_id` | long | Row index in this result |
| `entry_id` | string | Stable UUID |
| `number` | string | Dialed / remote number (E.164 when normalized) |
| `display_name` | string | Contact name if Enetro VoIP has `READ_CONTACTS`; otherwise `""` |
| `direction` | string | `INCOMING` or `OUTGOING` |
| `type` | int | `1` incoming, `2` outgoing, `3` missed (same idea as system CallLog) |
| `result` | string | `COMPLETED`, `MISSED`, `DECLINED`, `CANCELED`, `FAILED` |
| `date` | long | Start time, epoch milliseconds |
| `duration` | long | Connected duration in seconds |
| `recording_available` | int | `1` if a recording was matched, else `0` |
| `recording_path` | string or null | `content://…/recordings/{id}/audio`, or null |

`recording_path` is null unless **Record calls** is enabled and a backend
recording matched the entry (same direction, last 10 digits, start time within
5 minutes). Opening that URI streams audio from the backend; the device token
never leaves Enetro VoIP.

#### Kotlin

```kotlin
val calls = Uri.parse("content://com.enetro.vobizvoip.provider.calllog/calls")
contentResolver.query(calls, null, null, null, null)?.use { cursor ->
    val number = cursor.getColumnIndexOrThrow("number")
    val name = cursor.getColumnIndexOrThrow("display_name")
    val type = cursor.getColumnIndexOrThrow("type")
    val result = cursor.getColumnIndexOrThrow("result")
    val date = cursor.getColumnIndexOrThrow("date")
    val duration = cursor.getColumnIndexOrThrow("duration")
    val recordingPath = cursor.getColumnIndexOrThrow("recording_path")
    while (cursor.moveToNext()) {
        val path = cursor.getString(recordingPath)
        if (path != null) {
            contentResolver.openInputStream(Uri.parse(path))?.use { audio ->
                // play or save
            }
        }
    }
}
```

```bash
# Caller must hold com.enetro.vobizvoip.permission.READ_CALL_LOG
adb shell content query \
  --uri content://com.enetro.vobizvoip.provider.calllog/calls
```

---

## Quick checklist

**Configure the app**

1. Enter SIP username, password, registrar `wss://…`, and SIP domain.
2. Enter the tunnel HTTPS URL, the backend `DEVICE_TOKEN`, and the E.164 caller ID.
3. Set **Default country** if the device has no SIM.
4. Leave **Record calls** on if other apps should read recordings.
5. **Save and connect** until SIP is registered and Backend is online.

**Integrate from another app**

1. `ACTION_DIAL` + `tel:` to pre-fill the keypad.
2. `com.enetro.vobizvoip.action.CALL` + extra `number` to place a call.
3. `TelephonyCallback` + `READ_PHONE_STATE` for native cellular state.
4. Poll `content://com.enetro.vobizvoip.provider.calllog/calls` (with
   `READ_CALL_LOG`) for VoIP history and optional recording audio.
