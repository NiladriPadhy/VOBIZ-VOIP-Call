import "dotenv/config";

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import express, { type NextFunction, type Request, type Response } from "express";
import { cert, getApps, initializeApp } from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";
import helmet from "helmet";
import pino from "pino";
import { pinoHttp } from "pino-http";
import { z } from "zod";

const e164 = z.string().regex(/^\+[1-9]\d{7,14}$/);

const env = z
  .object({
    PORT: z.coerce.number().int().positive().default(3000),
    PUBLIC_URL: z.string().url(),
    DEVICE_TOKEN: z.string().min(32),
    WEBHOOK_TOKEN: z.string().min(32),
    SIP_ENDPOINT: z.string().regex(/^sip:[^@\s]+@[^@\s]+$/),
    DEFAULT_COUNTRY_CODE: z.string().regex(/^\d{1,3}$/).default("91"),
    MAX_CALL_SECONDS: z.coerce.number().int().min(30).max(3600).default(300),
    VOBIZ_AUTH_ID: z.string().optional(),
    VOBIZ_AUTH_TOKEN: z.string().optional(),
    VOBIZ_API_BASE_URL: z.string().url().default("https://api.vobiz.ai"),
    FIREBASE_SERVICE_ACCOUNT_PATH: z.string().default("./serviceAccountKey.json"),
  })
  .parse(process.env);

const logger = pino({
  level: process.env.LOG_LEVEL ?? "info",
  redact: {
    paths: [
      "req.headers.authorization",
      "installationId",
      "password",
      "token",
      "VOBIZ_AUTH_TOKEN",
    ],
    censor: "[REDACTED]",
  },
});

type PendingCall = {
  id: string;
  caller: string;
  did: string;
  callUuid?: string;
  roomId: string;
  createdAt: number;
  expiresAt: number;
  status: "ringing" | "accepted" | "declined" | "joined";
  endpoint: string;
};

type DirectInboundState = {
  callUuid: string;
  active: boolean;
  endedAt?: number;
};

type OutboundIntent = {
  destination: string;
  callerId: string;
};

type RecordingMeta = {
  id: string;
  endpoint: string;
  number: string;
  direction: "incoming" | "outgoing";
  url: string;
  durationSeconds: number;
  callUuid?: string;
  createdAt: number;
  startedAt: number;
};

const installationIds = new Map<string, string>();
const callerIdByEndpoint = new Map<string, string>();
const pendingCalls = new Map<string, PendingCall>();
const pendingJoinByEndpoint = new Map<string, string>();
const outboundByEndpoint = new Map<string, OutboundIntent>();
const directInboundByEndpoint = new Map<string, DirectInboundState>();
const sipEndpointUser = env.SIP_ENDPOINT.substring(4).split("@")[0]!;
const webhookBaseUrl =
  `${env.PUBLIC_URL.replace(/\/$/, "")}/webhooks/vobiz/${env.WEBHOOK_TOKEN}`;
const RECORDINGS_FILE = process.env.RECORDINGS_FILE ?? "./data/recordings.json";
const MAX_RECORDINGS = 500;
const recordings = loadRecordings();

const firebaseMessaging = initializeFirebase();
const app = express();
app.disable("x-powered-by");
app.use(helmet({ contentSecurityPolicy: false }));
app.use(pinoHttp({ logger }));
app.use(express.json({ limit: "32kb" }));
app.use(express.urlencoded({ extended: false, limit: "32kb" }));

app.get("/health", (_request, response) => {
  response.json({
    status: "ok",
    firebase: firebaseMessaging !== null,
    pendingCalls: pendingCalls.size,
  });
});

app.post(
  `/webhooks/vobiz/${env.WEBHOOK_TOKEN}/answer`,
  async (request, response) => {
    const from = field(request, "From", "from");
    const to = field(request, "To", "to");
    const routeType = field(request, "RouteType", "route_type").toLowerCase();
    const callUuid = field(
      request,
      "CallUUID",
      "CallUuid",
      "RequestUUID",
      "request_uuid",
    );
    const isSipLeg = from.toLowerCase().startsWith("sip:") || routeType === "sip";
    // PSTN inbound: Vobiz only POSTs attached numbers to this Voice App, so
    // any non-SIP From is a call to whatever DID is in To. SIP From is outbound.
    if (!isSipLeg) {
      const did = normalizeNumber(to);
      const caller = normalizeNumber(from) ?? sanitizeCaller(from);
      if (!did) {
        sendXml(
          response.status(400),
          speakAndHangupXml("Inbound destination was not a valid number."),
        );
        return;
      }
      logger.info(
        {
          from: redactIdentity(from),
          to: redactIdentity(to),
          caller: redactIdentity(caller),
          did: redactIdentity(did),
          routeType: routeType || "none",
          callUuid: callUuid || "none",
          endpoint: sipEndpointUser,
        },
        "Inbound PSTN answered; ringing registered SIP endpoint",
      );
      if (callUuid) {
        directInboundByEndpoint.set(sipEndpointUser, {
          callUuid,
          active: true,
        });
      }
      sendXml(
        response,
        dialUserXml(
          env.SIP_ENDPOINT,
          caller,
          did,
          recordVerb("incoming", normalizeNumber(from) ?? caller, sipEndpointUser),
        ),
      );
      return;
    }

    const endpoint = sipUser(from) || sipEndpointUser;
    logger.info(
      {
        from: redactIdentity(from),
        to: redactIdentity(to),
        routeType: routeType || "none",
        endpoint,
      },
      "SIP-originated answer webhook",
    );
    const pendingId = pendingJoinByEndpoint.get(endpoint);
    if (pendingId) {
      const pending = pendingCalls.get(pendingId);
      if (pending && pending.expiresAt > Date.now()) {
        pending.status = "joined";
        pendingJoinByEndpoint.delete(endpoint);
        sendXml(response, conferenceXml(pending.roomId));
        return;
      }
      pendingJoinByEndpoint.delete(endpoint);
    }

    const outbound = outboundByEndpoint.get(endpoint);
    outboundByEndpoint.delete(endpoint);
    const destination = outbound?.destination ?? normalizeNumber(to);
    const callerId = outbound?.callerId ?? callerIdByEndpoint.get(endpoint);
    if (!destination) {
      sendXml(
        response.status(400),
        speakAndHangupXml("No valid destination was supplied."),
      );
      return;
    }
    if (!callerId) {
      sendXml(
        response.status(400),
        speakAndHangupXml("No caller ID is registered for this endpoint."),
      );
      return;
    }
    if (destination === callerId) {
      sendXml(
        response.status(400),
        speakAndHangupXml("Calling the endpoint caller ID is not allowed."),
      );
      return;
    }
    sendXml(
      response,
      dialNumberXml(destination, callerId, recordVerb("outgoing", destination, endpoint)),
    );
  },
);

app.post(
  `/webhooks/vobiz/${env.WEBHOOK_TOKEN}/hangup`,
  (request, response) => {
    const callUuid = field(
      request,
      "CallUUID",
      "CallUuid",
      "RequestUUID",
      "request_uuid",
    );
    for (const [id, pending] of pendingCalls) {
      if (pending.callUuid === callUuid) {
        pendingCalls.delete(id);
        pendingJoinByEndpoint.delete(pending.endpoint);
      }
    }
    const directInbound = directInboundByEndpoint.get(sipEndpointUser);
    if (directInbound?.callUuid === callUuid) {
      directInbound.active = false;
      directInbound.endedAt = Date.now();
    }
    response.sendStatus(204);
  },
);

app.post(
  `/webhooks/vobiz/${env.WEBHOOK_TOKEN}/dial-events`,
  (request, response) => {
    logger.info(
      {
        event: field(request, "Event"),
        action: field(request, "DialAction"),
        status: field(request, "DialBLegStatus"),
        hangupCause: field(request, "DialBLegHangupCause"),
        hangupSource: field(request, "DialBLegHangupSource"),
        aLegUuid: field(request, "DialALegUUID"),
        bLegUuid: field(request, "DialBLegUUID"),
      },
      "Vobiz Dial lifecycle event",
    );
    response.sendStatus(204);
  },
);

app.post(
  `/webhooks/vobiz/${env.WEBHOOK_TOKEN}/dial-result`,
  (request, response) => {
    logger.info(
      {
        event: field(request, "Event"),
        status: field(request, "DialStatus"),
        ringStatus: field(request, "DialRingStatus"),
        hangupCause: field(request, "DialHangupCause"),
        aLegUuid: field(request, "DialALegUUID"),
        bLegUuid: field(request, "DialBLegUUID"),
      },
      "Vobiz Dial final result",
    );
    response.sendStatus(204);
  },
);

app.post(
  `/webhooks/vobiz/${env.WEBHOOK_TOKEN}/record`,
  (request, response) => {
    const recordUrl = field(
      request,
      "RecordUrl",
      "RecordFile",
      "RecordingUrl",
      "recordUrl",
    );
    const durationSeconds =
      Number.parseInt(
        field(request, "RecordingDuration", "Duration", "recordingDuration") || "0",
        10,
      ) || 0;
    const direction =
      field(request, "dir").toLowerCase() === "incoming" ? "incoming" : "outgoing";
    const number = field(request, "num");
    const endpoint = field(request, "ep") || sipEndpointUser;
    const callUuid = field(request, "CallUUID", "CallUuid", "RequestUUID");
    if (recordUrl) {
      addRecording({ endpoint, number, direction, url: recordUrl, durationSeconds, callUuid });
    } else {
      logger.info(
        { event: field(request, "Event") },
        "Record callback received without a recording URL",
      );
    }
    response.sendStatus(204);
  },
);

app.use("/devices", authenticateDevice);
app.use("/calls", authenticateDevice);
app.use("/recordings", authenticateDevice);

app.post("/devices/register", (request, response) => {
  const input = z
    .object({
      endpoint: z.string().min(1).max(128),
      installationId: z.string().min(20).max(256),
      callerId: e164.optional(),
    })
    .parse(request.body);
  installationIds.set(input.endpoint, input.installationId);
  if (input.callerId) {
    callerIdByEndpoint.set(input.endpoint, input.callerId);
  }
  logger.info({ endpoint: input.endpoint }, "FCM installation registered");
  response.sendStatus(204);
});

app.post("/calls/outbound", (request, response) => {
  const input = z
    .object({
      endpoint: z.string().min(1).max(128),
      destination: z.string(),
      callerId: e164,
    })
    .parse(request.body);
  const destination = normalizeNumber(input.destination);
  if (!destination || destination === input.callerId) {
    response.status(400).json({ error: "Invalid destination" });
    return;
  }
  callerIdByEndpoint.set(input.endpoint, input.callerId);
  outboundByEndpoint.set(input.endpoint, {
    destination,
    callerId: input.callerId,
  });
  setTimeout(() => {
    const current = outboundByEndpoint.get(input.endpoint);
    if (current?.destination === destination && current.callerId === input.callerId) {
      outboundByEndpoint.delete(input.endpoint);
    }
  }, 30_000).unref();
  response.sendStatus(204);
});

app.get("/calls/inbound-status", (request, response) => {
  const endpoint = request.header("X-Vobiz-Endpoint") ?? "";
  const state = directInboundByEndpoint.get(endpoint);
  response.json({
    known: state !== undefined,
    active: state?.active ?? false,
    endedAt: state?.endedAt,
  });
});

app.get("/calls/:id", (request, response) => {
  const pending = getPending(request.params.id);
  if (!pending) {
    response.status(404).json({ error: "Pending call not found" });
    return;
  }
  response.json({
    id: pending.id,
    caller: pending.caller,
    expiresAt: pending.expiresAt,
    status: pending.status,
  });
});

app.post("/calls/:id/accept", (request, response) => {
  const pending = getPending(request.params.id);
  if (!pending) {
    response.status(404).json({ error: "Pending call not found" });
    return;
  }
  const endpoint = z
    .object({ endpoint: z.string().min(1).max(128) })
    .parse(request.body).endpoint;
  if (endpoint !== pending.endpoint) {
    response.status(403).json({ error: "Endpoint mismatch" });
    return;
  }
  pending.status = "accepted";
  pendingJoinByEndpoint.set(endpoint, pending.id);
  response.json({ joinNumber: pending.did });
});

app.post("/calls/:id/decline", async (request, response) => {
  const pending = getPending(request.params.id);
  if (!pending) {
    response.status(404).json({ error: "Pending call not found" });
    return;
  }
  pending.status = "declined";
  pendingJoinByEndpoint.delete(pending.endpoint);
  if (pending.callUuid) {
    await hangupVobizCall(pending.callUuid);
  }
  pendingCalls.delete(pending.id);
  response.sendStatus(204);
});

app.get("/recordings", (request, response) => {
  const endpoint = request.header("X-Vobiz-Endpoint") ?? "";
  const list = [...recordings.values()]
    .filter((recording) => recording.endpoint === endpoint)
    .sort((a, b) => b.createdAt - a.createdAt)
    .slice(0, 100)
    .map((recording) => ({
      id: recording.id,
      number: recording.number,
      direction: recording.direction,
      startedAtEpochMs: recording.startedAt,
      durationSeconds: recording.durationSeconds,
    }));
  response.json({ recordings: list });
});

app.get("/recordings/:id/audio", async (request, response) => {
  const recording = recordings.get(request.params.id);
  if (!recording) {
    response.sendStatus(404);
    return;
  }
  try {
    // Vobiz media (media.vobiz.ai) authenticates via X-Auth-ID / X-Auth-Token
    // headers, not HTTP Basic.
    const upstreamHeaders: Record<string, string> = {};
    if (env.VOBIZ_AUTH_ID && env.VOBIZ_AUTH_TOKEN) {
      upstreamHeaders["X-Auth-ID"] = env.VOBIZ_AUTH_ID;
      upstreamHeaders["X-Auth-Token"] = env.VOBIZ_AUTH_TOKEN;
    }
    const upstream = await fetch(recording.url, { headers: upstreamHeaders });
    if (!upstream.ok) {
      logger.error(
        { id: recording.id, status: upstream.status },
        "Recording upstream fetch failed",
      );
      response.sendStatus(502);
      return;
    }
    const buffer = Buffer.from(await upstream.arrayBuffer());
    response
      .type(upstream.headers.get("content-type") ?? "audio/mpeg")
      .send(buffer);
  } catch (error) {
    logger.error({ error, id: recording.id }, "Recording proxy error");
    response.sendStatus(502);
  }
});

app.use(
  (
    error: unknown,
    _request: Request,
    response: Response,
    _next: NextFunction,
  ) => {
    if (error instanceof z.ZodError) {
      response.status(400).json({ error: "Invalid request" });
      return;
    }
    logger.error({ error }, "Unhandled backend error");
    response.status(500).json({ error: "Internal server error" });
  },
);

app.listen(env.PORT, () => {
  logger.info(
    {
      port: env.PORT,
      answerUrl: `${env.PUBLIC_URL}/webhooks/vobiz/[REDACTED]/answer`,
      endpoint: sipEndpointUser,
      firebase: firebaseMessaging !== null,
    },
    "Vobiz Android POC backend listening",
  );
});

function authenticateDevice(
  request: Request,
  response: Response,
  next: NextFunction,
): void {
  const supplied = request.header("authorization")?.replace(/^Bearer\s+/i, "") ?? "";
  const expected = Buffer.from(env.DEVICE_TOKEN);
  const actual = Buffer.from(supplied);
  if (
    expected.length !== actual.length ||
    !crypto.timingSafeEqual(expected, actual)
  ) {
    response.status(401).json({ error: "Unauthorized" });
    return;
  }
  next();
}

function createPendingCall(
  caller: string,
  did: string,
  callUuid?: string,
): PendingCall {
  const now = Date.now();
  const id = crypto.randomUUID();
  const pending: PendingCall = {
    id,
    caller,
    did,
    callUuid,
    roomId: `vi_${id.replaceAll("-", "")}`,
    createdAt: now,
    expiresAt: now + 30_000,
    status: "ringing",
    endpoint: sipEndpointUser,
  };
  pendingCalls.set(id, pending);
  setTimeout(() => {
    const current = pendingCalls.get(id);
    if (
      current &&
      current.expiresAt <= Date.now() &&
      current.status !== "joined"
    ) {
      if (current.callUuid) void hangupVobizCall(current.callUuid);
      pendingCalls.delete(id);
      pendingJoinByEndpoint.delete(current.endpoint);
    }
  }, 31_000).unref();
  return pending;
}

function getPending(id: string | undefined): PendingCall | undefined {
  if (!id) return undefined;
  const pending = pendingCalls.get(id);
  if (!pending || pending.expiresAt <= Date.now() || pending.status === "declined") {
    if (pending) pendingCalls.delete(id);
    return undefined;
  }
  return pending;
}

async function notifyIncomingCall(pending: PendingCall): Promise<void> {
  const installationId = installationIds.get(pending.endpoint);
  if (!firebaseMessaging || !installationId) {
    logger.warn(
      { endpoint: pending.endpoint },
      "Inbound call cannot wake device: FCM or installation ID is unavailable",
    );
    return;
  }
  try {
    await firebaseMessaging.send({
      fid: installationId,
      android: { priority: "high", ttl: 30_000 },
      data: {
        type: "inbound_call",
        pendingCallId: pending.id,
        caller: pending.caller,
        expiresAt: String(pending.expiresAt),
      },
    });
  } catch (error) {
    logger.error({ error, endpoint: pending.endpoint }, "FCM send failed");
  }
}

async function hangupVobizCall(callUuid: string): Promise<void> {
  if (!env.VOBIZ_AUTH_ID || !env.VOBIZ_AUTH_TOKEN) {
    logger.warn({ callUuid }, "Cannot terminate call: Vobiz REST credentials missing");
    return;
  }
  const url = new URL(
    `/api/v1/Account/${encodeURIComponent(env.VOBIZ_AUTH_ID)}/Call/${encodeURIComponent(callUuid)}/`,
    env.VOBIZ_API_BASE_URL,
  );
  const basic = Buffer.from(
    `${env.VOBIZ_AUTH_ID}:${env.VOBIZ_AUTH_TOKEN}`,
  ).toString("base64");
  const result = await fetch(url, {
    method: "DELETE",
    headers: { Authorization: `Basic ${basic}` },
  });
  if (!result.ok && result.status !== 404) {
    logger.error(
      { callUuid, status: result.status },
      "Vobiz call termination failed",
    );
  }
}

function addRecording(input: {
  endpoint: string;
  number: string;
  direction: "incoming" | "outgoing";
  url: string;
  durationSeconds: number;
  callUuid?: string;
}): void {
  const now = Date.now();
  const recording: RecordingMeta = {
    id: crypto.randomUUID(),
    endpoint: input.endpoint,
    number: input.number,
    direction: input.direction,
    url: input.url,
    durationSeconds: input.durationSeconds,
    callUuid: input.callUuid,
    createdAt: now,
    startedAt: now - input.durationSeconds * 1000,
  };
  recordings.set(recording.id, recording);
  if (recordings.size > MAX_RECORDINGS) {
    const oldest = [...recordings.values()].sort(
      (a, b) => a.createdAt - b.createdAt,
    )[0];
    if (oldest) recordings.delete(oldest.id);
  }
  persistRecordings();
  logger.info(
    {
      id: recording.id,
      endpoint: recording.endpoint,
      direction: recording.direction,
      number: redactIdentity(recording.number),
      durationSeconds: recording.durationSeconds,
    },
    "Call recording stored",
  );
}

function loadRecordings(): Map<string, RecordingMeta> {
  try {
    const parsed = JSON.parse(
      fs.readFileSync(RECORDINGS_FILE, "utf8"),
    ) as RecordingMeta[];
    return new Map(parsed.map((recording) => [recording.id, recording]));
  } catch {
    return new Map();
  }
}

function persistRecordings(): void {
  try {
    fs.mkdirSync(path.dirname(RECORDINGS_FILE), { recursive: true });
    fs.writeFileSync(RECORDINGS_FILE, JSON.stringify([...recordings.values()]));
  } catch (error) {
    logger.warn({ error }, "Failed to persist recordings");
  }
}

function initializeFirebase(): ReturnType<typeof getMessaging> | null {
  try {
    const serviceAccount = JSON.parse(
      fs.readFileSync(env.FIREBASE_SERVICE_ACCOUNT_PATH, "utf8"),
    ) as Parameters<typeof cert>[0];
    const firebaseApp =
      getApps()[0] ?? initializeApp({ credential: cert(serviceAccount) });
    return getMessaging(firebaseApp);
  } catch (error) {
    logger.warn({ error }, "Firebase Admin unavailable; push is disabled");
    return null;
  }
}

function field(request: Request, ...names: string[]): string {
  for (const name of names) {
    const value = request.body?.[name] ?? request.query?.[name];
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return "";
}

function sipUser(value: string): string | undefined {
  const match = /^sip:([^@;>]+)/i.exec(value.replace(/^</, ""));
  return match?.[1];
}

function normalizeNumber(value: string): string | undefined {
  const raw = value
    .replace(/^sip:/i, "")
    .split("@")[0]!
    .replace(/[^\d+]/g, "");
  if (/^\+[1-9]\d{7,14}$/.test(raw)) return raw;
  if (/^[1-9]\d{7,14}$/.test(raw)) {
    if (
      raw.startsWith(env.DEFAULT_COUNTRY_CODE) &&
      raw.length >= env.DEFAULT_COUNTRY_CODE.length + 8
    ) {
      return `+${raw}`;
    }
    return `+${env.DEFAULT_COUNTRY_CODE}${raw}`;
  }
  return undefined;
}

function sanitizeCaller(value: string): string {
  return value.replace(/[<>&"']/g, "").slice(0, 64) || "Unknown caller";
}

function sendXml(response: Response, xml: string): void {
  response.status(response.statusCode).type("application/xml").send(xml);
}

function dialNumberXml(destination: string, callerId: string, record = ""): string {
  return xmlResponse(
    record +
      `<Dial callerId="${xmlEscape(callerId)}" dialMusic="real" timeout="30" ` +
      `callbackUrl="${xmlEscape(`${webhookBaseUrl}/dial-events`)}" ` +
      `callbackMethod="POST" action="${xmlEscape(`${webhookBaseUrl}/dial-result`)}" ` +
      `method="POST" redirect="false">` +
      `<Number>${xmlEscape(destination)}</Number></Dial><Hangup/>`,
  );
}

function dialUserXml(
  sipEndpoint: string,
  caller: string,
  did: string,
  record = "",
): string {
  // callerId MUST be an account-owned DID. Vobiz refuses to originate the SIP
  // B-leg when From is a foreign CLI, so real inbound callers die instantly
  // while calls from owned numbers appear to work. The real caller is passed
  // in callerName and X-VH-Caller (sipHeaders="Caller=..."). Use the DID from
  // webhook To, not a server-wide env value.
  const display = xmlEscape(caller);
  return xmlResponse(
    record +
      `<Dial timeout="45" callerId="${xmlEscape(did)}" callerName="${display}" ` +
      `dialMusic="real" ` +
      `callbackUrl="${xmlEscape(`${webhookBaseUrl}/dial-events`)}" ` +
      `callbackMethod="POST" action="${xmlEscape(`${webhookBaseUrl}/dial-result`)}" ` +
      `method="POST" redirect="false">` +
      `<User sipHeaders="Caller=${display}">${xmlEscape(sipEndpoint)}</User></Dial>`,
  );
}

// Vobiz records the whole bridged session; RecordStop posts an MP3 URL to the
// callback. Direction/number/endpoint are carried in the callback query so the
// app can match a recording to its call-log entry without extra server state.
function recordVerb(
  direction: "incoming" | "outgoing",
  number: string,
  endpoint: string,
): string {
  const callback = new URL(`${webhookBaseUrl}/record`);
  callback.searchParams.set("dir", direction);
  callback.searchParams.set("num", number);
  callback.searchParams.set("ep", endpoint);
  return (
    `<Record callbackUrl="${xmlEscape(callback.toString())}" callbackMethod="POST" ` +
    `recordSession="true" redirect="false" maxLength="3600" timeout="3600" ` +
    `playBeep="false" fileFormat="mp3"/>`
  );
}

function conferenceXml(roomId: string): string {
  return xmlResponse(
    `<Conference stayAlone="true">` +
      `${xmlEscape(roomId)}</Conference>`,
  );
}

function speakAndHangupXml(message: string): string {
  return xmlResponse(`<Speak>${xmlEscape(message)}</Speak><Hangup/>`);
}

function xmlResponse(children: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?><Response>${children}</Response>`;
}

function xmlEscape(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}

function redactIdentity(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) return "none";
  const digits = trimmed.replace(/\D/g, "");
  if (digits.length >= 8) {
    return `${digits.slice(0, 4)}…${digits.slice(-2)}`;
  }
  if (trimmed.toLowerCase().startsWith("sip:")) {
    return `sip:${sipUser(trimmed) ?? "user"}@…`;
  }
  return trimmed.slice(0, 8) + "…";
}
