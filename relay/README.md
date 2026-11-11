# AllFather push relay

The push relay the app and the host plugin (`conduit_push`) talk to: it holds each phone's push token, pairs it
with your Hermes gateways, and delivers their (end-to-end encrypted) notifications through **Firebase Cloud
Messaging**. It is the upstream Hermes push relay with FCM added (`src/fcm.mjs`); APNs still works for iPhones.

Run it on the same host as your Hermes dashboard. Node 22+, no dependencies.

## With Docker
```bash
cd relay/deploy
mkdir -p data secrets && sudo chown 1000:1000 data
cp ~/Downloads/<firebase-key>.json secrets/service-account.json && chmod 600 secrets/service-account.json
PUBLIC_URL=http://<this host's LAN IP>:8787 docker compose up -d --build
```
`PUBLIC_URL` is where phones reach the relay. Plain `http://` is only accepted for LAN or Tailscale addresses;
a public address must be `https://` (behind a tunnel or reverse proxy, also set `TRUST_PROXY=1`).

## Without Docker (systemd user unit)
Copy `deploy/relay.env.example` to `~/.config/allfather-push-relay/relay.env`, fill it in, `chmod 600` it, and
follow the steps at the top of `deploy/allfather-push-relay.service`.

## Then
In the app: Settings > Notifications. The relay URL defaults to `http://<dashboard host>:8787`; change it if
the relay lives elsewhere. The app registers the phone, proves it holds its token, and pairs every profile of
the dashboard through the host plugin.

## Tests
```bash
npm test
```

License: MIT (see [LICENSE](LICENSE)).
