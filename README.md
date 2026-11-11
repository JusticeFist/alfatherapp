# AllFather

A native Android client (Kotlin + Jetpack Compose) for [Hermes Agent](https://github.com/NousResearch/hermes-agent),
with a Wear OS companion. It signs in to your own Hermes dashboard and puts your agent on your phone: chat,
voice, notifications, tasks, bots, and an assistant on the power button.

[Español abajo](#español)

## Features
- **Chat** with streaming replies, Markdown, code, LaTeX, Mermaid, images and files; model and reasoning picker;
  queue, steer or interrupt a running turn; reactions, read aloud, quote, fork.
- **Chats list** with pins, archive, sources, search; **Projects** and **Artifacts**.
- **Tasks**: the Kanban board and scheduled (cron) jobs with each run's result.
- **Bots**: profiles and group rooms; **helper profiles** your agent can consult ("Ask your helpers").
- **Settings**: connection(s), profile config, skills, tools, connectors, plugins, host files.
- **Notifications** (FCM, end-to-end encrypted): replies, approvals from the notification, calls, live progress.
- **Voice**: dictation, read aloud, Gemini Live calls, wake phrase.
- **System assistant** (hold the power button) and **phone control** through an accessibility service (asks
  before sending, paying or deleting; never touches passwords, banking or password-manager apps).
- **Sharing with your agent** (opt-in, end-to-end encrypted): calendar, phone state, location, Health Connect.
- **Pulse**: your agent's proactive cards.
- **Wear OS** app: ask by voice, hear the answer; tile and complication.
- Share target, launcher shortcuts, home-screen widget and a Quick Settings tile.
- English and Spanish UI.

## What you need
- A Hermes host running the web dashboard (`hermes dashboard`, port 9119 by default) that your phone can reach
  (LAN, Tailscale, or a tunnel; Cloudflare Access service tokens are supported).
- The host plugin `conduit_push` (notifications, calls, Pulse, sharing, artifacts…):
  ```bash
  hermes plugins install kaishi00/hermes-conduit-notifier --enable
  ```
  then restart the dashboard. The app can update it later from Settings.
- Android 10 (API 29) or newer. Wear OS watch optional.

## Build
JDK 17 or newer and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).
```bash
./gradlew testDebugUnitTest :app:assembleRelease :wear:assembleRelease
```
APKs land in `app/build/outputs/apk/release/` and `wear/build/outputs/apk/release/`. Release builds are signed
with your debug key: install the phone and watch APKs from the same machine (they must share the signing key).
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```
Then open the app, enter your dashboard address, username and password (or tap "Ask Hermes" for a prompt that
makes Hermes set it up and send you a sign-in link).

## Push notifications (optional)
Without this the app works, just without notifications.
1. Create a Firebase project, add an Android app with package `app.allfather`, download `google-services.json`
   into `app/`, and rebuild.
2. In Firebase: Project settings > Service accounts > Generate new private key.
3. Run the relay on your Hermes host (Docker, Node 22): see [`relay/`](relay/README.md).
4. In the app: Settings > Notifications (the relay URL defaults to `http://<dashboard host>:8787`).

## Host skills (recommended)
Copy the folders in [`host/skills/`](host/skills) into `~/.hermes/skills/` on your Hermes host:
- `allfather-app`: tells your agent how the app shows its replies and what the phone shares.
- `android-phone-control`: how to drive the phone with the app's `actions` block.
- `helper-team`: how to set up and consult helper profiles.

## Docs
- [`docs/protocol.md`](docs/protocol.md): the dashboard protocol the app speaks (REST + JSON-RPC WebSocket).
- [`docs/design-allfather.md`](docs/design-allfather.md): the design system.
- [`CONTRIBUTING.md`](CONTRIBUTING.md): build notes and library gotchas.

## Credits
- Fonts: [Geist](https://vercel.com/font) (SIL OFL). Icons: [Lucide](https://lucide.dev) (ISC).
- Bundled: [KaTeX](https://katex.org) and [Mermaid](https://mermaid.js.org) (MIT).
- Phone-control ideas from [buddy-android](https://github.com/ghorbelhamdi/buddy-android) (Apache-2.0).
- Licenses ship inside the app under `assets/licenses/` and `assets/web/`.

## License
[MIT](LICENSE).

---

## Español

AllFather es un cliente nativo de Android (y Wear OS) para [Hermes Agent](https://github.com/NousResearch/hermes-agent):
se conecta a tu propio dashboard de Hermes y trae a tu agente al teléfono, con chat, voz, notificaciones, tareas,
bots y un asistente en el botón de encendido. La interfaz está en inglés y español (sigue el idioma del teléfono).

**Necesitas** un host de Hermes con el dashboard (`hermes dashboard`, puerto 9119) accesible desde el teléfono, y
el plugin del host:
```bash
hermes plugins install kaishi00/hermes-conduit-notifier --enable
```

**Compilar** (JDK 17+ y Android SDK):
```bash
./gradlew testDebugUnitTest :app:assembleRelease :wear:assembleRelease
```

**Notificaciones (opcional):** crea un proyecto de Firebase con el paquete `app.allfather`, pon
`google-services.json` en `app/`, recompila y levanta el relay de [`relay/`](relay/README.md) en tu host.

**Skills del host:** copia las carpetas de [`host/skills/`](host/skills) a `~/.hermes/skills/`.
