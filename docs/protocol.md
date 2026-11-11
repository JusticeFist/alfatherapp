# Hermes Dashboard Client Protocol (for the Android client)

What AllFather does against a `hermes dashboard` server (`NousResearch/hermes-agent`),
checked against the server code. Scope: password login, REST session list and history, and the
JSON-RPC WebSocket for chat. **UNCONFIRMED** marks anything not verified against the server.

---

## 0. Server facts that shape the client

- `hermes dashboard` defaults to `host=127.0.0.1`, `port=9119` (`hermes_cli/web_server.py::start_server`).
- Any non-loopback bind (LAN, Tailscale IP, `0.0.0.0`), or a non-loopback `dashboard.public_url`,
  turns on the **gated** auth mode (`app.state.auth_required = True`). `--insecure` no longer
  turns it off. A phone on the network therefore always talks to gated mode: login, cookies,
  WS ticket. Everything below assumes gated mode.
- Username/password comes from the bundled `basic` provider. Server config:
  `HERMES_DASHBOARD_BASIC_AUTH_USERNAME`, `..._PASSWORD` or `..._PASSWORD_HASH`,
  `..._SECRET` (signing key; without it, sessions die when the server restarts), `..._TTL_SECONDS`
  (access-token TTL, default 12 h). The refresh token lasts 30 days.
- Loopback mode (bound to 127.0.0.1, reached through an SSH tunnel or `adb reverse`) uses a different
  scheme: header `X-Hermes-Session-Token` on REST and `?token=` on the WS, with a per-process
  token injected into the SPA HTML. **UNCONFIRMED** how a native client would get that token. Not covered here.
- Host/Origin guard on the WS: if the request has an `Origin` header, its host must match the
  bound host (any host is accepted when bound to `0.0.0.0`). OkHttp sends no `Origin` by default.
  Keep it that way.

---

## 1. Login (username + password)

### 1.1 Discover providers (optional, public)
`GET /api/auth/providers`
```json
{"providers":[{"name":"basic","display_name":"Username & Password","supports_password":true}]}
```
- `503 {"detail":"no auth providers registered"}`: gated mode with no provider configured.
- A 3xx here means an auth proxy (Cloudflare Access, oauth2-proxy) sits in front. Not supported.
- AllFather picks password login when any provider has `supports_password: true`.

### 1.2 Password login
`POST /auth/password-login`, `Content-Type: application/json`
```json
{"provider":"basic","username":"admin","password":"secret"}
```
Optional `"next":"/"`. On success: `200 {"ok":true,"next":"/"}` plus `Set-Cookie` headers.
Errors (JSON `{"detail": "..."}`): `401` bad credentials, `404` unknown provider, `429` rate limit
(10 attempts per 60 s per IP), `503` provider unreachable.

Session cookies (all `HttpOnly`, `SameSite=Lax`, `Path=/`, or the proxy prefix when there is one):

| Bare name | Meaning | Max-Age |
|---|---|---|
| `hermes_session_at` | access token (HMAC-signed blob) | token TTL (default 43200 s) |
| `hermes_session_rt` | refresh token | 30 days |
| `hermes_session_provider` | non-secret hint, e.g. `basic` | 30 days |

The name gets a prefix depending on transport: plain HTTP uses the bare name. HTTPS at the root uses `__Host-<name>`
(with `Secure`). HTTPS behind an `X-Forwarded-Prefix` uses `__Secure-<name>`. The server reads all three
spellings, so **store the cookies exactly as received** (OkHttp `CookieJar`, persisted) and send them back.

### 1.3 Session persistence and refresh
- REST auth = **cookies only**. AllFather sends no bearer and no custom header on REST (it runs
  `fetch(..., {credentials:'include'})` in a WebView, or uses native cookies when the page cannot run).
- Refresh happens on the server: when the AT is missing or expired and the RT is still valid, **any** gated request
  is served normally and the response sets rotated `hermes_session_at`/`hermes_session_rt`
  cookies. The client must save the rotated cookies, and must save the new RT in particular. Single-flight:
  the server coalesces parallel requests that carry the same stale RT.
- Expired or invalid session on `/api/*` gives `401`:
  ```json
  {"error":"session_expired","detail":"Unauthorized","reason":"invalid_or_expired_session","login_url":"/login"}
  ```
  (`"error":"unauthenticated"` when no cookie was sent at all). The client then logs in again with the saved
  username and password, as AllFather's "silent renewal" does, and retries.
- Logout: `POST /auth/logout` (clears the cookies; answers with a 302 to `/login`).
- Alternative the server supports but AllFather does not use for password login: gated REST also accepts
  `Authorization: Bearer <access token>` (the same value as `hermes_session_at`), and the WS accepts
  `?token=<access token>`. `POST /auth/native/refresh {"refresh_token":"...","provider":"basic"}`
  returns `{"access_token","refresh_token","token_type":"Bearer","expires_at","provider","user_id"}`.

### 1.4 WebSocket ticket
`POST /api/auth/ws-ticket` (no body, session cookies attached)
```json
{"ticket":"q3V...base64url...","ttl_seconds":30}
```
The ticket is **single-use with a 30 s TTL**. Mint a fresh one for **every** WS connect and reconnect.
A spent or expired ticket makes the WS upgrade fail (AllFather sees 403 / close 4401).
`401` here means the session is dead: log in again (§1.3).

---

## 2. WebSocket connection

- URL: `ws(s)://<host>[:port][/prefix]/api/ws?ticket=<urlencoded ticket>`
  (`http` becomes `ws`, `https` becomes `wss`, same host and path base as REST).
- Headers AllFather sends: the Cloudflare/proxy headers when configured, `X-Conduit-Client: conduit`
  (informational, not checked by the server), and the session cookies for that address
  (only needed when an auth proxy sits in front; Hermes itself checks only the ticket).
  No `Origin`. **Never follow redirects** on the upgrade (AllFather refuses them).
- Alternative ticket transport (server only, AllFather does not use it): `Sec-WebSocket-Protocol:
  hermes-gateway-v1, hermes-gateway-ticket.<ticket>`.
- Rejection close codes: `4401` bad or missing credential, `4403` host/origin mismatch or chat disabled.
- Framing: one JSON-RPC 2.0 object per **text** frame (newline-delimited when batched; split on `\n`
  and ignore blank lines). AllFather also accepts binary frames as UTF-8.

### 2.1 Handshake
1. The server's first frame after accept:
   ```json
   {"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","session_id":"",
    "payload":{"skin":{},"change_events":true,"heartbeat":true,"replay_epoch":"..."}}}
   ```
2. On `gateway.ready`, the client **must** advertise once per socket (AllFather does this):
   ```json
   {"jsonrpc":"2.0","id":1,"method":"client.capabilities","params":{"server_requests":true}}
   ```
   Result: `{"declines_not_shown": true|false}` (other fields possible).
   Without this call the server never sends approval or clarify requests to this client. It
   withdraws them instead ("the attached client cannot answer approval requests").

### 2.2 Keepalive
- AllFather: when no inbound frame has arrived for 30 s (45 s in background), it sends the RPC `ping` (5 s timeout in
  foreground, 10 s for keepalive; a "method not found" reply still counts as alive). Two unanswered
  pings in a row close the socket.
- Server: `gateway.ping` is answered **on the read loop**, even while a handler is blocked:
  `{"jsonrpc":"2.0","id":N,"method":"gateway.ping"}` gets `{"jsonrpc":"2.0","result":{"ok":true},"id":N}`.
  The server comment says clients ping every 15 s against a 45 s deadline.
  **Recommendation: use `gateway.ping` every 15-30 s.** Whether the server still registers a plain `ping` method: **UNCONFIRMED**.
- The server closes a peer that cannot take a send within 30 s.

### 2.3 Reconnect (what AllFather does)
- On socket error or close, all pending RPCs fail. Schedule a reconnect with exponential backoff:
  `delay = max(0.5, min(cap, 1 s * 2^attempt) * jitter)`, where cap = 60 s (8 s while the UI is visible;
  visible uses jitter in [0.5, 1]). Reset `attempt` to 0 after a successful connect.
  Reconnect immediately (0.1 s) if a turn was running.
- Each attempt: mint a new ticket (§1.4), log in again if that returns 401, open the WS, wait for `gateway.ready`,
  send `client.capabilities`, then `session.resume` the open chat (§4.2). That reattaches the stream
  and returns `running` and `open_requests`. Runtime session ids belong to the server process,
  so always re-resume after a reconnect and do not reuse the old runtime id blindly.

---

## 3. RPC layer

Client request (integer ids, incrementing per connection):
```json
{"jsonrpc":"2.0","id":7,"method":"session.create","params":{"cols":96,"source":"desktop"}}
```
Response: `{"jsonrpc":"2.0","id":7,"result":{...}}` or
`{"jsonrpc":"2.0","id":7,"error":{"code":4009,"message":"session busy","data":{"reason":"..."}}}`.
- Omit `params` (or send `null`) when empty. AllFather adds `"profile":"<name>"` to params when the
  profile is not `default`.
- AllFather's timeouts: 30 s by default, 180 s for `prompt.submit`, 60 s for a resume that returns the full transcript.
- Error codes seen: `-32601` method not found, `-32602` invalid params, `-32700` parse error, `4000`
  invalid params (`"...: <key>: Extra inputs are not permitted"`, so drop the key and retry), `4001` session
  not found, `4009` session busy, `data.reason:"SESSION_NOT_OWNED"` (another surface owns the chat).

Frame dispatch rule (AllFather `handleMessage`):
1. `method == "event"`: a server push event (§5).
2. `method` set and `id` is a **string** (`srq-<12hex>`): a server-to-client request (§6). Integer ids
   with a method are ignored.
3. No `method` and integer `id`: the response to a pending client request.

Server push envelope (`tui_gateway/server.py::_event_frame`):
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"<runtime sid>","payload":{"text":"Hel"}}}
```
`payload` may be absent. `session_id` is the **runtime** id (empty for global events).
The server coalesces `message.delta`/`reasoning.delta`/`thinking.delta` into batches of about 33 ms. One WS frame
can therefore carry several newline-separated JSON objects (**UNCONFIRMED**: the exact batching format was not read in `tui_gateway/transport.py`).

---

## 4. Sessions

**Two ids per chat.** The *stored* id (DB row, e.g. `20261011_142233_ab12cd`) is used by REST and by the session
list. The *runtime* id is returned by `session.create`/`session.resume` and used by `prompt.submit`,
`session.interrupt`, `approval.respond`, and on events. Keep a map stored id to runtime id. The
`session.title` event carries the stored id in `payload.session_id`.

### 4.1 List sessions (REST, what AllFather uses)
`GET /api/profiles/sessions?limit=200&offset=0&min_messages=1&archived=exclude&order=recent&exclude_sources=cron&profile=default`
(`profile` defaults to `all`, which merges every profile; `limit` can be at most 500; `archived` is `exclude|only|include`;
`order` is `recent|created`.)
```json
{"sessions":[{"id":"20261011_142233_ab12cd","title":"Fix the build","preview":"why does gradle...",
  "started_at":1760190153.2,"last_active":1760193000.5,"message_count":14,"source":"desktop",
  "model":"claude-...","profile":"default","archived":false,"pinned":false}],
 "total":42,"profile_totals":{"default":42},"limit":200,"offset":0,"errors":[],"storage":[]}
```
AllFather's mapping: id = `session_id` ?? `id`; title = `title` ?? `preview` ?? "Untitled";
preview is shown only when it differs from the title; updated time is the first of `last_active`, `updated_at`,
..., `created_at` (epoch seconds; values > 4e10 are divided by 1000). Page by `offset += 200`
until a batch comes back smaller than 200. The row fields beyond `id`, `title`, `preview`, `started_at`, `message_count`, `source`,
`profile`, `last_active` are **UNCONFIRMED** (they come from `list_sessions_rich`).
Global event `sessions.changed` (payload `{}`) means: re-fetch the list.
The WS RPC `session.list` exists, but AllFather avoids it for the UI (it is not profile-scoped).

### 4.2 Open a chat and load history
1. Attach: RPC `session.resume`
   ```json
   {"session_id":"<stored id>","cols":96,"source":"desktop","omit_messages":true}
   ```
   Result (shape from `_resume_payload`):
   ```json
   {"session_id":"<runtime id>","resumed":"<stored>","session_key":"<stored>","message_count":14,
    "messages":[],"messages_omitted":true,"running":false,"status":"...","inflight":null,
    "info":{"model":"...","provider":"...","running":false,"title":"...","stored_session_id":"...","cwd":"..."},
    "open_requests":[{"id":"srq-ab12...","method":"approval","params":{...}}]}
   ```
   Re-deliver each `open_requests` entry as if it had just arrived (§6). Use `running` as the busy state.
   For the old one-shot path, send `"inline_images":false` without `omit_messages`. `messages` is then the transcript.
2. History (REST): `GET /api/sessions/{storedId}/messages?limit=120&offset=0&order=latest&include_compacted=true&inline_images=false`
   (add `&profile=<p>` for non-default profiles)
   ```json
   {"session_id":"<resolved stored id>","profile":"default",
    "messages":[{"id":101,"role":"user","content":"hi","timestamp":1760190153.2},
                {"id":102,"role":"assistant","content":"Hello!","reasoning":"...","tool_calls":[{"id":"call_1","function":{"name":"terminal","arguments":"{\"cmd\":\"ls\"}"}}]},
                {"id":103,"role":"tool","tool_call_id":"call_1","content":"a.txt\nb.txt"}],
    "pagination":{"limit":120,"offset":0,"order":"latest","returned":3}}
   ```
   Load older pages with `offset += 120`. There are more pages while `returned >= limit`. The server caps `limit` at 500. A `404`
   means not found (on very old servers: route missing). AllFather reads `role` (`user|assistant|system|tool|reasoning`),
   `content`/`text` (a string, or a list of parts with `text`), `reasoning`, `tool_calls[]`, `tool_call_id`,
   `tool_name`/`name`, `timestamp`/`created_at`, `display_kind` (it skips `"hidden"`). The exact column set is **UNCONFIRMED**.

### 4.3 New session
RPC `session.create` with `{"cols":96,"source":"desktop"}` and optional `model`, `provider`,
`reasoning_effort`, `fast`, `cwd`, `title`, `profile`.
Result: `{"session_id":"<runtime>","stored_session_id":"<stored>","message_count":0,"messages":[],"info":{...}}`.
The DB row (and so the REST list entry) appears only after the first prompt.

Rename: `session.title {"session_id":"<runtime>","title":"New name"}`. Without `title` it reads the title back as `{"title": "..."}`.

---

## 5. Sending a message and streaming events

RPC `prompt.submit`:
```json
{"jsonrpc":"2.0","id":12,"method":"prompt.submit","params":{"session_id":"<runtime>","text":"List files"}}
```
Result: `{"status":"streaming"|"queued"|"steered"|"redirected","user_row_id":104}`. The reply arrives
as events. Optional `"queued":true` waits for a busy turn to finish (`4000` on older gateways).
While busy, `session.steer {"session_id","text"}` (result `status:"rejected"` when refused) or
`session.redirect` inject text into the running turn.

Events (`params.type`) as `StreamEventParser` handles them. Read fields from `payload` unless stated otherwise:

| type | payload fields used | meaning |
|---|---|---|
| `message.start` | (optional `display_kind`,`text`) | turn started streaming |
| `message.delta` | `text` (null/empty = segment boundary, ignore) | reply text chunk; append |
| `reasoning.delta`, `message.reasoning`, `message.reasoning.delta` | `reasoning` ?? `reasoning_content` ?? `text` ?? `content` | thinking chunk. The server also emits `thinking.delta` and `reasoning.available` (`text`), which AllFather leaves unparsed |
| `message.interim` | `text`, `already_streamed` | commentary next to tool calls, sealed as its own segment |
| `tool.start` (alias `tool_call`) | `tool_id`, `name`, `args_text` ?? `context` ?? `args` | tool started |
| `tool.complete` (alias `tool_result`) | `tool_id`, `name`, `result`, `result_text`, `summary`, `duration_s` (AllFather: `output` ?? `result`) | tool finished |
| `tool.generating` | `name` | model is writing tool args (AllFather leaves it unparsed) |
| `message.complete` | `text` (also `content`/`rendered`), `reasoning`, `status` (`complete`/`error`/`interrupted`), `error`, `usage`, `response_previewed`/`response_reused` (text already shown, do not duplicate) | **turn done** |
| `error` | `message` | session-level failure outside a turn |
| `status.update` | `kind` (`compacting`,`compacted`,...), `text` | transient status line |
| `session.info` | the full info object (`running`, `model`, `provider`, `title`,...) | live session state |
| `session.usage` | `usage.context_used/context_max/context_percent` | usage tick, about 1/s |
| `session.title` | `session_id` (stored), `title` | auto-title |
| `model.update` | `model`, `provider` | model changed |
| `approval.request` | approval fields (§6) | legacy approval notification |
| `request.cancel` | `id`, `method`, `reason` (`timeout`/`interrupted`/`shutdown`/`resolved`/`session_closed`) | server withdrew an `srq-` request; close that card |
| `approval.cancelled` | `request_ids`, `reason` | pending approvals dropped (resolved as deny) |
| `sessions.changed` | `{}` | re-fetch session list |
| `subagent.*` | `subagent_id`, `goal`, `status`, `text` | delegate-agent progress |

AllFather also maps `message.interrupted`/`session.interrupted`, `session.busy` (`busy`), and
`context.update`/`session.context`. Those names do not appear in the current server event contract
(`tui_gateway/contracts/events.py`). **UNCONFIRMED** whether any server still emits them. Treat
`message.complete` with `status:"interrupted"` as the interrupt signal.

Minimal turn: `message.start`, `reasoning.delta`\*, `message.delta`\*, (`tool.start` then `tool.complete`)\*,
`message.delta`\*, `message.complete`.

---

## 6. Approvals (and other server-to-client requests)

The server asks the client a question with a JSON-RPC **request** that has a string id:
```json
{"jsonrpc":"2.0","id":"srq-1a2b3c4d5e6f","method":"approval","params":{
  "session_id":"<runtime>","request_id":"<queue id>","command":"rm -rf build/",
  "description":"dangerous command","choices":["once","session","always","deny"],
  "allow_permanent":true,"smart_denied":false,"tool_name":"terminal"}}
```
Choices: `once`, `session`, `always`, `deny`. When `choices` is missing, the default is `once`, `session` (unless
`smart_denied` or `allow_session:false`), `always` (unless `allow_permanent:false`), `deny`.

Answer in **either** of two ways:
- (AllFather) RPC `approval.respond`:
  `{"session_id":"<runtime>","choice":"once","request_id":"<params.request_id>"}`. Optional `"all":true`.
  The result `{"resolved":1}` must be >0, otherwise the request was already gone.
- (Plain JSON-RPC) reply to the srq frame: `{"jsonrpc":"2.0","id":"srq-1a2b3c4d5e6f","result":{"choice":"once"}}`.

`approval.pending {"session_id"}` gives `{"approvals":[...]}` (the same objects). Use it after a reconnect.
The server withdraws a request with `request.cancel {id:"srq-...",method:"approval",reason}`.

Other server-to-client requests:
- `clarify`: answer with result `{"answer":"..."}`. For multi-question requests use RPC `clarify.lock {request_id,question_id,answer}`.
- `sudo`, `secret`, `vault.*`: answer with result `{"value":"..."}` (`""` = declined).
- Anything you do not handle: **reply right away** with
  `{"jsonrpc":"2.0","id":"srq-...","error":{"code":-32601,"message":"no handler for server request: <method>"}}`,
  otherwise the agent waits out the deadline (300 s for clarify). Window-owned methods (`preview.read`,
  `preview.act`, `terminal.read`, `window.read`, `tour`) are declined with code `4404` only when
  `client.capabilities` returned `declines_not_shown:true`. Otherwise do not reply to them.

---

## 7. Stop a running turn
RPC `session.interrupt {"session_id":"<runtime>"}` returns `{"status":"interrupted"}`. The turn then ends with
`message.complete` with `status:"interrupted"`. Pending approvals are dropped (`approval.cancelled`).

---

## 8. Model and provider (simple path)
- List: RPC `model.options {"explicit_only":true,"session_id":"<runtime, optional>"}` returns
  `{"model":"<current>","provider":"<current>","providers":[{"slug":"anthropic","name":"Anthropic",
  "is_current":true,"aliases":[],"models":["id", {"id":"...","label":"...","reasoning":true}]}]}`
  (a model entry is a string or an object). REST alternative: `GET /api/model/options?explicit_only=true`.
  The provider and model field names here come from AllFather's parser. The server contract is **UNCONFIRMED**.
- Switch (this session only): RPC `config.set`
  `{"key":"model","session_id":"<runtime>","value":"<model> --provider <slug> --session"}`.
  Result: `{"value":"<resolved model>","confirm_required":false,"confirm_message":"","deferred":false}`.
  If `confirm_required` is true, show `confirm_message` and resend with `"confirm_expensive_model":true`.
- Related: `config.set` with key `reasoning` (value: effort), key `fast` (`fast`/`normal`), key `yolo` (`1`/`0`).

---

## 9. Android implementation checklist
1. An OkHttp client with a persistent `CookieJar` (store cookies exactly as received; rotate on every response) and `followRedirects(false)` for the WS.
2. Log in, then `POST /api/auth/ws-ticket`, then `ws(s)://.../api/ws?ticket=` (no Origin header).
3. On `gateway.ready`, send `client.capabilities {server_requests:true}`. Start `gateway.ping` every 15-30 s.
4. Session list and history over REST with cookies. Use the WS for `session.create`/`resume`/`prompt.submit`/`interrupt`.
5. Answer or decline every `srq-*` request. Map `request.cancel` to card removal.
6. On reconnect: new ticket (log in again on 401), backoff with jitter, `session.resume` the open chat, re-deliver `open_requests`.

---

## Sources

**NousResearch/hermes-agent**
- `hermes_cli/web_server.py` (port 9119, auth_required decision, legacy session token)
- `hermes_cli/web_server_boundary.py` (gate rule, Host/Origin guard, ticket subprotocol)
- `hermes_cli/web_server_chat.py` (`_ws_auth_reason`: ticket, token, internal)
- `hermes_cli/web_routers/chat_ws.py` (`/api/ws` route, close codes)
- `hermes_cli/dashboard_auth/routes.py`, `cookies.py`, `middleware.py`, `ws_tickets.py`, `public_paths.py`, `token_auth.py`
- `plugins/dashboard_auth/basic/__init__.py` (basic provider, env vars, TTLs)
- `hermes_cli/web_routers/profiles.py` (`GET /api/profiles/sessions`), `hermes_cli/web_routers/sessions.py` (`/api/sessions/{id}/messages`)
- `tui_gateway/ws.py` (gateway.ready, gateway.ping, coalescing), `tui_gateway/server.py` (`_event_frame`, approval payload)
- `tui_gateway/server_requests.py`, `tui_gateway/contracts/server_requests.py`, `contracts/events.py`, `contracts/sessions.py`, `contracts/prompt_voice.py`, `contracts/common.py`
- `tui_gateway/methods_prompt.py` (prompt.submit, approval.respond/pending), `methods_session.py` (create/list/resume), `methods_session_interrupt.py`
