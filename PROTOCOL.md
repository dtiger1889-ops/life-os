# Life OS sync protocol

How the three surfaces (phone app, browser dashboards, your AI assistant) share one set of
SQLite stores without stepping on each other. Everything here is implemented by
`server/sync_server.py` and demonstrated by `example/casework/casework.py`. Plain HTTP +
JSON, Python stdlib only, no external services.

## Model

- **The desktop is authoritative.** Each dashboard owns one SQLite file next to its engine.
  Phones hold a local replica and queue edits; browsers and assistants write directly.
- **Every syncable table carries four columns**: `updated_at` (ISO UTC), `updated_by`
  (`'desktop'` or `'phone'`), `deleted_at` (tombstone — rows are never hard-deleted once
  syncable), `server_seq` (monotonic integer from a global per-database counter; the pull
  cursor). Three support tables: `sync_counter`, `applied_mutations` (idempotency),
  `conflict_log`.
- **Deletes are tombstones.** A deleted row keeps its identity so the deletion propagates;
  every read path filters `deleted_at IS NULL`.
- **All writes go through the engine's own functions** — the server never issues raw SQL at
  tables. Validation, stamping, and the dashboard rebuild happen identically for every
  surface.

## Endpoints

### `GET /health`
`{"ok": true, "db_seq": {"<dashboard>": <latest server_seq>, ...}}` — cheap reachability
probe; the phone calls it before attempting a sync cycle.

### `GET /api/<dashboard>/pull?since=<server_seq>`
Returns every row (including tombstones) with `server_seq > since`, across all syncable
tables, oldest first:
`{"rows": [{"table": "<name>", "record": {<full row>}}, ...], "new_cursor": <int>}`
Clients persist `new_cursor` per dashboard and pass it back next time. Cursor on the
integer sequence, never on timestamps (no clock-skew ambiguity).

### `POST /api/<dashboard>/push`
Body: `{"mutations": [<mutation>, ...]}`. Each mutation:

| field | meaning |
|---|---|
| `mutation_id` | client-generated unique id — the idempotency key; replays are acked as no-ops |
| `table` | target table name |
| `op` | `create`, `update`, `delete`, or a table-specific named action the engine's `SYNC_TABLES` declares |
| `record_id` | the row's id (int-keyed tables) or unique name (name-keyed tables); ignored for `create` on int-keyed tables |
| `payload` | field values for the op (only fields the table's op config lists are passed through) |
| `base_version_updated_at` | the `updated_at` the client last saw for this row; enables conflict detection |

Per-mutation ack: `{"mutation_id", "status": "applied"|"rejected"|"error", "conflict":
bool, "note"?/"reason"?}`.
- **applied** — done (client drops the mutation from its queue).
- **rejected** — permanent (bad target, unknown op, nonexistent row for a non-create op —
  the *exists-guard*, which prevents name-keyed upserts from spawning rows off stale
  client names). Clients must drop, never retry.
- **error** — the engine threw; client may retry later.

**Conflicts**: if `base_version_updated_at` no longer matches the row, the push is STILL
applied (the phone edit wins — its author touched the data last) and the losing desktop
version is written to `conflict_log` for the assistant to adjudicate later: it decides,
corrects through normal engine calls if needed, marks the row resolved, and notifies you.
The engine CLI exposes `sync-status` (unresolved conflicts + newest phone write) and
`resolve-conflict <id>`.

### `POST /api/<dashboard>/desktop/<action>`
The browser dashboards' own controls post here (JSON or form-encoded). These are
synchronous desktop-side writes: `updated_by='desktop'`, no mutation ids, no conflict
bookkeeping (there is nothing to conflict with — this IS the authoritative copy).
Validation failure → HTTP 400 with the engine's message and nothing written. Actions are
declared by each engine's `DESKTOP_ACTIONS` export.

## Client expectations (what the app template implements)

- Local Room (or equivalent) database is the on-device source of truth; the UI never waits
  on the network. Every local edit writes the entity table AND an outbox row in one
  transaction.
- A background worker (connectivity-gated, exponential backoff) drains the outbox (drop on
  `applied`/`rejected`, keep on `error`), then pulls since the stored cursor and applies
  rows — tombstones mark local rows deleted. Triggers: app foreground, after any local
  edit, periodic.
- Offline is a non-event: edits queue; the queue drains whenever the server is next
  reachable.

## Adding a dashboard

Write an engine that follows `example/casework/casework.py` (sync columns from day one,
`SYNC_TABLES` + `DESKTOP_ACTIONS` exports, `sync-status`/`resolve-conflict` subcommands,
dashboard generator), then register it in `server/dashboards.config.json` and add its card
to `server/dashboards.json`. The full checklist is `PLAYBOOK.md`.
