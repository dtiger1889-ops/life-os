#!/usr/bin/env python3
"""
Casework: a day in the life of a modern consulting detective.

Reference "engine" implementation for the Life OS framework starter kit --
a tiny local store (cases / leads / contacts) plus a generated HTML
dashboard, following the same pattern any dashboard in this framework
uses: a stdlib-only SQLite store, a CLI, sync columns on every table from
day one, and two exports (SYNC_TABLES, DESKTOP_ACTIONS) that let the
generic server/sync_server.py drive it without ever touching raw SQL.

No external dependencies. Python 3.9+ and the standard library only.

Common commands:
    python3 casework.py init --seed
    python3 casework.py add-case "The Vanishing Courier of Camden Lock" --client "Scotland Yard" --priority high
    python3 casework.py solve-case 1
    python3 casework.py add-lead "Pull the CCTV footage" --case 1 --urgency high
    python3 casework.py done-lead 3
    python3 casework.py delete-lead 4
    python3 casework.py add-contact "Dr. Watson" --role "flatmate & chronicler"
    python3 casework.py log-contact "Dr. Watson" --note "rang about the courier case"
    python3 casework.py status            # JSON snapshot
    python3 casework.py status --text     # human-readable snapshot
    python3 casework.py dashboard         # rebuild dashboard.html
"""

import argparse
import json
import os
import sqlite3
import sys
from datetime import datetime, date, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(HERE, "casework.db")
HTML_PATH = os.path.join(HERE, "dashboard.html")

STATUSES = ["investigating", "stakeout", "awaiting_lab", "solved", "cold"]
PRIORITIES = ["high", "medium", "low"]
URGENCIES = ["high", "medium", "low"]

_PRIORITY_WEIGHT = {"high": 3, "medium": 2, "low": 1}
_URGENCY_WEIGHT = {"high": 3, "medium": 2, "low": 1}
_STATUS_WEIGHT = {"stakeout": 4, "investigating": 3, "awaiting_lab": 2, "cold": 1, "solved": 0}
_KIND_BY_STATUS = {"stakeout": "red", "investigating": "amber", "awaiting_lab": "ghost",
                    "cold": "ghost", "solved": "green"}
_KIND_BY_PRIORITY = {"high": "red", "medium": "amber", "low": "ghost"}
_KIND_BY_URGENCY = {"high": "red", "medium": "amber", "low": "ghost"}


class ValidationError(Exception):
    """Raised by DESKTOP_ACTIONS handlers on bad browser-edit input. The
    generic sync_server.py looks this class up by name on the module and
    turns it into an HTTP 400."""
    pass


# --------------------------------------------------------------------------
# Database
# --------------------------------------------------------------------------
def connect():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON;")
    return conn


def init_db():
    conn = connect()
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS cases (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            title       TEXT NOT NULL,
            client      TEXT,
            status      TEXT NOT NULL DEFAULT 'investigating',
            priority    TEXT NOT NULL DEFAULT 'medium',
            opened      TEXT,                 -- ISO date
            notes       TEXT,
            created_at  TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS leads (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            text        TEXT NOT NULL,
            case_id     INTEGER,              -- nullable FK to cases.id; the quick-capture table
            urgency     TEXT NOT NULL DEFAULT 'medium',
            done        INTEGER NOT NULL DEFAULT 0,
            created_at  TEXT NOT NULL,
            done_at     TEXT
        );

        CREATE TABLE IF NOT EXISTS contacts (
            name          TEXT PRIMARY KEY,
            role          TEXT,
            last_contact  TEXT,                -- ISO date
            notes         TEXT,
            created_at    TEXT NOT NULL
        );

        -- Sync layer: one global monotonic counter drives server_seq on
        -- every phone-editable table so a client's pull cursor is immune
        -- to clock skew.
        CREATE TABLE IF NOT EXISTS sync_counter (
            id     INTEGER PRIMARY KEY CHECK (id = 1),
            value  INTEGER NOT NULL DEFAULT 0
        );

        -- Idempotency ledger for pushes: a mutation_id seen here is a
        -- no-op replay, never re-applied.
        CREATE TABLE IF NOT EXISTS applied_mutations (
            mutation_id  TEXT PRIMARY KEY,
            applied_at   TEXT NOT NULL
        );

        -- Queue of push-time last-write-wins conflicts for later review.
        CREATE TABLE IF NOT EXISTS conflict_log (
            id             INTEGER PRIMARY KEY AUTOINCREMENT,
            table_name     TEXT NOT NULL,
            record_id      TEXT,
            phone_payload  TEXT,
            desktop_row    TEXT,
            resolution     TEXT,
            resolved       INTEGER NOT NULL DEFAULT 0,
            created_at     TEXT NOT NULL
        );
        """
    )
    conn.execute("INSERT OR IGNORE INTO sync_counter (id, value) VALUES (1, 0)")
    conn.commit()
    conn.close()


def _next_seq(conn):
    """Bump and return the DB-global monotonic sync sequence."""
    conn.execute("UPDATE sync_counter SET value = value + 1 WHERE id = 1")
    return conn.execute("SELECT value FROM sync_counter WHERE id = 1").fetchone()[0]


# Sync columns added to every phone-editable table via the same idempotent
# ALTER-guard pattern used for any later schema growth. updated_by defaults
# to 'desktop' for pre-existing rows via its column DEFAULT, but
# updated_at/server_seq have no DEFAULT and need the _backfill_sync_seq()
# pass below (NULL never satisfies the pull endpoint's `server_seq > since`
# filter, so an un-backfilled row would never reach a client).
_SYNC_TABLES = ["cases", "leads", "contacts"]
_MIGRATIONS = []
for _t in _SYNC_TABLES:
    _MIGRATIONS.append((_t, "updated_at", "TEXT"))
    _MIGRATIONS.append((_t, "updated_by", "TEXT DEFAULT 'desktop'"))
    _MIGRATIONS.append((_t, "deleted_at", "TEXT"))
    _MIGRATIONS.append((_t, "server_seq", "INTEGER"))
del _t


def _migrate():
    conn = connect()
    for table, col, ddl in _MIGRATIONS:
        try:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN {col} {ddl}")
        except sqlite3.OperationalError:
            pass  # column already exists
    conn.commit()
    conn.close()


def _backfill_sync_seq():
    """One-time backfill for rows written before server_seq/updated_at were
    populated inline. Idempotent: only touches server_seq IS NULL rows."""
    conn = connect()
    for table in _SYNC_TABLES:
        rows = conn.execute(
            f"SELECT rowid FROM {table} WHERE server_seq IS NULL ORDER BY rowid"
        ).fetchall()
        for row in rows:
            seq = _next_seq(conn)
            conn.execute(
                f"UPDATE {table} SET server_seq = ?, "
                f"updated_at = COALESCE(updated_at, created_at) WHERE rowid = ?",
                (seq, row["rowid"]),
            )
    conn.commit()
    conn.close()


def ensure_db():
    # CREATE TABLE IF NOT EXISTS is idempotent, so running init on every call
    # is cheap and means a script ahead of its DB (new table) self-heals.
    init_db()
    _migrate()
    _backfill_sync_seq()


def now_iso():
    return datetime.now().isoformat(timespec="seconds")


def today_iso():
    return date.today().isoformat()


def parse_date(s):
    if not s:
        return None
    try:
        return date.fromisoformat(s[:10])
    except ValueError:
        return None


def days_since(iso):
    d = parse_date(iso)
    if d is None:
        return None
    return (date.today() - d).days


# --------------------------------------------------------------------------
# Cases
# --------------------------------------------------------------------------
def add_case(title, client=None, status="investigating", priority="medium",
             opened=None, notes=None, updated_by="desktop"):
    ensure_db()
    if status not in STATUSES:
        status = "investigating"
    if priority not in PRIORITIES:
        priority = "medium"
    conn = connect()
    seq = _next_seq(conn)
    now = now_iso()
    cur = conn.execute(
        "INSERT INTO cases (title, client, status, priority, opened, notes, created_at, "
        "updated_at, updated_by, server_seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (title, client, status, priority, opened or today_iso(), notes, now, now, updated_by, seq),
    )
    conn.commit()
    new_id = cur.lastrowid
    conn.close()
    print(f"Added case #{new_id}: {title}")
    return new_id


def update_case(case_id, title=None, client=None, status=None, priority=None,
                 opened=None, notes=None, updated_by="desktop"):
    """Partial-field update, used by the sync server to apply a client-side
    edit through the engine's own code path."""
    ensure_db()
    conn = connect()
    row = conn.execute(
        "SELECT id FROM cases WHERE id = ? AND deleted_at IS NULL", (case_id,)
    ).fetchone()
    if row is None:
        conn.close()
        print(f"No case #{case_id}.")
        return
    if status is not None and status not in STATUSES:
        status = None
    if priority is not None and priority not in PRIORITIES:
        priority = None
    sets, vals = [], []
    for col, v in (("title", title), ("client", client), ("status", status),
                   ("priority", priority), ("opened", opened), ("notes", notes)):
        if v is not None:
            sets.append(f"{col} = ?")
            vals.append(v)
    if not sets:
        conn.close()
        print(f"No fields to update for case #{case_id}.")
        return
    seq = _next_seq(conn)
    sets.append("updated_at = ?"); vals.append(now_iso())
    sets.append("updated_by = ?"); vals.append(updated_by)
    sets.append("server_seq = ?"); vals.append(seq)
    vals.append(case_id)
    conn.execute(f"UPDATE cases SET {', '.join(sets)} WHERE id = ?", vals)
    conn.commit()
    conn.close()
    print(f"Updated case #{case_id}.")


def solve_case(case_id, updated_by="desktop"):
    ensure_db()
    conn = connect()
    row = conn.execute(
        "SELECT title FROM cases WHERE id = ? AND deleted_at IS NULL", (case_id,)
    ).fetchone()
    seq = _next_seq(conn)
    now = now_iso()
    conn.execute(
        "UPDATE cases SET status = 'solved', updated_at = ?, updated_by = ?, "
        "server_seq = ? WHERE id = ?",
        (now, updated_by, seq, case_id),
    )
    conn.commit()
    conn.close()
    print(f"Case #{case_id} solved: {row['title']}" if row else f"No case #{case_id}.")


# --------------------------------------------------------------------------
# Leads (the quick-capture table)
# --------------------------------------------------------------------------
def add_lead(text, case_id=None, urgency="medium", updated_by="desktop"):
    ensure_db()
    if urgency not in URGENCIES:
        urgency = "medium"
    conn = connect()
    seq = _next_seq(conn)
    now = now_iso()
    cur = conn.execute(
        "INSERT INTO leads (text, case_id, urgency, created_at, updated_at, updated_by, "
        "server_seq) VALUES (?, ?, ?, ?, ?, ?, ?)",
        (text, case_id, urgency, now, now, updated_by, seq),
    )
    conn.commit()
    new_id = cur.lastrowid
    conn.close()
    print(f"Added lead #{new_id}: {text}")
    return new_id


def update_lead(lead_id, text=None, case_id=None, urgency=None, done=None, updated_by="desktop"):
    ensure_db()
    conn = connect()
    row = conn.execute(
        "SELECT id FROM leads WHERE id = ? AND deleted_at IS NULL", (lead_id,)
    ).fetchone()
    if row is None:
        conn.close()
        print(f"No lead #{lead_id}.")
        return
    if urgency is not None and urgency not in URGENCIES:
        urgency = None
    sets, vals = [], []
    for col, v in (("text", text), ("case_id", case_id), ("urgency", urgency)):
        if v is not None:
            sets.append(f"{col} = ?")
            vals.append(v)
    if done is not None:
        sets.append("done = ?")
        vals.append(1 if done else 0)
    if not sets:
        conn.close()
        print(f"No fields to update for lead #{lead_id}.")
        return
    seq = _next_seq(conn)
    sets.append("updated_at = ?"); vals.append(now_iso())
    sets.append("updated_by = ?"); vals.append(updated_by)
    sets.append("server_seq = ?"); vals.append(seq)
    vals.append(lead_id)
    conn.execute(f"UPDATE leads SET {', '.join(sets)} WHERE id = ?", vals)
    conn.commit()
    conn.close()
    print(f"Updated lead #{lead_id}.")


def done_lead(lead_id, updated_by="desktop"):
    ensure_db()
    conn = connect()
    now = now_iso()
    seq = _next_seq(conn)
    conn.execute(
        "UPDATE leads SET done = 1, done_at = ?, updated_at = ?, updated_by = ?, "
        "server_seq = ? WHERE id = ?",
        (now, now, updated_by, seq, lead_id),
    )
    conn.commit()
    conn.close()
    print(f"Marked lead #{lead_id} done.")


def delete_lead(lead_id, updated_by="desktop"):
    ensure_db()
    conn = connect()
    seq = _next_seq(conn)
    now = now_iso()
    cur = conn.execute(
        "UPDATE leads SET deleted_at = ?, updated_at = ?, updated_by = ?, "
        "server_seq = ? WHERE id = ? AND deleted_at IS NULL",
        (now, now, updated_by, seq, lead_id),
    )
    conn.commit()
    n = cur.rowcount
    conn.close()
    print(f"Deleted lead #{lead_id}." if n else f"No lead #{lead_id}.")


# --------------------------------------------------------------------------
# Contacts (name-keyed)
# --------------------------------------------------------------------------
def add_contact(name, role=None, last_contact=None, notes=None, updated_by="desktop"):
    ensure_db()
    conn = connect()
    now = now_iso()
    seq = _next_seq(conn)
    try:
        conn.execute(
            "INSERT INTO contacts (name, role, last_contact, notes, created_at, "
            "updated_at, updated_by, server_seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (name, role, last_contact, notes, now, now, updated_by, seq),
        )
        conn.commit()
        print(f"Added contact: {name}")
    except sqlite3.IntegrityError:
        # Clears deleted_at -- re-adding a tombstoned contact revives it.
        conn.execute(
            "UPDATE contacts SET role = COALESCE(?, role), "
            "last_contact = COALESCE(?, last_contact), notes = COALESCE(?, notes), "
            "deleted_at = NULL, updated_at = ?, updated_by = ?, server_seq = ? "
            "WHERE name = ?",
            (role, last_contact, notes, now, updated_by, seq, name),
        )
        conn.commit()
        print(f"Updated contact: {name}")
    conn.close()


def log_contact(name, on_date=None, note=None, updated_by="desktop"):
    """Record today's (or a given) contact date, appending an optional note.
    Does NOT auto-create -- a log-contact against an unknown name is a
    no-op error, not a silent new row (mirrors the framework's exists-guard
    for named actions on the sync path)."""
    ensure_db()
    conn = connect()
    row = conn.execute(
        "SELECT notes FROM contacts WHERE name = ? AND deleted_at IS NULL", (name,)
    ).fetchone()
    if row is None:
        print(f"Contact '{name}' not found. Use add-contact first.")
        conn.close()
        return
    when = on_date or today_iso()
    now = now_iso()
    seq = _next_seq(conn)
    if note:
        merged_notes = f"{row['notes']}\n[{when}] {note}" if row["notes"] else f"[{when}] {note}"
    else:
        merged_notes = row["notes"]
    conn.execute(
        "UPDATE contacts SET last_contact = ?, notes = ?, updated_at = ?, updated_by = ?, "
        "server_seq = ? WHERE name = ?",
        (when, merged_notes, now, updated_by, seq, name),
    )
    conn.commit()
    conn.close()
    print(f"Logged contact with '{name}' on {when}.")


# --------------------------------------------------------------------------
# Sync dispatch config for the generic server (server/sync_server.py).
# Same shape as the reference implementation's per-table config: key_type,
# and each supported op mapping to an ENGINE FUNCTION ONLY -- never raw SQL.
# Named action ops (solve, done, log_contact) dispatch exactly like update.
# --------------------------------------------------------------------------
SYNC_TABLES = {
    "cases": {
        "key_type": "int",
        "create": {"fn": "add_case",
                   "fields": [("title", None), ("client", None), ("status", "investigating"),
                              ("priority", "medium"), ("opened", None), ("notes", None)]},
        "update": {"fn": "update_case",
                   "fields": [("title", None), ("client", None), ("status", None),
                              ("priority", None), ("opened", None), ("notes", None)]},
        "solve": {"fn": "solve_case", "fields": []},
    },
    "leads": {
        "key_type": "int",
        "create": {"fn": "add_lead",
                   "fields": [("text", None), ("case_id", None), ("urgency", "medium")]},
        "update": {"fn": "update_lead",
                   "fields": [("text", None), ("case_id", None), ("urgency", None), ("done", None)]},
        "delete": {"fn": "delete_lead"},
        "done": {"fn": "done_lead", "fields": []},
    },
    "contacts": {
        "key_type": "str",
        "create": {"fn": "add_contact", "fields": [("role", None), ("last_contact", None), ("notes", None)]},
        "update": {"fn": "add_contact", "fields": [("role", None), ("last_contact", None), ("notes", None)]},
        "log_contact": {"fn": "log_contact", "fields": [("on_date", None), ("note", None)]},
    },
}


# --------------------------------------------------------------------------
# Desktop (browser) edit endpoints -- synchronous, one-shot calls straight
# into the engine's own functions with updated_by="desktop". Each handler
# validates its own input and raises ValidationError with a human-readable
# message on bad input; the sync server turns that into an HTTP 400.
# --------------------------------------------------------------------------
def _req_str(body, field):
    v = body.get(field)
    v = v.strip() if isinstance(v, str) else v
    if not v:
        raise ValidationError(f"'{field}' is required")
    return v


def _opt_str(body, field):
    v = body.get(field)
    if v is None:
        return None
    v = str(v).strip()
    return v or None


def _req_int(body, field):
    v = body.get(field)
    if v is None or v == "":
        raise ValidationError(f"'{field}' is required")
    try:
        return int(v)
    except (TypeError, ValueError):
        raise ValidationError(f"'{field}' must be an integer")


def _opt_int(body, field):
    v = body.get(field)
    if v is None or v == "":
        return None
    try:
        return int(v)
    except (TypeError, ValueError):
        raise ValidationError(f"'{field}' must be an integer")


def _row_exists(table, key_col, key_val):
    conn = connect()
    row = conn.execute(
        f"SELECT {key_col} FROM {table} WHERE {key_col} = ? AND deleted_at IS NULL", (key_val,)
    ).fetchone()
    conn.close()
    return row is not None


def _dt_add_lead(body):
    text = _req_str(body, "text")
    case_id = _opt_int(body, "case_id")
    if case_id is not None and not _row_exists("cases", "id", case_id):
        raise ValidationError(f"no case #{case_id}")
    urgency = _opt_str(body, "urgency") or "medium"
    if urgency not in URGENCIES:
        urgency = "medium"
    add_lead(text, case_id, urgency, updated_by="desktop")


def _dt_done_lead(body):
    lead_id = _req_int(body, "id")
    if not _row_exists("leads", "id", lead_id):
        raise ValidationError(f"no lead #{lead_id}")
    done_lead(lead_id, updated_by="desktop")


def _dt_solve_case(body):
    case_id = _req_int(body, "id")
    if not _row_exists("cases", "id", case_id):
        raise ValidationError(f"no case #{case_id}")
    solve_case(case_id, updated_by="desktop")


def _dt_log_contact(body):
    name = _req_str(body, "name")
    if not _row_exists("contacts", "name", name):
        raise ValidationError(f"no contact named '{name}'")
    note = _opt_str(body, "note")
    log_contact(name, None, note, updated_by="desktop")


DESKTOP_ACTIONS = {
    "add-lead": _dt_add_lead,
    "done-lead": _dt_done_lead,
    "solve-case": _dt_solve_case,
    "log-contact": _dt_log_contact,
}


# --------------------------------------------------------------------------
# Snapshot (for briefings) and dashboard
# --------------------------------------------------------------------------
_SYNC_META_KEYS = ("updated_at", "updated_by", "deleted_at", "server_seq")


def gather():
    ensure_db()
    conn = connect()

    all_case_titles = {c["id"]: c["title"] for c in conn.execute(
        "SELECT id, title FROM cases WHERE deleted_at IS NULL"
    ).fetchall()}

    cases = [dict(c) for c in conn.execute(
        "SELECT * FROM cases WHERE deleted_at IS NULL ORDER BY created_at"
    ).fetchall()]
    for c in cases:
        for k in _SYNC_META_KEYS:
            c.pop(k, None)
    cases.sort(key=lambda x: (_STATUS_WEIGHT.get(x["status"], 0),
                               _PRIORITY_WEIGHT.get(x["priority"], 2)), reverse=True)

    leads = [dict(l) for l in conn.execute(
        "SELECT * FROM leads WHERE deleted_at IS NULL AND done = 0 ORDER BY created_at"
    ).fetchall()]
    for l in leads:
        for k in _SYNC_META_KEYS:
            l.pop(k, None)
        l["case_title"] = all_case_titles.get(l["case_id"])
    leads.sort(key=lambda x: _URGENCY_WEIGHT.get(x["urgency"], 2), reverse=True)

    contacts = [dict(k) for k in conn.execute(
        "SELECT * FROM contacts WHERE deleted_at IS NULL ORDER BY name"
    ).fetchall()]
    for k in contacts:
        for kk in _SYNC_META_KEYS:
            k.pop(kk, None)

    conn.close()
    return {"cases": cases, "leads": leads, "contacts": contacts, "generated": now_iso()}


def sync_status():
    """One cheap JSON answer to 'is there client-sync state a writer must
    handle before touching this DB?' -- unresolved conflict_log rows plus
    the newest client-attributed write as a freshness hint."""
    ensure_db()
    conn = connect()
    conflicts = [dict(r) for r in conn.execute(
        "SELECT id, table_name, record_id, phone_payload, desktop_row, resolution, "
        "created_at FROM conflict_log WHERE resolved = 0 ORDER BY created_at"
    ).fetchall()]
    last_phone = None
    for table in _SYNC_TABLES:
        row = conn.execute(
            f"SELECT MAX(updated_at) AS m FROM {table} WHERE updated_by = 'phone'"
        ).fetchone()
        if row["m"] and (last_phone is None or row["m"] > last_phone):
            last_phone = row["m"]
    conn.close()
    print(json.dumps({
        "db": "casework",
        "unresolved_conflicts": conflicts,
        "last_phone_write": last_phone,
    }, indent=2))


def resolve_conflict(conflict_id):
    ensure_db()
    conn = connect()
    n = conn.execute(
        "UPDATE conflict_log SET resolved = 1 WHERE id = ? AND resolved = 0",
        (conflict_id,),
    ).rowcount
    conn.commit()
    conn.close()
    print(f"Conflict #{conflict_id}: {'resolved' if n else 'not found or already resolved'}.")


def status(as_text):
    data = gather()
    if not as_text:
        print(json.dumps(data, indent=2))
        return

    lines = ["OPEN CASES"]
    any_case = False
    for c in data["cases"]:
        if c["status"] == "solved":
            continue
        any_case = True
        client = f" ({c['client']})" if c["client"] else ""
        lines.append(f"  #{c['id']} {c['title']}{client} - {c['status']}, {c['priority']} priority")
    if not any_case:
        lines.append("  nothing open")

    lines.append("\nLEADS")
    for l in data["leads"]:
        case_tag = f" [{l['case_title']}]" if l["case_title"] else ""
        lines.append(f"  #{l['id']} {l['text']}{case_tag} - {l['urgency']} urgency")
    if not data["leads"]:
        lines.append("  nothing outstanding")

    lines.append("\nCONTACTS")
    for k in data["contacts"]:
        role = f" ({k['role']})" if k["role"] else ""
        last = f", last contact {k['last_contact']}" if k["last_contact"] else ", never contacted"
        lines.append(f"  {k['name']}{role}{last}")
    if not data["contacts"]:
        lines.append("  none tracked")

    print("\n".join(lines))


def build_dashboard():
    data = gather()
    html = render_html(data)
    with open(HTML_PATH, "w", encoding="utf-8") as f:
        f.write(html)
    print(f"Dashboard written to {HTML_PATH}")


def escape(s):
    if s is None:
        return ""
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _attr(s):
    return escape(s).replace('"', "&quot;")


def _chip(text, kind):
    return f'<span class="chip {kind}">{escape(text)}</span>'


def _md(iso):
    d = parse_date(iso)
    if not d:
        return iso or ""
    return d.strftime("%b ") + str(d.day)


def render_html(data):
    gen = datetime.fromisoformat(data["generated"]).strftime("%A %d %B, %I:%M %p")

    # Cases
    case_rows = []
    for c in data["cases"]:
        status_kind = _KIND_BY_STATUS.get(c["status"], "ghost")
        pr_kind = _KIND_BY_PRIORITY.get(c["priority"], "ghost")
        client = f'<span class="row-sub">{escape(c["client"])}</span>' if c["client"] else ""
        opened = f'<span class="row-sub">opened {_md(c["opened"])}</span>' if c["opened"] else ""
        notes = f'<div class="case-notes">{escape(c["notes"])}</div>' if c["notes"] else ""
        solve_btn = ""
        if c["status"] != "solved":
            solve_btn = (
                f'<span class="row-actions">'
                f'<button type="button" class="mini-btn" data-id="{c["id"]}" '
                f'onclick="caseworkSolveCase(this)">Solve</button></span>'
            )
        case_rows.append(
            f'<li class="case-card">'
            f'<div class="row"><span class="row-name">#{c["id"]} {escape(c["title"])}</span>'
            f'{client}{opened}'
            f'{_chip(c["status"], status_kind)}{_chip(c["priority"], pr_kind)}{solve_btn}</div>'
            f'{notes}</li>'
        )
    if not case_rows:
        case_rows.append('<li class="empty">No cases on the books.</li>')

    # Leads
    lead_rows = []
    for l in data["leads"]:
        case_tag = f'<span class="row-sub">{escape(l["case_title"])}</span>' if l["case_title"] else ""
        urg_kind = _KIND_BY_URGENCY.get(l["urgency"], "ghost")
        lead_rows.append(
            f'<li class="task">'
            f'<input type="checkbox" class="task-check" '
            f'onchange="caseworkDoneLead({l["id"]}, this)">'
            f'<span class="task-text">{escape(l["text"])}</span>'
            f'<span class="meta">{case_tag}{_chip(l["urgency"], urg_kind)}</span></li>'
        )
    if not lead_rows:
        lead_rows.append('<li class="empty">No open leads. The trail is cold.</li>')

    # Contacts
    contact_rows = []
    for k in data["contacts"]:
        role = f'<span class="row-sub">{escape(k["role"])}</span>' if k["role"] else ""
        last = f"last contact {_md(k['last_contact'])}" if k["last_contact"] else "never contacted"
        name_attr = _attr(k["name"])
        actions = (
            f'<span class="row-actions">'
            f'<button type="button" class="mini-btn" data-name="{name_attr}" '
            f'onclick="caseworkLogContact(this)">Log contact</button></span>'
        )
        contact_rows.append(
            f'<li class="row"><span class="row-name">{escape(k["name"])}</span>'
            f'{role}<span class="row-sub">{last}</span>{actions}</li>'
        )
    if not contact_rows:
        contact_rows.append('<li class="empty">No contacts tracked yet.</li>')

    return TEMPLATE.format(
        generated=gen,
        cases="\n".join(case_rows),
        leads="\n".join(lead_rows),
        contacts="\n".join(contact_rows),
    )


TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="900">
<title>Casework</title>
<style>
  :root {{
    --bg: #0d1117; --panel: #161b22; --ink: #c9d1d9; --muted: #6e7681;
    --line: #232a33; --red: #ff7b72; --amber: #e4b65c; --green: #8fb96a; --accent: #9db8d2;
  }}
  * {{ box-sizing: border-box; margin: 0; padding: 0; }}
  body {{
    background: var(--bg); color: var(--ink);
    font-family: "Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif;
    line-height: 1.5; padding: 32px 20px 64px; max-width: 780px; margin: 0 auto;
    -webkit-font-smoothing: antialiased;
  }}
  header {{ margin-bottom: 28px; }}
  .kicker {{ font-family: ui-monospace, "SF Mono", Menlo, monospace; text-transform: uppercase;
    letter-spacing: 0.22em; font-size: 11px; color: var(--muted); }}
  h1 {{ font-size: 32px; font-weight: 600; letter-spacing: -0.01em; margin-top: 4px; font-style: italic; }}
  .stamp {{ color: var(--muted); font-size: 13px; margin-top: 6px;
    font-family: ui-monospace, "SF Mono", Menlo, monospace; }}
  section {{ background: var(--panel); border: 1px solid var(--line); border-radius: 14px;
    padding: 20px 22px; margin-bottom: 18px; }}
  h2 {{ font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 12px; letter-spacing: 0.18em;
    text-transform: uppercase; color: var(--accent); margin-bottom: 14px; }}
  ul {{ list-style: none; }}
  .action-error {{ background: rgba(255,123,114,0.12); border: 1px solid rgba(255,123,114,0.35);
    color: var(--red); border-radius: 10px; padding: 10px 14px; margin-bottom: 16px; font-size: 13px; }}
  .action-error[hidden] {{ display: none; }}
  .case-card {{ padding: 12px 0; border-top: 1px solid var(--line); }}
  .case-card:first-child {{ border-top: none; }}
  .case-notes {{ color: var(--muted); font-size: 13px; margin-top: 6px;
    font-family: ui-monospace, monospace; }}
  .task {{ display: grid; grid-template-columns: 22px 1fr; gap: 4px 12px; padding: 12px 0;
    border-top: 1px solid var(--line); }}
  .task:first-child {{ border-top: none; }}
  .task-check {{ align-self: start; margin-top: 4px; width: 16px; height: 16px; }}
  .task-text {{ font-size: 17px; }}
  .meta {{ grid-column: 2; display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px; }}
  .row {{ display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px; padding: 4px 0; }}
  .row-name {{ font-size: 17px; flex: 0 0 auto; }}
  .row-sub {{ color: var(--muted); font-size: 13px; flex: 0 0 auto;
    font-family: ui-monospace, monospace; }}
  .row-actions {{ display: inline-flex; gap: 6px; margin-left: auto; }}
  .mini-btn {{ font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 11px;
    letter-spacing: 0.03em; padding: 2px 8px; border-radius: 999px; border: 1px solid var(--line);
    background: transparent; color: var(--accent); cursor: pointer; }}
  .mini-btn:hover {{ border-color: var(--accent); }}
  .quickadd {{ display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; padding-top: 14px;
    border-top: 1px solid var(--line); }}
  .quickadd input[type="text"] {{ flex: 1 1 200px; background: var(--bg); color: var(--ink);
    border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; font-family: inherit; font-size: 14px; }}
  .quickadd select, .quickadd button {{ background: var(--bg); color: var(--ink);
    border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; font-family: inherit;
    font-size: 14px; cursor: pointer; }}
  .form-error {{ color: var(--red); font-size: 12px; flex-basis: 100%; }}
  .chip {{ font-family: ui-monospace, monospace; font-size: 11px; letter-spacing: 0.04em;
    padding: 3px 8px; border-radius: 999px; white-space: nowrap; }}
  .red   {{ background: rgba(255,123,114,0.16); color: var(--red);   border: 1px solid rgba(255,123,114,0.35); }}
  .amber {{ background: rgba(228,182,92,0.14);  color: var(--amber); border: 1px solid rgba(228,182,92,0.32); }}
  .green {{ background: rgba(143,185,106,0.14); color: var(--green); border: 1px solid rgba(143,185,106,0.30); }}
  .ghost {{ background: transparent; color: var(--muted); border: 1px solid var(--line); }}
  .empty {{ color: var(--muted); font-style: italic; padding: 6px 0; }}
  footer {{ color: var(--muted); font-size: 12px; text-align: center; margin-top: 28px;
    font-family: ui-monospace, monospace; }}
  @media (max-width: 560px) {{
    body {{ padding: 22px 14px 48px; }}
    h1 {{ font-size: 25px; }}
    section {{ padding: 16px 16px; }}
    .row {{ flex-direction: column; align-items: flex-start; }}
    .row-actions {{ margin-left: 0; }}
  }}
</style>
</head>
<body>
  <header>
    <div class="kicker">Casework</div>
    <h1>The game is afoot.</h1>
    <div class="stamp">updated {generated}</div>
  </header>

  <div id="casework-error" class="action-error" hidden></div>

  <section>
    <h2>Open cases</h2>
    <ul>
      {cases}
    </ul>
  </section>

  <section>
    <h2>Leads</h2>
    <ul>
      {leads}
    </ul>
    <form class="quickadd" onsubmit="return caseworkAddLead(event)">
      <input type="text" name="text" placeholder="New lead..." required>
      <select name="urgency">
        <option value="low">low</option>
        <option value="medium" selected>medium</option>
        <option value="high">high</option>
      </select>
      <button type="submit">Add</button>
      <span class="form-error" id="lead-add-error"></span>
    </form>
  </section>

  <section>
    <h2>Contacts</h2>
    <ul>
      {contacts}
    </ul>
  </section>

  <footer>auto-refreshes every 15 min while open</footer>

  <script>
  // Every control POSTs to the sync server's /api/casework/desktop/<action>
  // endpoint (the same engine functions the CLI uses, stamped
  // updated_by='desktop'); the engine rebuilds this HTML on every write, so
  // a successful POST just reloads the page. A failed POST shows an inline
  // error and does NOT reload.
  async function caseworkPost(action, data) {{
    try {{
      const res = await fetch('/api/casework/desktop/' + action, {{
        method: 'POST',
        headers: {{'Content-Type': 'application/json'}},
        body: JSON.stringify(data)
      }});
      if (!res.ok) {{
        let msg = 'request failed (' + res.status + ')';
        try {{ const j = await res.json(); if (j.error) msg = j.error; }} catch (e) {{}}
        return {{ok: false, error: msg}};
      }}
      return {{ok: true}};
    }} catch (e) {{
      return {{ok: false, error: 'network error: ' + e}};
    }}
  }}

  function caseworkShowError(msg) {{
    const el = document.getElementById('casework-error');
    el.textContent = msg;
    el.hidden = false;
  }}

  async function caseworkDoneLead(id, checkbox) {{
    checkbox.disabled = true;
    const r = await caseworkPost('done-lead', {{id: id}});
    if (r.ok) {{ location.reload(); }}
    else {{ checkbox.checked = false; checkbox.disabled = false; caseworkShowError(r.error); }}
  }}

  async function caseworkAddLead(ev) {{
    ev.preventDefault();
    const form = ev.target;
    const errEl = document.getElementById('lead-add-error');
    errEl.textContent = '';
    const r = await caseworkPost('add-lead', {{text: form.text.value, urgency: form.urgency.value}});
    if (r.ok) {{ location.reload(); }}
    else {{ errEl.textContent = r.error; }}
    return false;
  }}

  async function caseworkSolveCase(btn) {{
    btn.disabled = true;
    const r = await caseworkPost('solve-case', {{id: btn.dataset.id}});
    if (r.ok) {{ location.reload(); }}
    else {{ btn.disabled = false; caseworkShowError(r.error); }}
  }}

  async function caseworkLogContact(btn) {{
    const note = window.prompt('Note (optional):', '') || '';
    btn.disabled = true;
    const r = await caseworkPost('log-contact', {{name: btn.dataset.name, note: note}});
    if (r.ok) {{ location.reload(); }}
    else {{ btn.disabled = false; caseworkShowError(r.error); }}
  }}
  </script>
</body>
</html>
"""


# --------------------------------------------------------------------------
# Seed data -- fiction only, for `init --seed`. A day in the life of a
# modern consulting detective: three open cases, five leads, four contacts.
# --------------------------------------------------------------------------
def seed_data():
    today = date.today()

    add_case(
        "The Vanishing Courier of Camden Lock",
        client="Scotland Yard",
        status="investigating",
        priority="high",
        opened=(today - timedelta(days=6)).isoformat(),
        notes="Motorcycle courier failed to complete his round; last signal was an e-bike "
              "charging-dock ping near the canal towpath.",
    )
    add_case(
        "The Locked Flat on Marylebone Road",
        client="Scotland Yard",
        status="awaiting_lab",
        priority="medium",
        opened=(today - timedelta(days=11)).isoformat(),
        notes="No forced entry, no sign of struggle. A contactless payment was made from the "
              "tenant's card two hours after neighbours last saw him.",
    )
    add_case(
        "The Silent Group Chat",
        client="private client (name withheld)",
        status="stakeout",
        priority="medium",
        opened=(today - timedelta(days=2)).isoformat(),
        notes="Five old friends, one group chat, radio silence since Tuesday night -- the same "
              "night one of them stopped answering his phone.",
    )

    add_lead("Pull the 221B doorbell-cam footage from the night of the disappearance",
              case_id=1, urgency="high")
    add_lead("Ask Wiggins to trace the courier's e-bike serial number",
              case_id=1, urgency="high")
    add_lead("Cross-reference contactless payment terminals near Marylebone Road",
              case_id=2, urgency="medium")
    add_lead("Get Mrs. Hudson's account of who came and went that week",
              case_id=2, urgency="low")
    add_lead("Request the group chat's server-side timestamps (metadata only, not content)",
              case_id=3, urgency="medium")

    add_contact(
        "Dr. Watson", role="flatmate & chronicler",
        last_contact=(today - timedelta(days=1)).isoformat(),
        notes="Keeps the case notebook; reachable most evenings at 221B.",
    )
    add_contact(
        "DI Lestrade", role="Scotland Yard",
        last_contact=(today - timedelta(days=2)).isoformat(),
        notes="Official liaison; wants results before the press cycle turns.",
    )
    add_contact(
        "Mrs. Hudson", role="landlady",
        last_contact=today.isoformat(),
        notes="Sees everyone who comes and goes at 221B; excellent memory for faces.",
    )
    add_contact(
        "Mycroft Holmes", role="Whitehall",
        last_contact=(today - timedelta(days=5)).isoformat(),
        notes="Occasional consult; prefers a single tidy paragraph, no theatrics.",
    )
    print("Seeded 3 cases, 5 leads, 4 contacts.")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def main():
    p = argparse.ArgumentParser(description="Casework local store")
    sub = p.add_subparsers(dest="cmd")

    a = sub.add_parser("init")
    a.add_argument("--seed", action="store_true", help="also populate fictional example data")

    a = sub.add_parser("add-case")
    a.add_argument("title")
    a.add_argument("--client", default=None)
    a.add_argument("--status", default="investigating", choices=STATUSES)
    a.add_argument("--priority", default="medium", choices=PRIORITIES)
    a.add_argument("--opened", default=None, help="ISO date YYYY-MM-DD")
    a.add_argument("--notes", default=None)

    a = sub.add_parser("solve-case")
    a.add_argument("id", type=int)

    a = sub.add_parser("add-lead")
    a.add_argument("text")
    a.add_argument("--case", type=int, default=None, dest="case_id")
    a.add_argument("--urgency", default="medium", choices=URGENCIES)

    a = sub.add_parser("done-lead")
    a.add_argument("id", type=int)

    a = sub.add_parser("delete-lead")
    a.add_argument("id", type=int)

    a = sub.add_parser("add-contact")
    a.add_argument("name")
    a.add_argument("--role", default=None)
    a.add_argument("--notes", default=None)

    a = sub.add_parser("log-contact")
    a.add_argument("name")
    a.add_argument("--date", default=None, dest="on_date", help="ISO date; defaults to today")
    a.add_argument("--note", default=None)

    a = sub.add_parser("status")
    a.add_argument("--text", action="store_true")

    sub.add_parser("sync-status")

    a = sub.add_parser("resolve-conflict")
    a.add_argument("id", type=int)

    sub.add_parser("dashboard")

    args = p.parse_args()

    if args.cmd == "init":
        init_db()
        print(f"Initialized {DB_PATH}")
        if args.seed:
            seed_data()
            build_dashboard()
    elif args.cmd == "add-case":
        add_case(args.title, args.client, args.status, args.priority, args.opened, args.notes)
        build_dashboard()
    elif args.cmd == "solve-case":
        solve_case(args.id)
        build_dashboard()
    elif args.cmd == "add-lead":
        add_lead(args.text, args.case_id, args.urgency)
        build_dashboard()
    elif args.cmd == "done-lead":
        done_lead(args.id)
        build_dashboard()
    elif args.cmd == "delete-lead":
        delete_lead(args.id)
        build_dashboard()
    elif args.cmd == "add-contact":
        add_contact(args.name, args.role, notes=args.notes)
        build_dashboard()
    elif args.cmd == "log-contact":
        log_contact(args.name, args.on_date, args.note)
        build_dashboard()
    elif args.cmd == "status":
        status(as_text=args.text)
    elif args.cmd == "sync-status":
        sync_status()
    elif args.cmd == "resolve-conflict":
        resolve_conflict(args.id)
    elif args.cmd == "dashboard":
        build_dashboard()
    else:
        p.print_help()


if __name__ == "__main__":
    main()
