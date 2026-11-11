import { strict as assert } from 'node:assert';
import { createPublicKey, generateKeyPairSync, verify } from 'node:crypto';
import { test } from 'node:test';
import { FCM_MAX_DATA_BYTES, FcmClient, fcmDataBytes, fcmMessageFor, fcmResult, serviceAccountJwt } from '../src/fcm.mjs';
import { liveActivityPush } from '../src/live-activities.mjs';
import { tokenChallengePush } from '../src/token-proof.mjs';
import { callPushFor, isPrivateHost, locationPushFor, notificationFor, phoneBackgroundPushFor, validateDeviceToken } from '../src/server.mjs';

// A real-looking FCM registration token: instance id, colon, base64url.
const FCM_TOKEN = `dGhpcyBpcyBub3Q6YSByZWFs:APA91b${'Ab_-9'.repeat(30)}`;
const APNS_TOKEN = 'AB'.repeat(32);
const NOW = 1_800_000_000;

const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const account = {
  type: 'service_account',
  project_id: 'hermes-test',
  private_key_id: 'kid-123',
  private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }),
  client_email: 'relay@hermes-test.iam.gserviceaccount.com',
  token_uri: 'https://oauth2.googleapis.com/token',
};

const preferences = { enabled: true, show_previews: true, decision_cards: true, completion_sound: true };
const gateway = { id: '11111111-2222-4333-8444-555555555555', dashboardId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee' };

function envelope(eventId, ctChars = 2600) {
  return { v: 1, kid: 'a'.repeat(32), msg: eventId, iat: NOW, tok: 'b'.repeat(16), z: 0, req: '', n: 'c'.repeat(16), ct: 'd'.repeat(ctChars) };
}

function assertStrings(data) {
  for (const [key, value] of Object.entries(data)) assert.equal(typeof value, 'string', `${key} is a string`);
}

// ── token validation ────────────────────────────────────────────────────

test('APNs tokens stay hex, lowercased; FCM tokens are kept exactly', () => {
  assert.equal(validateDeviceToken(APNS_TOKEN), APNS_TOKEN.toLowerCase());
  assert.equal(validateDeviceToken(`<${APNS_TOKEN}>`, 'apns'), APNS_TOKEN.toLowerCase());
  assert.equal(validateDeviceToken(FCM_TOKEN, 'fcm'), FCM_TOKEN);
});

test('each platform refuses the other platform\'s tokens and malformed ones', () => {
  const refused = (value, platform) => assert.throws(() => validateDeviceToken(value, platform), { message: 'invalid_device_token' });
  refused(FCM_TOKEN, 'apns');
  refused(APNS_TOKEN, 'fcm'); // 64 chars: too short for FCM
  refused(`${FCM_TOKEN} `, 'fcm');
  refused(`${FCM_TOKEN.slice(0, 120)}/x`, 'fcm');
  refused('a'.repeat(401), 'fcm');
  refused(undefined, 'fcm');
  refused(12345, 'fcm');
});

test('plain-HTTP PUBLIC_URL hosts: LAN and Tailscale only', () => {
  for (const host of ['192.168.1.50', '10.0.0.5', '172.20.1.1', '100.101.102.103', '127.0.0.1', 'localhost', 'myhost.lan', 'myhost.tail1234.ts.net']) assert.ok(isPrivateHost(host), host);
  for (const host of ['8.8.8.8', '172.32.0.1', '100.128.0.1', 'push.example.com', 'lan.example.com']) assert.ok(!isPrivateHost(host), host);
});

// ── OAuth JWT ───────────────────────────────────────────────────────────

test('the service-account JWT is RS256 with the FCM scope, signed by the key', () => {
  const jwt = serviceAccountJwt(account, privateKey, NOW);
  const [header, claims, signature] = jwt.split('.');
  assert.deepEqual(JSON.parse(Buffer.from(header, 'base64url')), { alg: 'RS256', typ: 'JWT', kid: 'kid-123' });
  assert.deepEqual(JSON.parse(Buffer.from(claims, 'base64url')), {
    iss: account.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: NOW,
    exp: NOW + 3600,
  });
  const publicKey = createPublicKey(privateKey);
  assert.ok(verify('RSA-SHA256', Buffer.from(`${header}.${claims}`), publicKey, Buffer.from(signature, 'base64url')));
  assert.ok(!verify('RSA-SHA256', Buffer.from(`${header}.${claims}x`), publicKey, Buffer.from(signature, 'base64url')));
});

test('FcmClient exchanges the JWT once, caches the token and posts v1 messages', async () => {
  const calls = [];
  const fetch = async (url, init) => {
    calls.push({ url, init });
    if (url === account.token_uri) return Response.json({ access_token: 'ya29.test', expires_in: 3599, token_type: 'Bearer' });
    return Response.json({ name: 'projects/hermes-test/messages/1' });
  };
  const client = new FcmClient({ account, fetch });
  const message = fcmMessageFor(FCM_TOKEN, tokenChallengePush('n'.repeat(43), { ttlSeconds: 600, nowSeconds: NOW }), { nowSeconds: NOW });
  assert.deepEqual(await client.send(message), { ok: true, status: 200, reason: null });
  assert.deepEqual(await client.send(message), { ok: true, status: 200, reason: null });
  assert.equal(calls.filter((call) => call.url === account.token_uri).length, 1, 'token cached');
  const form = new URLSearchParams(calls[0].init.body);
  assert.equal(form.get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
  assert.equal(form.get('assertion').split('.').length, 3);
  const send = calls[1];
  assert.equal(send.url, 'https://fcm.googleapis.com/v1/projects/hermes-test/messages:send');
  assert.equal(send.init.headers.authorization, 'Bearer ya29.test');
  assert.deepEqual(JSON.parse(send.init.body), { message });
});

test('FCM errors read like APNs answers, and an oversized message never leaves', async () => {
  const fcmError = (status, code, message = '') => ({ error: { code: status, status: code, message, details: [{ '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: code }] } });
  assert.deepEqual(fcmResult(404, fcmError(404, 'UNREGISTERED')), { ok: false, status: 410, reason: 'Unregistered' });
  assert.deepEqual(fcmResult(403, fcmError(403, 'SENDER_ID_MISMATCH')), { ok: false, status: 403, reason: 'DeviceTokenNotForTopic' });
  assert.deepEqual(fcmResult(400, fcmError(400, 'INVALID_ARGUMENT', 'The registration token is not a valid FCM registration token')), { ok: false, status: 400, reason: 'BadDeviceToken' });
  assert.deepEqual(fcmResult(400, fcmError(400, 'INVALID_ARGUMENT', 'Invalid value at message.data')), { ok: false, status: 400, reason: 'INVALID_ARGUMENT' });
  assert.deepEqual(fcmResult(503, fcmError(503, 'UNAVAILABLE')), { ok: false, status: 503, reason: 'UNAVAILABLE' });

  let fetched = 0;
  const client = new FcmClient({ account, fetch: async () => { fetched += 1; return Response.json({}); } });
  const result = await client.send({ token: FCM_TOKEN, data: { blob: 'x'.repeat(FCM_MAX_DATA_BYTES) } });
  assert.deepEqual(result, { ok: false, status: 413, reason: 'PayloadTooLarge' });
  assert.equal(fetched, 0);
});

// ── APNs → FCM mapping ──────────────────────────────────────────────────

test('a notification maps to a high-priority data-only message of strings', () => {
  const event = { type: 'response.ready', eventId: 'evt-00000001', title: 'Done', body: 'All finished', sessionId: 's1', profile: 'main' };
  const push = notificationFor(event, preferences, gateway);
  const message = fcmMessageFor(FCM_TOKEN, push, { nowSeconds: NOW });
  assert.equal(message.token, FCM_TOKEN);
  assert.equal(message.notification, undefined, 'data-only');
  assert.deepEqual(message.android, { priority: 'high' });
  assertStrings(message.data);
  assert.equal(message.data.type, 'response.ready');
  assert.equal(message.data.push_type, 'alert');
  // What APNs would get on the wire, key for key.
  const wire = JSON.parse(JSON.stringify(push.payload));
  for (const key of ['aps', 'conduit', 'body']) assert.deepEqual(JSON.parse(message.data[key]), wire[key]);
  assert.equal(message.data.collapse_id, push.collapseId);
  assert.equal(message.data.thread_id, push.threadId);
});

test('the E2E envelope rides in data unchanged, inside the 4 KB budget', () => {
  const event = { type: 'input.needed', eventId: 'evt-00000002', e2e: envelope('evt-00000002') };
  const push = notificationFor(event, preferences, gateway);
  assert.ok(push.payload.conduit_e2e, 'the largest envelope still fits the APNs guard');
  const message = fcmMessageFor(FCM_TOKEN, push, { nowSeconds: NOW });
  assertStrings(message.data);
  assert.deepEqual(JSON.parse(message.data.conduit_e2e), event.e2e);
  assert.equal(JSON.parse(message.data.aps)['mutable-content'], 1);
  assert.ok(fcmDataBytes(message.data) <= FCM_MAX_DATA_BYTES, `${fcmDataBytes(message.data)} bytes`);
});

test('every payload the APNs guard lets through fits FCM\'s data cap', () => {
  // Clarify batches of growing size: each is either under the 3,800-byte
  // APNs guard or degraded to the routing stub, and must map under 4,096.
  const sessionId = `agent:main~${'x'.repeat(480)}`;
  for (let count = 1; count <= 8; count += 1) {
    const questions = Array.from({ length: count }, (_, index) => ({ qid: `q${index}`, question: 'Q'.repeat(400), choices: Array.from({ length: 8 }, () => 'c'.repeat(80)) }));
    const event = { type: 'input.needed', eventId: `evt-batch-${count}`, title: 'T'.repeat(120), body: 'B'.repeat(500), sessionId, profile: 'p'.repeat(80), gateway: 'g'.repeat(80), decision: { kind: 'clarify', request_id: 'r'.repeat(128), question: 'Q'.repeat(500), questions } };
    const push = notificationFor(event, preferences, gateway);
    const data = fcmMessageFor(FCM_TOKEN, push, { nowSeconds: NOW }).data;
    assert.ok(Buffer.byteLength(JSON.stringify(push.payload)) <= 3800 || !push.payload.body.conduit.decision);
    assert.ok(fcmDataBytes(data) <= FCM_MAX_DATA_BYTES, `${count} questions: ${fcmDataBytes(data)} bytes`);
  }
});

test('a ring maps to call.requested with push_type voip and its TTL', () => {
  const event = { type: 'call.requested', eventId: 'evt-00000003', sessionId: 's1', call: { id: 'call-0001', kind: 'done', session_ids: ['s1'] } };
  const push = callPushFor(event, preferences, gateway, { topic: 'com.example', nowSeconds: NOW });
  const message = fcmMessageFor(FCM_TOKEN, push, { nowSeconds: NOW });
  assert.equal(message.data.type, 'call.requested');
  assert.equal(message.data.push_type, 'voip');
  assert.equal(message.data.aps, undefined, 'an empty aps is left out');
  assert.equal(JSON.parse(message.data.conduit).sent_at, NOW);
  assert.equal(message.android.ttl, `${24 * 60 * 60}s`);
});

test('location requests, background changes, live activities and challenges keep their kind', () => {
  const location = fcmMessageFor(FCM_TOKEN, locationPushFor({ type: 'location.requested', profile: 'main', locate: { id: `l_${'a'.repeat(16)}` } }, gateway, { topic: 'com.example', nowSeconds: NOW }), { nowSeconds: NOW });
  assert.equal(location.data.type, 'location.requested');
  assert.equal(location.data.push_type, 'location');
  assert.equal(location.android.ttl, '60s');

  const background = fcmMessageFor(FCM_TOKEN, phoneBackgroundPushFor({ type: 'phone.changes', e2e: envelope('evt-00000004', 100) }, gateway, { topic: 'com.example', nowSeconds: NOW }), { nowSeconds: NOW });
  assert.equal(background.data.type, 'phone.changes');
  assert.equal(background.data.push_type, 'background');
  assert.equal(background.android.priority, 'high');
  assert.equal(JSON.parse(background.data.conduit_e2e).ct, 'd'.repeat(100));

  const activityId = 'f'.repeat(64);
  const activity = fcmMessageFor(FCM_TOKEN, liveActivityPush({ phase: 'tool', tool: 'web', tools: 3, context: 40 }, undefined, { nowSeconds: NOW }), { activity: activityId, nowSeconds: NOW });
  assert.equal(activity.data.type, 'live_activity');
  assert.equal(activity.data.activity, activityId);
  assert.deepEqual(JSON.parse(activity.data.aps)['content-state'], { phase: 'tool', tools: 3, tool: 'web', context: 40 });

  const nonce = 'N'.repeat(43);
  const challenge = fcmMessageFor(FCM_TOKEN, tokenChallengePush(nonce, { ttlSeconds: 600, nowSeconds: NOW }), { nowSeconds: NOW });
  assert.equal(challenge.data.type, 'token_challenge');
  assert.equal(challenge.data.conduit_token_challenge, nonce, 'the nonce stays a bare string');
  assert.equal(challenge.android.ttl, '600s');
});
