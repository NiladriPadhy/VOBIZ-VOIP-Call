# Answer and Hangup webhooks

Vobiz POSTs these URLs when a Voice Application call starts and when it ends.
The backend accepts `application/x-www-form-urlencoded` (Vobiz’s usual format)
or JSON. Fields are read from the body or the query string.

```text
Answer URL:  https://<PUBLIC_URL>/webhooks/vobiz/<WEBHOOK_TOKEN>/answer
Hangup URL:  https://<PUBLIC_URL>/webhooks/vobiz/<WEBHOOK_TOKEN>/hangup
Method: POST
```

Set both URLs on the Vobiz Voice Application. See
[SETUP_GUIDE.md](SETUP_GUIDE.md) for tunnel and token setup.

The backend treats a request as **inbound PSTN** unless `From` is a SIP user
this app has registered. That inbound path parks the caller in a conference and
wakes the device. Do not key off `RouteType`: Vobiz can report `sip` for both
the app’s SIP leg and SIP-trunked inbound.

---

## Answer webhook

### Fields the backend reads

| Field | Aliases | Role |
| --- | --- | --- |
| `From` | `from` | PSTN caller, or the app SIP user for SIP-originated legs |
| `To` | `to` | Called number / DID |
| `SIP-H-To` | | Fallback when `To` is missing (inbound only) |
| `CallUUID` | `CallUuid`, `RequestUUID`, `request_uuid` | Call id; stored so Hangup can clean up |
| `Direction` | `direction` | Logged only |
| `RouteType` | `route_type` | Logged only; **not** used for inbound vs outbound |

---

### 1. Inbound PSTN (someone dials the Vobiz DID)

`From` is **not** a registered SIP username. The backend parks the caller in a
per-call conference, looks up the device that registered this DID, and sends
FCM so the app can join.

**Request**

```http
POST /webhooks/vobiz/<WEBHOOK_TOKEN>/answer HTTP/1.1
Host: your-tunnel.example
Content-Type: application/x-www-form-urlencoded

From=%2B919812345678&To=%2B919876543210&Direction=inbound&RouteType=number&CallUUID=a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

Equivalent JSON:

```json
{
  "From": "+919812345678",
  "To": "+919876543210",
  "Direction": "inbound",
  "RouteType": "number",
  "CallUUID": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**Response `200` `application/xml`** — park in conference (record on by default):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Record callbackUrl="https://your-tunnel.example/webhooks/vobiz/TOKEN/record?dir=incoming&amp;num=%2B919812345678&amp;ep=endpoint-user"
          callbackMethod="POST"
          recordSession="true"
          redirect="false"
          maxLength="3600"
          timeout="3600"
          playBeep="false"
          fileFormat="mp3"/>
  <Conference action="https://your-tunnel.example/webhooks/vobiz/TOKEN/conference-events"
              method="POST"
              endConferenceOnExit="true">
    room-a1b2c3d4
  </Conference>
</Response>
```

If `CONFERENCE_WAIT_SOUND` is set, the `<Conference>` also gets
`waitSound="…" waitMethod="POST"`. If recording is off for that device, the
`<Record>` element is omitted.

---

### 2. SIP outbound (app placed a call)

`From` is the registered SIP username (or `sip:endpoint-user@registrar.vobiz.ai`).
There is no pending conference join for that endpoint, so the backend dials the
destination with the device’s caller ID.

**Request**

```http
POST /webhooks/vobiz/<WEBHOOK_TOKEN>/answer HTTP/1.1
Host: your-tunnel.example
Content-Type: application/x-www-form-urlencoded

From=sip%3Aendpoint-user%40registrar.vobiz.ai&To=%2B918765432109&Direction=outbound&RouteType=sip&CallUUID=b2c3d4e5-f6a7-8901-bcde-f12345678901
```

Equivalent JSON:

```json
{
  "From": "sip:endpoint-user@registrar.vobiz.ai",
  "To": "+918765432109",
  "Direction": "outbound",
  "RouteType": "sip",
  "CallUUID": "b2c3d4e5-f6a7-8901-bcde-f12345678901"
}
```

**Response `200` `application/xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Record callbackUrl="https://your-tunnel.example/webhooks/vobiz/TOKEN/record?dir=outgoing&amp;num=%2B918765432109&amp;ep=endpoint-user"
          callbackMethod="POST"
          recordSession="true"
          redirect="false"
          maxLength="3600"
          timeout="3600"
          playBeep="false"
          fileFormat="mp3"/>
  <Dial callerId="+919876543210"
        dialMusic="real"
        timeout="30"
        callbackUrl="https://your-tunnel.example/webhooks/vobiz/TOKEN/dial-events"
        callbackMethod="POST"
        action="https://your-tunnel.example/webhooks/vobiz/TOKEN/dial-result"
        method="POST"
        redirect="false">
    <Number>+918765432109</Number>
  </Dial>
  <Hangup/>
</Response>
```

If recording is off for that outbound intent / device, the `<Record>` element
is omitted.

---

### 3. SIP join (app answered the inbound push)

Same SIP `From` as outbound, but the backend has a pending conference join for
that endpoint. `To` is usually the DID the app dialed to join.

**Request** — same shape as outbound.

**Response `200` `application/xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Conference action="https://your-tunnel.example/webhooks/vobiz/TOKEN/conference-events"
              method="POST"
              endConferenceOnExit="true">
    room-a1b2c3d4
  </Conference>
</Response>
```

---

### Answer error responses

Still VobizXML. Status is `400` or `404`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Speak>No device is registered for this number.</Speak>
  <Hangup/>
</Response>
```

| Status | Speak text |
| --- | --- |
| `400` | `Inbound destination was not a valid number.` |
| `404` | `No device is registered for this number.` |
| `400` | `No valid destination was supplied.` |
| `400` | `No caller ID is registered for this endpoint.` |
| `400` | `Calling the endpoint caller ID is not allowed.` |

---

## Hangup webhook

Called when the Vobiz call ends. The handler only uses `CallUUID` (to drop
pending / inbound state) and always returns empty.

**Request**

```http
POST /webhooks/vobiz/<WEBHOOK_TOKEN>/hangup HTTP/1.1
Host: your-tunnel.example
Content-Type: application/x-www-form-urlencoded

CallUUID=a1b2c3d4-e5f6-7890-abcd-ef1234567890&From=%2B919812345678&To=%2B919876543210&Duration=42&HangupCause=NORMAL_CLEARING
```

Equivalent JSON:

```json
{
  "CallUUID": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "From": "+919812345678",
  "To": "+919876543210",
  "Duration": "42",
  "HangupCause": "NORMAL_CLEARING"
}
```

Aliases for the call id: `CallUUID`, `CallUuid`, `RequestUUID`, `request_uuid`.
Extra fields (`From`, `Duration`, `HangupCause`, and others Vobiz may send) are
ignored.

**Response**

```http
HTTP/1.1 204 No Content
```

No body. This is not VobizXML.
