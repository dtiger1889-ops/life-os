# Life OS

A tiny personal-dashboard framework: **one data layer on your own PC, three ways to touch
it** — an offline-first Android app, browser dashboards with inline edit controls, and your
AI coding assistant. No cloud, no accounts, no subscriptions; Python standard library on
the desktop, Kotlin/Compose on the phone, SQLite files you own.

The design premise: you should not build dashboards by hand in a no-code tool. You describe
a life domain to a coding agent (Claude, or any capable assistant), point it at
`PLAYBOOK.md`, and it writes a purpose-fit dashboard — schema, sync, HTML, and optionally
native phone screens — in an afternoon. The framework supplies the proven plumbing so the
agent only writes the domain.

The reference dashboard, **casework**, tracks a day in the life of a modern consulting
detective — open cases, leads, and contacts for a certain resident of 221B Baker Street.
All of its data is fiction; replace it with your own domains.

## What's in the box

| path | what |
|---|---|
| `server/sync_server.py` | the one desktop server: static dashboards, phone sync API, browser-edit endpoints |
| `server/dashboards.config.json` | engine registry — add a line per dashboard |
| `server/dashboards.json` | the card manifest the app + hub render (no rebuilds to change it) |
| `server/home.html` | browser hub: every dashboard in tabs |
| `example/casework/` | the reference engine + generated dashboard + seeded fictional data |
| `app/` | Android app template: offline-first editing (Room + outbox + WorkManager), manifest-driven cards |
| `PROTOCOL.md` | the sync contract (pull cursors, idempotent push, tombstones, conflict policy) |
| `PLAYBOOK.md` | the add-a-dashboard checklist your coding agent follows |

## Quickstart

> **Windows note**: clone with `git -c core.longpaths=true clone <url>` — the app template's
> screenshot-test goldens have long generated filenames that default Windows git rejects.

0. **Set your server address** (one edit, required): in `server/dashboards.json`, replace
   `YOUR-PC-HOSTNAME` with an address **other devices can reach your PC at** — a LAN
   hostname/IP or a mesh-VPN name, not `localhost`. Every surface that renders dashboard
   cards (the hub page, the phone app) loads dashboards from this URL; leaving the
   placeholder makes fresh installs show every card as "offline" even while direct
   `localhost` URLs work. (Testing in an Android emulator? `10.0.2.2` is the emulator's
   alias for the host machine — but note it only resolves *inside* the emulator, so pick
   the address that matches the surface you're testing.)

1. **Desktop** (Python 3.9+, no pip installs):
   `cd example/casework && python casework.py init --seed` (creates the fictional example
   data), then `cd ../../server && python sync_server.py` — open `http://localhost:8765/`
   for the hub and `http://localhost:8765/casework/dashboard.html` for the example. Edit
   controls on the page write through the engine and regenerate it.
2. **CLI / assistant**: `cd example/casework && python casework.py status` — every write
   surface goes through this same engine. Wire the CLI into your assistant however you
   like (MCP server, shell calls); the engine is the API.
3. **Phone** (Android Studio or a plain Gradle build): build `app/`, install, and on first
   launch enter your server URL. Your PC must be reachable from the phone — same LAN
   works; a private mesh VPN (e.g. Tailscale) is the comfortable way to make it work from
   anywhere. The app is fully usable offline; edits queue and sync when the PC is
   reachable.
4. **Add your own dashboard**: hand `PLAYBOOK.md` to your coding agent and describe the
   domain. Register the result in `dashboards.config.json` + `dashboards.json`; the app
   and hub pick it up with no rebuilds.

**Troubleshooting**: a card shows "offline" while the server is demonstrably up → its
`dashboards.json` URL isn't reachable *from that surface* (see step 0). The app loads each
dashboard's page once per process — after changing `dashboards.json`, force-stop and
relaunch the app rather than just re-opening the card.

## Posture

This is a personal-infrastructure project shared as-is: no hosted anything, no support
promises, no roadmap obligations. Fork it, gut it, make it yours. Everything runs on your
machines and your data never leaves them.

Two deliberate choices to know before you expose it beyond your own network: the sync
server's write endpoints carry no authentication, and the Android app allows cleartext
HTTP to the server. Both assume the server is reachable only over your LAN or a mesh VPN
(Tailscale or similar). Put it on the open internet and you must add auth and TLS first.

## License

FSL-1.1-MIT: the Functional Source License. Use it, modify it, run it for yourself or for
clients; the one thing you may not do is sell a competing product built from it, and that
restriction expires two years after each release, when the MIT license applies. See LICENSE.
