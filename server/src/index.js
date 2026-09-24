require("dotenv").config();

const https = require("https");
const express = require("express");
const admin = require("firebase-admin");
const fs = require("fs");
const path = require("path");
const { URLSearchParams } = require("url");

const app = express();
app.use(express.json({ limit: "64kb" }));

const guardianPairingCodes = new Map();
const guardianReturnStates = new Map();
const GUARDIAN_PAIRING_TTL_MS = 10 * 60 * 1000;
const DATA_DIR = process.env.SAFEWAY_DATA_DIR || path.join(__dirname, "..", "data");
const GUARDIAN_STATES_FILE = path.join(DATA_DIR, "guardian-return-states.json");
const MAX_GUARDIAN_HISTORY_ITEMS = 120;

function initFirebase() {
  if (admin.apps.length > 0) {
    return;
  }

  const serviceAccountJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if (serviceAccountJson) {
    admin.initializeApp({
      credential: admin.credential.cert(JSON.parse(serviceAccountJson)),
    });
    return;
  }

  admin.initializeApp();
}

function optionalString(value) {
  return typeof value === "string" ? value.trim() : "";
}

function optionalNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function loadGuardianReturnStates() {
  try {
    if (!fs.existsSync(GUARDIAN_STATES_FILE)) {
      return;
    }
    const raw = fs.readFileSync(GUARDIAN_STATES_FILE, "utf8");
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") {
      return;
    }
    for (const [token, state] of Object.entries(parsed)) {
      if (token && state && typeof state === "object") {
        guardianReturnStates.set(token, state);
      }
    }
  } catch (error) {
    console.error("Failed to load guardian return states", error);
  }
}

function persistGuardianReturnStates() {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(
      GUARDIAN_STATES_FILE,
      JSON.stringify(Object.fromEntries(guardianReturnStates), null, 2)
    );
  } catch (error) {
    console.error("Failed to persist guardian return states", error);
  }
}

function saveGuardianReturnState(guardianToken, updates) {
  const token = optionalString(guardianToken);
  if (!token) {
    return null;
  }

  const previous = guardianReturnStates.get(token) || {};
  const updatedAt = Number(updates.updatedAt) || Date.now();
  const next = {
    ...previous,
    ...updates,
    updatedAt,
  };
  next.history = buildGuardianHistory(previous.history, next);
  guardianReturnStates.set(token, next);
  persistGuardianReturnStates();
  return next;
}

function buildGuardianStateFromFields(fields) {
  return {
    type: optionalString(fields.type),
    title: optionalString(fields.title),
    body: optionalString(fields.body),
    mapsLink: optionalString(fields.mapsLink),
    routeLink: optionalString(fields.routeLink),
    routePoints: optionalString(fields.routePoints),
    destination: optionalString(fields.destination),
    latitude: optionalString(fields.latitude),
    longitude: optionalString(fields.longitude),
    expectedMinutes: optionalString(fields.expectedMinutes),
    durationMinutes: optionalString(fields.durationMinutes),
    offRouteMeters: optionalString(fields.offRouteMeters),
    status: optionalString(fields.status),
    updatedAt: Number(fields.updatedAt) || Date.now(),
  };
}

function publicGuardianState(state) {
  if (!state || typeof state !== "object") {
    return null;
  }
  return {
    ...buildGuardianStateFromFields(state),
    history: normalizeGuardianHistory(state.history),
  };
}

function guardianPushData(state) {
  const publicState = buildGuardianStateFromFields(state || {});
  const data = {};
  for (const [key, value] of Object.entries(publicState)) {
    data[key] = value === undefined || value === null ? "" : String(value);
  }
  return data;
}

function shouldRecordGuardianHistory(state) {
  const type = optionalString(state.type);
  if (type === "RETURN_LOCATION_UPDATE") {
    return false;
  }
  const status = optionalString(state.status);
  return status === "active" || status === "completed" || status === "deviated" || status === "danger";
}

function normalizeGuardianHistory(history) {
  if (!Array.isArray(history)) {
    return [];
  }
  return history
    .filter((entry) => entry && typeof entry === "object")
    .map((entry) => buildGuardianStateFromFields(entry))
    .filter((entry) => shouldRecordGuardianHistory(entry) && entry.updatedAt > 0)
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .slice(0, MAX_GUARDIAN_HISTORY_ITEMS);
}

function buildGuardianHistory(previousHistory, state) {
  const history = normalizeGuardianHistory(previousHistory);
  if (!shouldRecordGuardianHistory(state)) {
    return history;
  }

  const entry = buildGuardianStateFromFields(state);
  const duplicateIndex = history.findIndex(
    (item) => item.updatedAt === entry.updatedAt && item.status === entry.status && item.type === entry.type
  );
  if (duplicateIndex >= 0) {
    history[duplicateIndex] = entry;
  } else {
    history.unshift(entry);
  }

  return history
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .slice(0, MAX_GUARDIAN_HISTORY_ITEMS);
}

function cleanupExpiredGuardianPairings(now = Date.now()) {
  for (const [code, pairing] of guardianPairingCodes.entries()) {
    if (!pairing || pairing.expiresAt <= now) {
      guardianPairingCodes.delete(code);
    }
  }
}

function createGuardianPairingCode() {
  cleanupExpiredGuardianPairings();
  for (let index = 0; index < 20; index += 1) {
    const code = String(Math.floor(100000 + Math.random() * 900000));
    if (!guardianPairingCodes.has(code)) {
      return code;
    }
  }
  return String(Date.now()).slice(-6);
}

loadGuardianReturnStates();

function toRadians(value) {
  return (value * Math.PI) / 180;
}

function estimateDistanceMeters(originLatitude, originLongitude, destinationLatitude, destinationLongitude) {
  const earthRadiusMeters = 6371000;
  const deltaLatitude = toRadians(destinationLatitude - originLatitude);
  const deltaLongitude = toRadians(destinationLongitude - originLongitude);
  const originRadians = toRadians(originLatitude);
  const destinationRadians = toRadians(destinationLatitude);
  const a =
    Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2) +
    Math.cos(originRadians) *
      Math.cos(destinationRadians) *
      Math.sin(deltaLongitude / 2) *
      Math.sin(deltaLongitude / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return Math.round(earthRadiusMeters * c);
}

function encodePolyline(points) {
  let previousLatitude = 0;
  let previousLongitude = 0;
  let encoded = "";

  for (const point of points) {
    const latitude = Math.round(point.latitude * 1e5);
    const longitude = Math.round(point.longitude * 1e5);
    encoded += encodePolylineValue(latitude - previousLatitude);
    encoded += encodePolylineValue(longitude - previousLongitude);
    previousLatitude = latitude;
    previousLongitude = longitude;
  }

  return encoded;
}

function encodePolylineValue(value) {
  let coordinate = value < 0 ? ~(value << 1) : value << 1;
  let encoded = "";
  while (coordinate >= 0x20) {
    encoded += String.fromCharCode((0x20 | (coordinate & 0x1f)) + 63);
    coordinate >>= 5;
  }
  encoded += String.fromCharCode(coordinate + 63);
  return encoded;
}

function buildFallbackRoute(originLatitude, originLongitude, destinationLatitude, destinationLongitude, reason) {
  const distanceMeters = estimateDistanceMeters(
    originLatitude,
    originLongitude,
    destinationLatitude,
    destinationLongitude
  );
  const durationSeconds = Math.max(60, Math.round(distanceMeters / 1.2));
  return {
    ok: true,
    encodedPolyline: encodePolyline([
      { latitude: originLatitude, longitude: originLongitude },
      { latitude: destinationLatitude, longitude: destinationLongitude },
    ]),
    points: [
      { latitude: originLatitude, longitude: originLongitude },
      { latitude: destinationLatitude, longitude: destinationLongitude },
    ],
    distanceMeters,
    duration: `${durationSeconds}s`,
    fallback: true,
    routeMode: "line",
    fallbackReason: reason || "Kakao Map API did not return a walking route",
  };
}

function postJson(url, headers, payload) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify(payload);
    const request = https.request(
      url,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(body),
          ...headers,
        },
      },
      (response) => {
        let data = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          data += chunk;
        });
        response.on("end", () => {
          let json = {};
          try {
            json = data ? JSON.parse(data) : {};
          } catch (error) {
            reject(new Error(`Invalid JSON response: ${error.message}`));
            return;
          }
          resolve({ statusCode: response.statusCode || 0, json });
        });
      }
    );

    request.setTimeout(12000, () => {
      request.destroy(new Error("POST request timed out"));
    });
    request.on("error", reject);
    request.write(body);
    request.end();
  });
}

function getJson(url, headers) {
  return new Promise((resolve, reject) => {
    const request = https.get(
      url,
      {
        headers,
      },
      (response) => {
        let data = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          data += chunk;
        });
        response.on("end", () => {
          let json = {};
          try {
            json = data ? JSON.parse(data) : {};
          } catch (error) {
            reject(new Error(`Invalid JSON response: ${error.message}`));
            return;
          }
          resolve({ statusCode: response.statusCode || 0, json });
        });
      }
    );

    request.setTimeout(12000, () => {
      request.destroy(new Error("GET request timed out"));
    });
    request.on("error", reject);
  });
}

function parseWaypoints(value) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .slice(0, 5)
    .map((point) => ({
      latitude: optionalNumber(point && point.latitude),
      longitude: optionalNumber(point && point.longitude),
    }))
    .filter((point) => point.latitude !== null && point.longitude !== null);
}

const KAKAO_WALK_ROUTE_MODES = new Set(["BROAD_FIRST", "SHORTEST", "ACCESSIBLE"]);

function parseRouteMode(value) {
  const mode = optionalString(value).toUpperCase();
  return KAKAO_WALK_ROUTE_MODES.has(mode) ? mode : "BROAD_FIRST";
}

function appendUniquePoint(points, longitudeValue, latitudeValue) {
  const longitude = optionalNumber(longitudeValue);
  const latitude = optionalNumber(latitudeValue);
  if (latitude === null || longitude === null) {
    return;
  }
  const previous = points[points.length - 1];
  if (previous && previous.latitude === latitude && previous.longitude === longitude) {
    return;
  }
  points.push({ latitude, longitude });
}

function kakaoMapWalkSteps(route) {
  const steps = [];
  const legs = Array.isArray(route && route.legs) ? route.legs : [];
  for (const leg of legs) {
    if (Array.isArray(leg && leg.steps)) {
      steps.push(...leg.steps);
    }
  }
  return steps;
}

function extractKakaoMapWalkPoints(route) {
  const points = [];
  for (const step of kakaoMapWalkSteps(route)) {
    const path = step && step.path ? step.path : {};
    const pathPoints = Array.isArray(path.points) ? path.points : [];
    for (const point of pathPoints) {
      if (Array.isArray(point) && point.length >= 2) {
        appendUniquePoint(points, point[0], point[1]);
      }
    }
  }
  return points;
}

function extractKakaoMapWalkGuides(route) {
  const guides = [];
  for (const step of kakaoMapWalkSteps(route)) {
    const properties = step && step.properties ? step.properties : {};
    const text = optionalString(properties.guidance);
    if (!text) {
      continue;
    }

    const path = step && step.path ? step.path : {};
    const firstPoint = Array.isArray(path.points) && Array.isArray(path.points[0])
      ? path.points[0]
      : [];
    const longitude = optionalNumber(properties.x) ?? optionalNumber(firstPoint[0]);
    const latitude = optionalNumber(properties.y) ?? optionalNumber(firstPoint[1]);
    if (latitude === null || longitude === null) {
      continue;
    }

    guides.push({
      latitude,
      longitude,
      text,
      distanceMeters: optionalNumber(properties.distance) || 0,
      durationSeconds: optionalNumber(properties.time) || 0,
    });
  }
  return guides;
}

function buildKakaoMapWalkingRoute(route, routeMode, waypointCount) {
  const points = extractKakaoMapWalkPoints(route);
  if (points.length < 2) {
    return null;
  }
  const properties = route && route.properties ? route.properties : {};
  return {
    ok: true,
    encodedPolyline: encodePolyline(points),
    distanceMeters: optionalNumber(properties.totalDistance) || 0,
    duration: `${optionalNumber(properties.totalTime) || 0}s`,
    fallback: false,
    source: "kakao_map_walking",
    routeMode,
    waypointCount,
    landingUrl: optionalString(properties.landingUrl),
    points,
    guides: extractKakaoMapWalkGuides(route),
  };
}

async function computeRouteWithKakaoMapWalkingApi(
  apiKey,
  originLatitude,
  originLongitude,
  destinationLatitude,
  destinationLongitude,
  waypoints,
  routeMode
) {
  const params = new URLSearchParams({
    start_x: String(originLongitude),
    start_y: String(originLatitude),
    end_x: String(destinationLongitude),
    end_y: String(destinationLatitude),
    input_coord: "WGS84",
    output_coord: "WGS84",
    route_mode: routeMode,
  });
  if (waypoints.length > 0) {
    params.set("via_x", waypoints.map((point) => point.longitude).join(","));
    params.set("via_y", waypoints.map((point) => point.latitude).join(","));
  }

  const kakaoResponse = await getJson(`https://dapi.kakao.com/v2/routing/walk?${params}`, {
    Accept: "application/json",
    Authorization: `KakaoAK ${apiKey}`,
  });
  if (kakaoResponse.statusCode < 200 || kakaoResponse.statusCode >= 300) {
    return {
      route: null,
      reason:
        optionalString(kakaoResponse.json.msg) ||
        optionalString(kakaoResponse.json.message) ||
        `Kakao Map walking route request failed: ${kakaoResponse.statusCode}`,
    };
  }

  if (optionalString(kakaoResponse.json.status) !== "OK" || !kakaoResponse.json.route) {
    return {
      route: null,
      reason: optionalString(kakaoResponse.json.status) || "Kakao Map walking route returned no route",
    };
  }

  const routeBody = buildKakaoMapWalkingRoute(kakaoResponse.json.route, routeMode, waypoints.length);
  return routeBody
    ? { route: routeBody, reason: "" }
    : { route: null, reason: "Kakao Map walking route returned no geometry" };
}

function getOpenAiModel() {
  return optionalString(process.env.OPENAI_MODEL) || "gpt-5.2";
}

function getOpenAiTtsModel() {
  return optionalString(process.env.OPENAI_TTS_MODEL) || "gpt-4o-mini-tts";
}

function getOpenAiTtsVoice() {
  return optionalString(process.env.OPENAI_TTS_VOICE) || "marin";
}

function getModeTtsVoice(envName, fallbackVoice) {
  return optionalString(process.env[envName]) || optionalString(process.env.OPENAI_TTS_VOICE) || fallbackVoice;
}

function getModeTtsSpeed(envName, fallbackSpeed) {
  const value = optionalNumber(process.env[envName]);
  const globalValue = optionalNumber(process.env.OPENAI_TTS_SPEED);
  const speed = value || globalValue || fallbackSpeed;
  return Math.max(0.25, Math.min(4.0, speed));
}

function getOpenAiTtsProfile(mode) {
  const normalizedMode = optionalString(mode) || "보호자";
  const profiles = {
    보호자: {
      voice: getModeTtsVoice("OPENAI_TTS_VOICE_GUARDIAN", "cedar"),
      speed: getModeTtsSpeed("OPENAI_TTS_SPEED_GUARDIAN", 0.95),
      roleInstruction:
        "Sound like a calm trusted parent or guardian. Warm, steady, protective, and reassuring without sounding dramatic.",
    },
    친구: {
      voice: getModeTtsVoice("OPENAI_TTS_VOICE_FRIEND", "marin"),
      speed: getModeTtsSpeed("OPENAI_TTS_SPEED_FRIEND", 1.03),
      roleInstruction:
        "Sound like a close friend on a casual phone call. Friendly, relaxed, lightly upbeat, and natural.",
    },
    남자친구: {
      voice: getModeTtsVoice("OPENAI_TTS_VOICE_BOYFRIEND", "onyx"),
      speed: getModeTtsSpeed("OPENAI_TTS_SPEED_BOYFRIEND", 0.96),
      roleInstruction:
        "Sound like a caring boyfriend. Low, steady, close, and reassuring, but not overly romantic or theatrical.",
    },
    여자친구: {
      voice: getModeTtsVoice("OPENAI_TTS_VOICE_GIRLFRIEND", "nova"),
      speed: getModeTtsSpeed("OPENAI_TTS_SPEED_GIRLFRIEND", 1.02),
      roleInstruction:
        "Sound like a caring girlfriend. Soft, close, warm, and comforting, but still clear and practical.",
    },
    안내: {
      voice: getModeTtsVoice("OPENAI_TTS_VOICE_GUIDE", "sage"),
      speed: getModeTtsSpeed("OPENAI_TTS_SPEED_GUIDE", 0.98),
      roleInstruction:
        "Sound like a calm safety guide. Clear, concise, objective, and action-focused without sounding robotic.",
    },
  };
  return profiles[normalizedMode] || {
    voice: getOpenAiTtsVoice(),
    speed: getModeTtsSpeed("OPENAI_TTS_SPEED", 1.0),
    roleInstruction: "Sound natural, warm, and calm for a Korean safety phone call.",
  };
}

function extractOutputText(json) {
  if (typeof json.output_text === "string") {
    return json.output_text.trim();
  }
  if (!Array.isArray(json.output)) {
    return "";
  }
  return json.output
    .flatMap((item) => (Array.isArray(item.content) ? item.content : []))
    .map((content) => content.text || content.output_text || "")
    .join("")
    .trim();
}

function extractJsonObject(text) {
  const trimmed = optionalString(text)
    .replace(/^```json\s*/i, "")
    .replace(/^```\s*/i, "")
    .replace(/```$/i, "")
    .trim();
  try {
    return JSON.parse(trimmed);
  } catch (error) {
    const match = trimmed.match(/\{[\s\S]*\}/);
    if (!match) {
      return null;
    }
    try {
      return JSON.parse(match[0]);
    } catch (nestedError) {
      return null;
    }
  }
}

async function callOpenAiResponses(instructions, input, maxOutputTokens) {
  const apiKey = optionalString(process.env.OPENAI_API_KEY);
  if (!apiKey) {
    const error = new Error("OPENAI_API_KEY is required");
    error.statusCode = 500;
    throw error;
  }

  const response = await postJson(
    "https://api.openai.com/v1/responses",
    {
      Authorization: `Bearer ${apiKey}`,
    },
    {
      model: getOpenAiModel(),
      instructions,
      input,
      max_output_tokens: maxOutputTokens,
    }
  );

  if (response.statusCode < 200 || response.statusCode >= 300) {
    const error = new Error(
      (response.json.error && response.json.error.message) || `OpenAI request failed: ${response.statusCode}`
    );
    error.statusCode = response.statusCode;
    throw error;
  }

  return extractOutputText(response.json);
}

async function callOpenAiSpeech(input, mode) {
  const apiKey = optionalString(process.env.OPENAI_API_KEY);
  if (!apiKey) {
    const error = new Error("OPENAI_API_KEY is required");
    error.statusCode = 500;
    throw error;
  }

  const text = optionalString(input);
  if (!text) {
    const error = new Error("input is required");
    error.statusCode = 400;
    throw error;
  }

  const profile = getOpenAiTtsProfile(mode);
  const instructions = [
    "Speak in natural Korean for a phone-call style safety companion.",
    "Use a warm, calm, conversational tone with gentle intonation.",
    "Keep the pace moderate and pronunciation clear.",
    "Do not sound like an announcement, narrator, robot, or formal report.",
    "When reading 112, pronounce it as Korean emergency digits, 일 일이.",
    profile.roleInstruction,
    `Current relationship mode: ${optionalString(mode) || "보호자"}.`,
  ].join(" ");

  return postBinary(
    "https://api.openai.com/v1/audio/speech",
    {
      Authorization: `Bearer ${apiKey}`,
    },
    {
      model: getOpenAiTtsModel(),
      voice: profile.voice,
      input: text.slice(0, 4096),
      instructions,
      response_format: "mp3",
      speed: profile.speed,
    }
  );
}

function postBinary(url, headers, payload) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify(payload);
    const request = https.request(
      url,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(body),
          ...headers,
        },
      },
      (response) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("end", () => {
          const buffer = Buffer.concat(chunks);
          if (response.statusCode >= 200 && response.statusCode < 300) {
            resolve(buffer);
            return;
          }

          let message = `OpenAI speech request failed with status ${response.statusCode}`;
          try {
            const parsed = JSON.parse(buffer.toString("utf8"));
            message = parsed.error?.message || parsed.message || message;
          } catch (ignored) {
            const text = buffer.toString("utf8").trim();
            if (text) {
              message = text;
            }
          }
          const error = new Error(message);
          error.statusCode = response.statusCode;
          reject(error);
        });
      }
    );

    request.on("error", reject);
    request.write(body);
    request.end();
  });
}

function normalizeConversationMessages(messages) {
  if (!Array.isArray(messages)) {
    return [];
  }
  return messages
    .slice(-10)
    .map((message) => ({
      role: optionalString(message.role),
      content: optionalString(message.content),
    }))
    .filter((message) => ["user", "assistant"].includes(message.role) && message.content);
}

const AI_CHAT_INSTRUCTIONS = [
  "You are SafeWay, a Korean safety companion for a user walking home.",
  "The user may be pretending to be on a normal phone call so nearby people believe someone is with them.",
  "Keep replies natural for a voice call, short enough to speak aloud, and never mention that you are pretending unless the user asks.",
  "Write like spoken Korean, not written instructions. Avoid bullet-like phrasing, parentheses, symbols, URLs, and stiff report-style wording.",
  "Use short clauses with commas or periods so Android TTS has natural pauses.",
  "If mentioning the Korean emergency number, write it as 112 in JSON; the app will pronounce it naturally.",
  "Follow the requested mode exactly:",
  "- 보호자: concerned but calm, like a parent or trusted guardian. Give warm reassurance plus concrete safety actions.",
  "- 친구: casual and supportive, like a friend walking with the user. Use comfortable informal Korean.",
  "- 남자친구: close and steady, like a caring boyfriend. Be reassuring without being overly romantic or distracting.",
  "- 여자친구: close and steady, like a caring girlfriend. Be reassuring without being overly romantic or distracting.",
  "- 안내: objective and action-focused. Use less emotion and more direct safety instructions.",
  "If the user expresses fear, being followed, threat, injury, coercion, or asks for help, classify danger=true.",
  "For danger=true, prioritize moving to a bright public place, contacting a guardian, and calling 112 for immediate risk.",
  "Do not pretend to be police or emergency services. Do not tell the user to confront a suspicious person.",
  "If the user asks to call 112 or a guardian, set safetyAction to call_112 or call_guardian.",
  "Return JSON only with keys: reply, danger, safetyAction, summary.",
  "reply must be one or two short Korean sentences suitable for TTS.",
  "safetyAction must be one of normal, move_bright, call_guardian, call_112.",
  "summary must be one Korean sentence that can be saved in a return record.",
].join("\n");

const AI_SUMMARY_INSTRUCTIONS = [
  "Summarize a SafeWay walking-home AI companion conversation in Korean.",
  "Return JSON only with key summary.",
  "The summary must be one concise sentence for a return record.",
  "Mention danger guidance only if the conversation included fear, stalking, threat, or emergency advice.",
].join("\n");

app.get("/health", (req, res) => {
  res.json({ ok: true, service: "safeway-push-server" });
});

app.post("/guardians/pairing-code", (req, res) => {
  const guardianToken = optionalString(req.body.guardianToken);
  if (!guardianToken) {
    res.status(400).json({ ok: false, error: "guardianToken is required" });
    return;
  }

  const now = Date.now();
  const code = createGuardianPairingCode();
  const expiresAt = now + GUARDIAN_PAIRING_TTL_MS;
  guardianPairingCodes.set(code, {
    guardianToken,
    guardianName: optionalString(req.body.guardianName) || "보호자",
    guardianPhone: optionalString(req.body.guardianPhone),
    guardianRelation: optionalString(req.body.guardianRelation) || "보호자",
    createdAt: now,
    expiresAt,
  });

  res.json({
    ok: true,
    code,
    expiresAt,
    expiresInSeconds: Math.round(GUARDIAN_PAIRING_TTL_MS / 1000),
  });
});

app.post("/guardians/status", (req, res) => {
  const guardianToken = optionalString(req.body.guardianToken);
  if (!guardianToken) {
    res.status(400).json({ ok: false, error: "guardianToken is required" });
    return;
  }

  const state = publicGuardianState(guardianReturnStates.get(guardianToken));
  res.json({
    ok: true,
    hasStatus: Boolean(state),
    state,
  });
});

app.post("/guardians/link", async (req, res) => {
  const code = optionalString(req.body.code).replace(/[^0-9]/g, "");
  if (!code) {
    res.status(400).json({ ok: false, error: "code is required" });
    return;
  }

  cleanupExpiredGuardianPairings();
  const pairing = guardianPairingCodes.get(code);
  if (!pairing) {
    res.status(404).json({ ok: false, error: "pairing code is invalid or expired" });
    return;
  }
  guardianPairingCodes.delete(code);

  const linkedState = saveGuardianReturnState(
    pairing.guardianToken,
    buildGuardianStateFromFields({
      type: "GUARDIAN_LINKED",
      title: "SafeWay 보호자 연동 완료",
      body: "자녀 기기와 보호자 모니터가 연결되었습니다.",
      status: "linked",
    })
  );

  let linkNotificationSent = false;
  try {
    initFirebase();
    await admin.messaging().send({
      token: pairing.guardianToken,
      data: guardianPushData(linkedState),
      android: {
        priority: "high",
      },
    });
    linkNotificationSent = true;
  } catch (error) {
    console.error("Guardian link notification failed", error);
  }

  res.json({
    ok: true,
    guardianName: pairing.guardianName,
    guardianPhone: pairing.guardianPhone,
    guardianRelation: pairing.guardianRelation,
    guardianToken: pairing.guardianToken,
    linkNotificationSent,
  });
});

app.post("/guardians/unlink", async (req, res) => {
  const guardianToken = optionalString(req.body.guardianToken);
  if (!guardianToken) {
    res.status(400).json({ ok: false, error: "guardianToken is required" });
    return;
  }

  for (const [code, pairing] of guardianPairingCodes.entries()) {
    if (pairing && pairing.guardianToken === guardianToken) {
      guardianPairingCodes.delete(code);
    }
  }

  const unlinkedState = buildGuardianStateFromFields({
    type: "GUARDIAN_UNLINKED",
    title: "SafeWay 보호자 연동 해제",
    body: "자녀 기기에서 보호자 연동을 해제했습니다.",
    status: "unlinked",
  });
  let notificationSent = false;
  try {
    initFirebase();
    await admin.messaging().send({
      token: guardianToken,
      data: guardianPushData(unlinkedState),
      android: {
        priority: "high",
      },
    });
    notificationSent = true;
  } catch (error) {
    console.error("Guardian unlink notification failed", error);
  }

  guardianReturnStates.delete(guardianToken);
  persistGuardianReturnStates();
  res.json({ ok: true, notificationSent });
});

app.post("/ai/chat", async (req, res) => {
  const userText = optionalString(req.body.userText);
  const mode = optionalString(req.body.mode) || "보호자";
  const messages = normalizeConversationMessages(req.body.messages);

  if (!userText) {
    res.status(400).json({ ok: false, error: "userText is required" });
    return;
  }

  try {
    const outputText = await callOpenAiResponses(
      AI_CHAT_INSTRUCTIONS,
      JSON.stringify(
        {
          mode,
          userText,
          recentMessages: messages,
        },
        null,
        2
      ),
      500
    );
    const parsed = extractJsonObject(outputText) || {};
    const reply = optionalString(parsed.reply) || outputText || "지금은 밝고 사람이 많은 곳으로 이동하세요.";
    res.json({
      ok: true,
      reply,
      danger: Boolean(parsed.danger),
      safetyAction: optionalString(parsed.safetyAction) || "normal",
      summary: optionalString(parsed.summary) || "귀가 중 AI 안심 동행 대화를 진행함",
      model: getOpenAiModel(),
    });
  } catch (error) {
    console.error("OpenAI chat failed", error);
    res.status(error.statusCode || 500).json({
      ok: false,
      error: "OpenAI chat failed",
      detail: error.message,
    });
  }
});

app.post("/ai/speech", async (req, res) => {
  const input = optionalString(req.body.input);
  const mode = optionalString(req.body.mode) || "보호자";

  if (!input) {
    res.status(400).json({ ok: false, error: "input is required" });
    return;
  }

  try {
    const audio = await callOpenAiSpeech(input, mode);
    res.setHeader("Content-Type", "audio/mpeg");
    res.setHeader("Cache-Control", "no-store");
    res.send(audio);
  } catch (error) {
    console.error("OpenAI speech failed", error);
    res.status(error.statusCode || 500).json({
      ok: false,
      error: "OpenAI speech failed",
      detail: error.message,
    });
  }
});

app.post("/ai/summary", async (req, res) => {
  const messages = normalizeConversationMessages(req.body.messages);
  if (messages.length === 0) {
    res.status(400).json({ ok: false, error: "messages are required" });
    return;
  }

  try {
    const outputText = await callOpenAiResponses(
      AI_SUMMARY_INSTRUCTIONS,
      JSON.stringify({ messages }, null, 2),
      220
    );
    const parsed = extractJsonObject(outputText) || {};
    res.json({
      ok: true,
      summary: optionalString(parsed.summary) || outputText || "귀가 중 AI 안심 동행 통화를 사용함",
      model: getOpenAiModel(),
    });
  } catch (error) {
    console.error("OpenAI summary failed", error);
    res.status(error.statusCode || 500).json({
      ok: false,
      error: "OpenAI summary failed",
      detail: error.message,
    });
  }
});

app.post("/routes/compute", async (req, res) => {
  const apiKey = optionalString(process.env.KAKAO_REST_API_KEY);
  if (!apiKey) {
    res.status(500).json({ ok: false, error: "KAKAO_REST_API_KEY is required" });
    return;
  }

  const origin = req.body.origin || {};
  const destination = req.body.destination || {};
  const waypoints = parseWaypoints(req.body.waypoints);
  const routeMode = parseRouteMode(req.body.routeMode);
  const originLatitude = optionalNumber(origin.latitude);
  const originLongitude = optionalNumber(origin.longitude);
  const destinationLatitude = optionalNumber(destination.latitude);
  const destinationLongitude = optionalNumber(destination.longitude);

  if (
    originLatitude === null ||
    originLongitude === null ||
    destinationLatitude === null ||
    destinationLongitude === null
  ) {
    res.status(400).json({ ok: false, error: "origin and destination latitude/longitude are required" });
    return;
  }

  try {
    const walkingResult = await computeRouteWithKakaoMapWalkingApi(
      apiKey,
      originLatitude,
      originLongitude,
      destinationLatitude,
      destinationLongitude,
      waypoints,
      routeMode
    );
    if (walkingResult.route) {
      res.json(walkingResult.route);
      return;
    }

    res.json(
      buildFallbackRoute(
        originLatitude,
        originLongitude,
        destinationLatitude,
        destinationLongitude,
        walkingResult.reason
      )
    );
  } catch (error) {
    console.error("Kakao Map walking route API failed", error);
    res.status(500).json({
      ok: false,
      error: "Kakao Map walking route API failed",
      detail: error.message,
    });
  }
});

app.post("/alerts/return-started", async (req, res) => {
  const guardianToken = optionalString(req.body.guardianToken);
  if (!guardianToken) {
    res.status(400).json({ ok: false, error: "guardianToken is required" });
    return;
  }

  const title = optionalString(req.body.title) || "SafeWay 안심귀가 알림";
  const body = optionalString(req.body.body) || "안심귀가가 시작되었습니다.";
  const mapsLink = optionalString(req.body.mapsLink);
  const routeLink = optionalString(req.body.routeLink);
  const routePoints = optionalString(req.body.routePoints);
  const destination = optionalString(req.body.destination);
  const latitude = optionalString(req.body.latitude);
  const longitude = optionalString(req.body.longitude);
  const expectedMinutes = optionalString(req.body.expectedMinutes);
  const status = optionalString(req.body.status) || "active";
  const state = saveGuardianReturnState(
    guardianToken,
    buildGuardianStateFromFields({
      type: "RETURN_STARTED",
      title,
      body,
      mapsLink,
      routeLink,
      routePoints,
      destination,
      latitude,
      longitude,
      expectedMinutes,
      status,
    })
  );

  try {
    initFirebase();
    const message = {
      token: guardianToken,
      data: {
        ...guardianPushData(state),
      },
      android: {
        priority: "high",
      },
    };

    const messageId = await admin.messaging().send(message);
    res.json({ ok: true, messageId });
  } catch (error) {
    console.error("FCM send failed", error);
    res.status(500).json({
      ok: false,
      error: "FCM send failed",
      detail: error.message,
    });
  }
});

app.post("/alerts/return-location-update", (req, res) => {
  const guardianToken = optionalString(req.body.guardianToken);
  if (!guardianToken) {
    res.status(400).json({ ok: false, error: "guardianToken is required" });
    return;
  }

  const previous = guardianReturnStates.get(guardianToken) || {};
  const previousStatus = optionalString(previous.status);
  if (previousStatus === "completed") {
    res.json({ ok: true, ignored: true, state: publicGuardianState(previous) });
    return;
  }

  const stickyAlertStatus = previousStatus === "deviated" || previousStatus === "danger";
  const status = stickyAlertStatus ? previousStatus : optionalString(req.body.status) || "active";
  const title = stickyAlertStatus
    ? optionalString(previous.title) || "SafeWay 보호자 알림"
    : optionalString(req.body.title) || "SafeWay 실시간 위치";
  const body = stickyAlertStatus
    ? optionalString(previous.body) || "마지막 위치를 확인하고 바로 연락하세요."
    : optionalString(req.body.body) || "안심귀가 중입니다. 위치가 실시간으로 업데이트되고 있습니다.";

  const state = saveGuardianReturnState(
    guardianToken,
    buildGuardianStateFromFields({
      type: "RETURN_LOCATION_UPDATE",
      title,
      body,
      mapsLink: optionalString(req.body.mapsLink),
      routeLink: optionalString(req.body.routeLink),
      routePoints: optionalString(req.body.routePoints),
      destination: optionalString(req.body.destination),
      latitude: optionalString(req.body.latitude),
      longitude: optionalString(req.body.longitude),
      expectedMinutes: optionalString(req.body.expectedMinutes),
      offRouteMeters: stickyAlertStatus ? optionalString(previous.offRouteMeters) : "",
      status,
    })
  );

  res.json({ ok: true, state: publicGuardianState(state) });
});

app.post("/alerts/return-completed", async (req, res) => {
  const guardianToken = optionalString(req.body.guardianToken);
  if (!guardianToken) {
    res.status(400).json({ ok: false, error: "guardianToken is required" });
    return;
  }

  const title = optionalString(req.body.title) || "SafeWay 귀가 완료";
  const body = optionalString(req.body.body) || "안심귀가가 완료되었습니다.";
  const routeLink = optionalString(req.body.routeLink);
  const routePoints = optionalString(req.body.routePoints);
  const destination = optionalString(req.body.destination);
  const expectedMinutes = optionalString(req.body.expectedMinutes);
  const durationMinutes = optionalString(req.body.durationMinutes);
  const state = saveGuardianReturnState(
    guardianToken,
    buildGuardianStateFromFields({
      type: "RETURN_COMPLETED",
      title,
      body,
      mapsLink: "",
      routeLink,
      routePoints,
      destination,
      latitude: "",
      longitude: "",
      expectedMinutes,
      durationMinutes,
      status: "completed",
    })
  );

  try {
    initFirebase();
    const messageId = await admin.messaging().send({
      token: guardianToken,
      data: {
        ...guardianPushData(state),
      },
      android: {
        priority: "high",
      },
    });
    res.json({ ok: true, messageId });
  } catch (error) {
    console.error("FCM completion send failed", error);
    res.status(500).json({
      ok: false,
      error: "FCM completion send failed",
      detail: error.message,
    });
  }
});

app.post("/alerts/route-deviation", async (req, res) => {
  const guardianToken = optionalString(req.body.guardianToken);
  if (!guardianToken) {
    res.status(400).json({ ok: false, error: "guardianToken is required" });
    return;
  }

  const title = optionalString(req.body.title) || "SafeWay 경로 이탈 감지";
  const offRouteMeters = optionalString(req.body.offRouteMeters);
  const body =
    optionalString(req.body.body) ||
    (offRouteMeters
      ? `자녀가 설정한 귀가 경로에서 약 ${offRouteMeters}m 벗어났습니다.`
      : "자녀가 설정한 귀가 경로에서 벗어난 것 같습니다.");
  const mapsLink = optionalString(req.body.mapsLink);
  const routeLink = optionalString(req.body.routeLink);
  const routePoints = optionalString(req.body.routePoints);
  const destination = optionalString(req.body.destination);
  const latitude = optionalString(req.body.latitude);
  const longitude = optionalString(req.body.longitude);
  const expectedMinutes = optionalString(req.body.expectedMinutes);
  const state = saveGuardianReturnState(
    guardianToken,
    buildGuardianStateFromFields({
      type: "ROUTE_DEVIATION",
      title,
      body,
      mapsLink,
      routeLink,
      routePoints,
      destination,
      latitude,
      longitude,
      expectedMinutes,
      offRouteMeters,
      status: "deviated",
    })
  );

  try {
    initFirebase();
    const messageId = await admin.messaging().send({
      token: guardianToken,
      data: {
        ...guardianPushData(state),
      },
      android: {
        priority: "high",
      },
    });
    res.json({ ok: true, messageId });
  } catch (error) {
    console.error("FCM route deviation send failed", error);
    res.status(500).json({
      ok: false,
      error: "FCM route deviation send failed",
      detail: error.message,
    });
  }
});

app.post("/alerts/ai-danger", async (req, res) => {
  const guardianToken = optionalString(req.body.guardianToken);
  if (!guardianToken) {
    res.status(400).json({ ok: false, error: "guardianToken is required" });
    return;
  }

  const title = optionalString(req.body.title) || "SafeWay AI 위험 감지";
  const body =
    optionalString(req.body.body) ||
    "자녀가 AI 통화 중 위험 신호를 보냈습니다. 현재 상태를 확인하세요.";
  const mapsLink = optionalString(req.body.mapsLink);
  const routeLink = optionalString(req.body.routeLink);
  const routePoints = optionalString(req.body.routePoints);
  const destination = optionalString(req.body.destination);
  const latitude = optionalString(req.body.latitude);
  const longitude = optionalString(req.body.longitude);
  const expectedMinutes = optionalString(req.body.expectedMinutes);
  const state = saveGuardianReturnState(
    guardianToken,
    buildGuardianStateFromFields({
      type: "AI_DANGER",
      title,
      body,
      mapsLink,
      routeLink,
      routePoints,
      destination,
      latitude,
      longitude,
      expectedMinutes,
      status: "danger",
    })
  );

  try {
    initFirebase();
    const messageId = await admin.messaging().send({
      token: guardianToken,
      data: {
        ...guardianPushData(state),
      },
      android: {
        priority: "high",
      },
    });
    res.json({ ok: true, messageId });
  } catch (error) {
    console.error("FCM AI danger send failed", error);
    res.status(500).json({
      ok: false,
      error: "FCM AI danger send failed",
      detail: error.message,
    });
  }
});

app.post("/alerts/test", async (req, res) => {
  const guardianToken = optionalString(req.body.guardianToken);
  if (!guardianToken) {
    res.status(400).json({ ok: false, error: "guardianToken is required" });
    return;
  }
  const state = saveGuardianReturnState(
    guardianToken,
    buildGuardianStateFromFields({
      type: "TEST",
      title: "SafeWay 테스트 알림",
      body: "보호자 앱 푸시 알림 연결이 정상입니다.",
      status: "notice",
    })
  );

  try {
    initFirebase();
    const messageId = await admin.messaging().send({
      token: guardianToken,
      data: {
        ...guardianPushData(state),
      },
      android: {
        priority: "high",
      },
    });
    res.json({ ok: true, messageId });
  } catch (error) {
    console.error("FCM test send failed", error);
    res.status(500).json({
      ok: false,
      error: "FCM test send failed",
      detail: error.message,
    });
  }
});

const port = Number(process.env.PORT || 8080);
const host = process.env.HOST || "0.0.0.0";
app.listen(port, host, () => {
  console.log(`SafeWay push server listening on ${host}:${port}`);
});
