import { strict as assert } from 'node:assert';
import { generateKeyPairSync, randomBytes } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer as createNetServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { after, before, test } from 'node:test';

// An Android (FCM) phone through a real relay process with no APNs key at
// all: token proof by FCM data message, registration, pairing, and every
// kind of push arriving as an FCM data message. The APNs test seam stands
// in for Google, recording the FCM message beside each push.

const dir = mkdtempSync(join(tmpdir(), 'conduit-relay-fcm-'));
const dataPath = join(dir, 'relay-data.json');
const capturePath = join(dir, 'capture.jsonl');
const accountPath = join(dir, 'service-account.json');
// A throwaway service account: the seam means it is never sent anywhere.
const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
writeFileSync(accountPath, JSON.stringify({
  type: 'service_account',
  project_id: 'hermes-test',
  private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }),
  client_email: 'relay@hermes-test.iam.gserviceaccount.com',
}));

const FCM_TOKEN = `cHhvbmU6aW5zdGFuY2U:APA91b${randomBytes(105).toString('base64url')}`;
let child;
let base;
let device;
let gatewayCredential;

function freePort() {
  return new Promise((resolve, reject) => {
    const probe = createNetServer();
    probe.listen(0, '127.0.0.1', () => {
      const { port } = probe.address();
      probe.close(() => resolve(port));
    });
    probe.on('error', reject);
  });
}

async function api(path, { method = 'GET', body, credential } = {}) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: {
      ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
      ...(credential ? { authorization: `Bearer ${credential}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: response.status, json: await response.json().catch(() => null) };
}

function captured() {
  if (!existsSync(capturePath)) return [];
  return readFileSync(capturePath, 'utf8').split('\n').filter(Boolean).map((line) => JSON.parse(line));
}

// The FCM messages sent while `action` ran (or soon after, for async sends).
async function fcmSent(action, { wait = 0 } = {}) {
  const before = captured().length;
  const result = await action();
  const deadline = Date.now() + wait;
  while (captured().length === before && Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 50));
  return { result, messages: captured().slice(before).map((entry) => entry.notification.fcm) };
}

let eventCount = 0;
function sendEvent(body) {
  eventCount += 1;
  return api('/v1/events', { method: 'POST', credential: gatewayCredential, body: { event_id: `fcm-event-${eventCount}`, ...body } });
}

before(async () => {
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  child = spawn(process.execPath, ['src/server.mjs'], {
    cwd: fileURLToPath(new URL('..', import.meta.url)),
    env: {
      ...Object.fromEntries(Object.entries(process.env).filter(([name]) => !name.startsWith('APNS_'))),
      HOST: '127.0.0.1',
      PORT: String(port),
      // Plain HTTP on a LAN-style address, as on a home server.
      PUBLIC_URL: base,
      DATA_PATH: dataPath,
      FCM_SERVICE_ACCOUNT: accountPath,
      APNS_MODE: 'accept',
      APNS_CAPTURE_PATH: capturePath,
      RELAY_TOKEN_PROOF: '',
      RELAY_TEST_SEAMS: '1',
    },
    stdio: 'ignore',
  });
  const deadline = Date.now() + 10_000;
  for (;;) {
    try {
      if ((await fetch(`${base}/healthz`)).ok) break;
    } catch {}
    if (Date.now() > deadline) throw new Error('relay did not start');
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
});

after(async () => {
  if (child && child.exitCode === null) {
    child.kill('SIGTERM');
    await new Promise((resolve) => child.once('exit', resolve));
  }
  rmSync(dir, { recursive: true, force: true });
});

test('an FCM phone proves its token with a data-message nonce and registers', async () => {
  const { result, messages } = await fcmSent(() => api('/v1/token-challenges', { method: 'POST', body: { platform: 'fcm', device_token: FCM_TOKEN } }));
  assert.equal(result.status, 202);
  assert.equal(messages.length, 1);
  assert.equal(messages[0].token, FCM_TOKEN, 'case kept');
  assert.equal(messages[0].data.type, 'token_challenge');
  assert.equal(messages[0].android.priority, 'high');
  const nonce = messages[0].data.conduit_token_challenge;

  const unproven = await api('/v1/installations', { method: 'POST', body: { platform: 'fcm', device_token: FCM_TOKEN } });
  assert.deepEqual(unproven, { status: 403, json: { error: 'token_proof_required' } });

  // A PushKit token means nothing on Android: it is left out.
  const registered = await api('/v1/installations', { method: 'POST', body: { platform: 'fcm', device_token: FCM_TOKEN, token_proof: nonce, voip_token: 'a'.repeat(64) } });
  assert.equal(registered.status, 201);
  assert.equal(registered.json.installation.platform, 'fcm');
  assert.equal(registered.json.installation.voip, false);
  assert.equal(registered.json.relay_url, base);
  device = { id: registered.json.installation.id, credential: registered.json.credential };

  const meta = await api('/v1/meta', { credential: device.credential });
  assert.ok(meta.json.capabilities.includes('fcm'));
});

test('without an APNs key, APNs registrations and bad platforms are refused', async () => {
  const apns = { bundle_id: 'com.example', environment: 'production', device_token: 'a'.repeat(64) };
  assert.deepEqual(await api('/v1/token-challenges', { method: 'POST', body: apns }), { status: 400, json: { error: 'apns_unavailable' } });
  assert.deepEqual(await api('/v1/installations', { method: 'POST', body: apns }), { status: 400, json: { error: 'apns_unavailable' } });
  assert.deepEqual(await api('/v1/token-challenges', { method: 'POST', body: { platform: 'gcm', device_token: FCM_TOKEN } }), { status: 400, json: { error: 'invalid_platform' } });
  assert.deepEqual(await api('/v1/token-challenges', { method: 'POST', body: { platform: 'fcm', device_token: 'a'.repeat(64) } }), { status: 400, json: { error: 'invalid_device_token' } });
});

test('a Hermes host pairs with the FCM phone', async () => {
  const pairing = await api(`/v1/installations/${device.id}/pairings`, { method: 'POST', credential: device.credential, body: {} });
  assert.equal(pairing.status, 201);
  const claimed = await api('/v1/pairings/claim', { method: 'POST', body: { pairing_code: pairing.json.pairing_code, gateway_name: 'myhost' } });
  assert.equal(claimed.status, 200);
  gatewayCredential = claimed.json.credential;
});

test('notifications, encrypted events and calls arrive as FCM data messages', async () => {
  const ready = await fcmSent(() => sendEvent({ type: 'response.ready', session_id: 's1', profile: 'main' }));
  assert.equal(ready.result.status, 202);
  assert.equal(ready.messages[0].token, FCM_TOKEN);
  assert.equal(ready.messages[0].data.type, 'response.ready');
  assert.equal(ready.messages[0].data.push_type, 'alert');
  assert.equal(JSON.parse(ready.messages[0].data.aps).alert.title, 'Response ready');

  const eventId = `fcm-event-${eventCount + 1}`;
  const e2e = { v: 1, kid: 'a'.repeat(32), msg: eventId, iat: Math.floor(Date.now() / 1000), tok: 'b'.repeat(16), z: 0, req: '', n: 'c'.repeat(16), ct: 'd'.repeat(2000) };
  const sealed = await fcmSent(() => sendEvent({ type: 'approval.needed', e2e }));
  assert.equal(sealed.result.status, 202);
  assert.deepEqual(JSON.parse(sealed.messages[0].data.conduit_e2e), e2e);

  const call = await fcmSent(() => sendEvent({ type: 'call.requested', session_id: 's1', call: { id: 'call-00001', kind: 'done', session_ids: ['s1'] } }));
  assert.deepEqual(call.result.json, { accepted: true, delivered: true, rang: true });
  assert.equal(call.messages.length, 1);
  assert.equal(call.messages[0].data.type, 'call.requested');
  assert.equal(call.messages[0].data.push_type, 'voip');
});

test('location requests and background changes go to the same FCM token', async () => {
  const location = await fcmSent(() => sendEvent({ type: 'location.requested', profile: 'main', locate: { id: `l_${'a'.repeat(16)}` } }));
  assert.deepEqual(location.result.json, { accepted: true, delivered: true });
  assert.equal(location.messages[0].token, FCM_TOKEN);
  assert.equal(location.messages[0].data.type, 'location.requested');

  const background = await fcmSent(() => sendEvent({ type: 'phone.changes', mode: 'background', profile: 'main' }));
  assert.deepEqual(background.result.json, { accepted: true, delivered: true, background: true });
  assert.equal(background.messages[0].data.push_type, 'background');
});

test('live-activity progress reaches the phone\'s FCM token, naming the activity', async () => {
  const activity = randomBytes(32).toString('hex');
  const registered = await api(`/v1/installations/${device.id}/live-activities`, { method: 'PUT', credential: device.credential, body: { push_token: activity, session_ids: ['s1'] } });
  assert.equal(registered.status, 200);
  const progress = await fcmSent(() => api('/v1/live-activity', { method: 'POST', credential: gatewayCredential, body: { session_id: 's1', state: { phase: 'tool', tool: 'web', tools: 2 } } }), { wait: 3_000 });
  assert.deepEqual(progress.result.json, { tracked: true });
  assert.equal(progress.messages.length, 1);
  assert.equal(progress.messages[0].token, FCM_TOKEN);
  assert.equal(progress.messages[0].data.type, 'live_activity');
  assert.equal(progress.messages[0].data.activity, activity);
});
