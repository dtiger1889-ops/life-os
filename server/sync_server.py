#!/usr/bin/env python3
"""
Life OS framework starter -- generic sync server.

This is the public starter-kit fork of the Life OS pattern: one small HTTP
server that serves a "home hub" page plus any number of dashboards you
declare in a config file, and exposes a small JSON sync API so a phone (or
any other client) can pull/push against each dashboard's own SQLite store.

The server carries ZERO per-dashboard business logic. Every dashboard is a
plain Python "engine" module (see example/casework/casework.py for a full
reference implementation) that exports two things:

    SYNC_TABLES     -- {table: {"key_type": "int"|"str",
                                 "create"/"update"/"delete"/<named ops>:
                                     {"fn": "<engine function name>",
                                      "fields": [(field, default), ...]}}}
                       Same shape you'd hand-write for any new table; the
                       server dispatches every push mutation through
                       getattr(engine_module, op_cfg["fn"]) -- it never
                       touches SQL directly.

    DESKTOP_ACTIONS -- {action_name: callable(body_dict)}. Each callable
                       validates its own input and calls straight into the
                       engine's own functions, stamping updated_by="desktop".
                       Bad input raises the module's own `ValidationError`
                       class (any class literally named that on the module),
                       which the server turns into an HTTP 400.

Routing:
    GET  /                       -> this server's own directory (home.html,
                                    dashboards.json -- the hub)
    GET  /<name>/*               -> the dashboard's own static dir (its
                                    generated dashboard.html etc.)
    GET  /health                 -> {"ok": true, "db_seq": {"<name>": N, ...}}
    GET  /api/<name>/pull?since=<server_seq>
    POST /api/<name>/push        -> body {"mutations": [...]}
    POST /api/<name>/desktop/<action>

Design note: the reference Life OS deployment this was forked from mounted
its primary dashboard directly at "/" (no separate hub page existed yet).
This starter kit ships a real hub (server/home.html), so "/" always serves
THIS directory instead -- every dashboard, including the first/only one,
gets its own "/<name>/" mount. That avoids a same-path collision between
the hub's index and a dashboard's own generated page.

No external dependencies. Python 3.9+ and the standard library only.

Usage:
    python3 sync_server.py --port 8765
    python3 sync_server.py --port 8770 --config /path/to/dashboards.config.json
"""

import argparse
import importlib.util
import json
import os
import sys
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CONFIG = os.path.join(HERE, "dashboards.config.json")

# name -> {"mod": <loaded engine module>, "tables": <SYNC_TABLES>, "dir": <static dir>}
DB_REGISTRY = {}


def _load_engine(name, dir_path, filename):
    """Import an engine module (e.g. casework.py) from an arbitrary directory
    via importlib, so the config's "dir" can point anywhere without editing
    sys.path. The loaded module's own HERE/DB_PATH (computed from __file__)
    resolve to dir_path, so it reads/writes whichever copy we pointed it at."""
    path = os.path.join(dir_path, filename)
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def load_config(config_path):
    """dashboards.config.json shape:
        {"dashboards": [{"name": "casework", "dir": "../example/casework",
                          "engine": "casework.py"}, ...]}
    "dir" is resolved relative to the config file's own directory, so the
    config can live anywhere and still find its dashboards."""
    with open(config_path, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    base_dir = os.path.dirname(os.path.abspath(config_path))
    dashboards = []
    for entry in cfg.get("dashboards", []):
        name = entry["name"]
        dir_path = os.path.normpath(os.path.join(base_dir, entry["dir"]))
        engine_file = entry.get("engine", f"{name}.py")
        dashboards.append({"name": name, "dir": dir_path, "engine": engine_file})
    return dashboards


# --------------------------------------------------------------------------
# Pull / push -- generic dispatch over whatever SYNC_TABLES the engine module
# declares. Mirrors the reference implementation's _apply_one exactly, just
# parameterized by db_name instead of hand-listing two hardcoded databases.
# --------------------------------------------------------------------------
def do_pull(db_name, since):
    reg = DB_REGISTRY[db_name]
    mod, tables = reg["mod"], reg["tables"]
    conn = mod.connect()
    rows = []
    for table in tables:
        for r in conn.execute(
            f"SELECT * FROM {table} WHERE server_seq > ? ORDER BY server_seq", (since,)
        ).fetchall():
            rows.append({"table": table, "record": dict(r)})
    conn.close()
    rows.sort(key=lambda x: x["record"]["server_seq"])
    new_cursor = rows[-1]["record"]["server_seq"] if rows else since
    return {"rows": rows, "new_cursor": new_cursor}


def _apply_one(db_name, mutation):
    reg = DB_REGISTRY[db_name]
    mod, tables = reg["mod"], reg["tables"]
    mutation_id = mutation.get("mutation_id")
    table = mutation.get("table")
    op = mutation.get("op")
    record_id = mutation.get("record_id")
    payload = mutation.get("payload") or {}
    base_version = mutation.get("base_version_updated_at")

    if not mutation_id:
        return {"mutation_id": mutation_id, "status": "rejected", "reason": "missing mutation_id"}
    cfg = tables.get(table)
    if cfg is None:
        return {"mutation_id": mutation_id, "status": "rejected", "reason": f"unknown table '{table}'"}
    op_cfg = cfg.get(op)
    if op_cfg is None:
        return {"mutation_id": mutation_id, "status": "rejected",
                "reason": f"unsupported op '{op}' for table '{table}'"}

    conn = mod.connect()
    conn.execute("PRAGMA busy_timeout = 5000;")
    try:
        # Idempotency: a replayed mutation_id is a no-op, still acked.
        already = conn.execute(
            "SELECT 1 FROM applied_mutations WHERE mutation_id = ?", (mutation_id,)
        ).fetchone()
        if already:
            return {"mutation_id": mutation_id, "status": "applied", "note": "replay (no-op)"}

        key_col = "name" if cfg["key_type"] == "str" else "id"
        cast_id = record_id
        if cfg["key_type"] == "int" and record_id is not None:
            cast_id = int(record_id)

        conflict = False
        if op != "create" and cast_id is not None:
            cur = conn.execute(f"SELECT * FROM {table} WHERE {key_col} = ?", (cast_id,)).fetchone()
            if cur is None:
                # Non-create ops must target an EXISTING row -- without this
                # guard a name-keyed engine fn that upserts-by-name would
                # silently CREATE a row from a bad/stale client name.
                return {"mutation_id": mutation_id, "status": "rejected",
                        "reason": f"no existing {table} row for {key_col}={cast_id!r}"}
            cur_updated_at = cur["updated_at"]
            if base_version is not None and cur_updated_at != base_version:
                conflict = True
                conn.execute(
                    "INSERT INTO conflict_log (table_name, record_id, phone_payload, "
                    "desktop_row, resolution, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    (table, str(cast_id), json.dumps(payload), json.dumps(dict(cur)),
                     "phone_applied_lww", mod.now_iso()),
                )
                conn.commit()

        fields_spec = op_cfg.get("fields") if "fields" in op_cfg else None
        if fields_spec is not None:
            kwargs = {fname: payload.get(fname, default) for fname, default in fields_spec}
        else:
            kwargs = dict(payload)

        fn = getattr(mod, op_cfg["fn"])
        try:
            if op == "create":
                # Name-keyed tables take the business key as their first
                # positional arg even on create (it's an upsert-by-name in
                # the engine). Id-keyed tables assign the id themselves --
                # record_id is ignored on create.
                if cfg["key_type"] == "str":
                    fn(cast_id, **kwargs, updated_by="phone")
                else:
                    fn(**kwargs, updated_by="phone")
            elif op == "delete":
                extra = op_cfg.get("args")
                if extra:
                    fn(extra[0], extra[1], cast_id, extra[2], updated_by="phone")
                else:
                    fn(cast_id, updated_by="phone")
            else:
                # "update" plus every named-action op (solve, done,
                # log_contact, ...) share the same call shape:
                # fn(existing_row_key, **field_subset, updated_by="phone").
                fn(cast_id, **kwargs, updated_by="phone")
        except Exception as e:  # noqa: BLE001 -- report per-mutation, never crash the batch
            return {"mutation_id": mutation_id, "status": "error", "reason": str(e)}

        conn.execute(
            "INSERT INTO applied_mutations (mutation_id, applied_at) VALUES (?, ?)",
            (mutation_id, mod.now_iso()),
        )
        conn.commit()
        return {"mutation_id": mutation_id, "status": "applied", "conflict": conflict}
    finally:
        conn.close()


def do_push(db_name, mutations):
    acks = [_apply_one(db_name, m) for m in mutations]
    # Dashboard HTML regenerates once per push (cheaper than once per mutation).
    if any(a.get("status") == "applied" for a in acks):
        DB_REGISTRY[db_name]["mod"].build_dashboard()
    return acks


def do_health():
    out = {}
    for name, reg in DB_REGISTRY.items():
        conn = reg["mod"].connect()
        row = conn.execute("SELECT value FROM sync_counter WHERE id = 1").fetchone()
        conn.close()
        out[name] = row[0] if row else 0
    return {"ok": True, "db_seq": out}


def do_desktop_action(db_name, action, body):
    """Dispatch one browser-edit request straight into the engine module's
    own DESKTOP_ACTIONS dict. Returns nothing on success (caller rebuilds
    the dashboard); raises KeyError for an unknown action, or the engine
    module's own ValidationError for bad input -- the HTTP layer turns
    both into an error response."""
    reg = DB_REGISTRY[db_name]
    mod = reg["mod"]
    actions = getattr(mod, "DESKTOP_ACTIONS", None) or {}
    handler = actions.get(action)
    if handler is None:
        raise KeyError(f"unknown desktop action '{action}' for dashboard '{db_name}'")
    handler(body)


# --------------------------------------------------------------------------
# HTTP handler
# --------------------------------------------------------------------------
class SyncHandler(SimpleHTTPRequestHandler):
    server_root = None      # this server's own dir (home.html, dashboards.json) -- served at "/"
    dashboard_dirs = None   # {name: dir_path} -- each served at "/<name>/"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - - [%s] %s\n" % (
            self.address_string(), self.log_date_time_string(), fmt % args))

    def _send_json(self, obj, status=200):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        return json.loads(raw) if raw else {}

    def _read_any_body(self):
        """Desktop endpoints accept either JSON (fetch() controls send this)
        or form-encoded bodies (in case a control ever posts as a plain HTML
        form)."""
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        if not raw:
            return {}
        ctype = (self.headers.get("Content-Type") or "").split(";")[0].strip().lower()
        if ctype == "application/json":
            try:
                return json.loads(raw)
            except json.JSONDecodeError:
                raise ValueError("invalid JSON body")
        parsed = parse_qs(raw.decode("utf-8"), keep_blank_values=True)
        return {k: v[0] for k, v in parsed.items()}

    # ---- routing ----
    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            return self._send_json(do_health())
        if parsed.path.startswith("/api/"):
            return self._handle_api_get(parsed)
        self._serve_static(parsed)

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path.startswith("/api/"):
            return self._handle_api_post(parsed)
        self.send_error(404, "Not found")

    def _handle_api_get(self, parsed):
        parts = [p for p in parsed.path.split("/") if p]
        # parts = ["api", "<name>", "pull"]
        if len(parts) != 3 or parts[2] != "pull" or parts[1] not in DB_REGISTRY:
            return self._send_json({"error": "unknown endpoint"}, status=404)
        db_name = parts[1]
        qs = parse_qs(parsed.query)
        since = int(qs.get("since", ["0"])[0])
        return self._send_json(do_pull(db_name, since))

    def _handle_api_post(self, parsed):
        parts = [p for p in parsed.path.split("/") if p]
        if len(parts) == 3 and parts[2] == "push" and parts[1] in DB_REGISTRY:
            return self._handle_push(parts[1])
        if len(parts) == 4 and parts[2] == "desktop" and parts[1] in DB_REGISTRY:
            return self._handle_desktop(parts[1], parts[3])
        return self._send_json({"error": "unknown endpoint"}, status=404)

    def _handle_push(self, db_name):
        try:
            body = self._read_json_body()
        except json.JSONDecodeError:
            return self._send_json({"error": "invalid JSON body"}, status=400)
        mutations = body.get("mutations") or []
        acks = do_push(db_name, mutations)
        return self._send_json({"acks": acks})

    def _handle_desktop(self, db_name, action):
        try:
            body = self._read_any_body()
        except ValueError as e:
            return self._send_json({"error": str(e)}, status=400)
        mod = DB_REGISTRY[db_name]["mod"]
        validation_error_cls = getattr(mod, "ValidationError", Exception)
        try:
            do_desktop_action(db_name, action, body)
        except KeyError as e:
            return self._send_json({"error": str(e)}, status=404)
        except validation_error_cls as e:
            return self._send_json({"error": str(e)}, status=400)
        mod.build_dashboard()
        return self._send_json({"ok": True})

    def _serve_static(self, parsed):
        parts = [p for p in parsed.path.split("/") if p]
        if parts and parts[0] in self.dashboard_dirs:
            # /<name>/* -> that dashboard's own static dir, prefix stripped.
            name = parts[0]
            self.directory = self.dashboard_dirs[name]
            original_path = self.path
            self.path = "/" + "/".join(parts[1:]) if len(parts) > 1 else "/"
            try:
                super().do_GET()
            finally:
                self.path = original_path
        else:
            # "/" and everything else -> this server's own directory (the
            # hub). SimpleHTTPRequestHandler only auto-serves "index.html"
            # for a bare "/" -- our hub file is named home.html, so rewrite
            # the bare-root request to it explicitly (otherwise "/" falls
            # through to a directory listing).
            self.directory = self.server_root
            original_path = self.path
            if parsed.path == "/":
                self.path = "/home.html"
            try:
                super().do_GET()
            finally:
                self.path = original_path


def main():
    p = argparse.ArgumentParser(description="Life OS framework starter -- generic sync server")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--config", default=None,
                   help="Path to dashboards.config.json (default: the copy next to this script)")
    args = p.parse_args()

    config_path = args.config or DEFAULT_CONFIG
    dashboards = load_config(config_path)
    if not dashboards:
        print(f"Warning: no dashboards configured in {config_path}")

    dashboard_dirs = {}
    for d in dashboards:
        mod = _load_engine(f"{d['name']}_engine", d["dir"], d["engine"])
        mod.ensure_db()
        tables = getattr(mod, "SYNC_TABLES", {})
        DB_REGISTRY[d["name"]] = {"mod": mod, "tables": tables, "dir": d["dir"]}
        dashboard_dirs[d["name"]] = d["dir"]

    SyncHandler.server_root = os.path.dirname(os.path.abspath(config_path))
    SyncHandler.dashboard_dirs = dashboard_dirs

    server = ThreadingHTTPServer(("0.0.0.0", args.port), SyncHandler)
    print(f"Life OS framework starter sync server on :{args.port}")
    print(f"  hub (server root): {SyncHandler.server_root}")
    for name, dirp in dashboard_dirs.items():
        print(f"  {name}: {dirp}  (mounted at /{name}/)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
