import { createPrivateKey, createSign } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { TOKEN_CHALLENGE_KEY } from './token-proof.mjs';

// Firebase Cloud Messaging (HTTP v1) for Android installations: the same
// pushes the relay sends through APNs, as high-priority data-only messages.
// Data-only, so the app's FirebaseMessagingService always runs and decides
// what to show (an E2E envelope can only be opened on the phone).
//
// The APNs-shaped push the server builds (`{ payload, pushType, priority,
// expiration, collapseId, threadId }`) is mapped by fcmMessageFor: every
// top-level payload key becomes a data key, objects JSON-encoded (FCM data
// values must be strings), plus `type` and `push_type` for routing.

const OAUTH_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const DEFAULT_TOKEN_URI = 'https://oauth2.googleapis.com/token';
const DEFAULT_FCM_ORIGIN = 'https://fcm.googleapis.com';
const SEND_TIMEOUT_MS = 10_000;
// FCM refuses a message whose data keys and values pass 4096 bytes.
export const FCM_MAX_DATA_BYTES = 4096;
// FCM registration tokens: base64url-ish with a `:` after the instance id.
const FCM_TOKEN_PATTERN = /^[A-Za-z0-9_:-]{100,400}$/;

export function isFcmToken(value) {
  return typeof value === 'string' && FCM_TOKEN_PATTERN.test(value);
}

// The FCM v1 message for an APNs-shaped push. `activity` is the Live
// Activity id the push updates (live-activities.mjs), when it is one.
export function fcmMessageFor(token, push, { activity, nowSeconds = Math.floor(Date.now() / 1000) } = {}) {
  const { aps, ...rest } = push.payload ?? {};
  const data = { type: fcmType(push), push_type: push.pushType || 'alert' };
  if (aps && Object.keys(aps).length) data.aps = JSON.stringify(aps);
  for (const [key, value] of Object.entries(rest)) {
    if (value === undefined || value === null) continue;
    data[key] = typeof value === 'string' ? value : JSON.stringify(value);
  }
  // Notification tag and grouping on the phone (APNs collapse id / thread).
  if (push.collapseId) data.collapse_id = push.collapseId;
  if (push.threadId) data.thread_id = push.threadId;
  if (activity) data.activity = activity;
  const ttl = push.expiration !== undefined ? Math.max(0, push.expiration - nowSeconds) : undefined;
  return {
    token,
    data,
    android: { priority: 'high', ...(ttl !== undefined ? { ttl: `${ttl}s` } : {}) },
  };
}

function fcmType(push) {
  if (push.pushType === 'liveactivity') return 'live_activity';
  if (push.payload?.[TOKEN_CHALLENGE_KEY]) return 'token_challenge';
  return push.payload?.conduit?.type ?? 'unknown';
}

// What FCM counts against its 4 KB data cap.
export function fcmDataBytes(data) {
  let bytes = 0;
  for (const [key, value] of Object.entries(data)) bytes += Buffer.byteLength(key) + Buffer.byteLength(value);
  return bytes;
}

// FCM's answer in the shape the server already reads from APNs
// ({ ok, status, reason }), so a dead token is handled the same way.
export function fcmResult(status, body) {
  if (status === 200) return { ok: true, status, reason: null };
  const error = body?.error ?? {};
  const code = (error.details ?? []).find((detail) => detail?.errorCode)?.errorCode ?? error.status ?? null;
  if (code === 'UNREGISTERED' || status === 404) return { ok: false, status: 410, reason: 'Unregistered' };
  if (code === 'SENDER_ID_MISMATCH') return { ok: false, status, reason: 'DeviceTokenNotForTopic' };
  // INVALID_ARGUMENT is also a malformed message; only a token complaint
  // retires the token.
  if (code === 'INVALID_ARGUMENT' && /registration token/i.test(String(error.message ?? ''))) return { ok: false, status, reason: 'BadDeviceToken' };
  return { ok: false, status, reason: code };
}

// An RS256 JWT for Google's OAuth token endpoint (service-account flow).
export function serviceAccountJwt(account, privateKey, nowSeconds = Math.floor(Date.now() / 1000)) {
  const header = base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT', ...(account.private_key_id ? { kid: account.private_key_id } : {}) }));
  const claims = base64url(JSON.stringify({
    iss: account.client_email,
    scope: OAUTH_SCOPE,
    aud: account.token_uri || DEFAULT_TOKEN_URI,
    iat: nowSeconds,
    exp: nowSeconds + 3600,
  }));
  const signingInput = `${header}.${claims}`;
  const signer = createSign('RSA-SHA256');
  signer.update(signingInput);
  signer.end();
  return `${signingInput}.${signer.sign(privateKey).toString('base64url')}`;
}

export class FcmClient {
  // `fetch` and `origin` exist so tests can stand in for Google.
  constructor({ serviceAccountPath, account, fetch: fetchImpl = globalThis.fetch, origin = DEFAULT_FCM_ORIGIN }) {
    this.account = account ?? JSON.parse(readFileSync(serviceAccountPath, 'utf8'));
    for (const field of ['project_id', 'client_email', 'private_key']) {
      if (!this.account[field]) throw new Error(`FCM service account is missing ${field}.`);
    }
    this.privateKey = createPrivateKey(this.account.private_key);
    this.fetch = fetchImpl;
    this.origin = origin;
    this.cachedToken = null;
    this.tokenExpiresAt = 0;
    this.pendingToken = null;
  }

  async send(message) {
    if (fcmDataBytes(message.data) > FCM_MAX_DATA_BYTES) return { ok: false, status: 413, reason: 'PayloadTooLarge' };
    const accessToken = await this.accessToken();
    const response = await this.fetch(`${this.origin}/v1/projects/${encodeURIComponent(this.account.project_id)}/messages:send`, {
      method: 'POST',
      headers: { authorization: `Bearer ${accessToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ message }),
      signal: AbortSignal.timeout(SEND_TIMEOUT_MS),
    });
    const body = await response.json().catch(() => null);
    // A token Google stopped honouring before it expired: fetch a new one next time.
    if (response.status === 401) this.cachedToken = null;
    return fcmResult(response.status, body);
  }

  // Cached until a minute before it expires; concurrent sends share one fetch.
  async accessToken() {
    if (this.cachedToken && Date.now() < this.tokenExpiresAt) return this.cachedToken;
    this.pendingToken ??= this.fetchAccessToken().finally(() => { this.pendingToken = null; });
    return this.pendingToken;
  }

  async fetchAccessToken() {
    const response = await this.fetch(this.account.token_uri || DEFAULT_TOKEN_URI, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion: serviceAccountJwt(this.account, this.privateKey),
      }).toString(),
      signal: AbortSignal.timeout(SEND_TIMEOUT_MS),
    });
    const body = await response.json().catch(() => null);
    if (!response.ok || typeof body?.access_token !== 'string') {
      throw new Error(`fcm oauth token exchange failed (${response.status}${body?.error ? ` ${body.error}` : ''})`);
    }
    const expiresIn = Number(body.expires_in) || 3600;
    this.cachedToken = body.access_token;
    this.tokenExpiresAt = Date.now() + Math.max(0, expiresIn - 60) * 1000;
    return this.cachedToken;
  }
}

function base64url(value) {
  return Buffer.from(value).toString('base64url');
}
