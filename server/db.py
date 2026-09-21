"""SQLite persistence for Eternity II Lab.

Everything the application knows lives in one file (``data/eternity2.sqlite``):
attempts, the exact settings each attempt used, the placement order needed for
replay, progress samples for the charts, and what the learner has concluded.
Delete the file to start fresh; keep it and the app resumes exactly where it
stopped.

All access goes through a single connection guarded by a lock.  Write volume is
tiny (a few hundred rows per attempt) so this is simpler and safer than a pool.
"""

import json
import os
import sqlite3
import threading
import time

SCHEMA_VERSION = 3

_DDL = """
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS attempts (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at    REAL NOT NULL,
    finished_at   REAL,
    status        TEXT NOT NULL DEFAULT 'running',
    solved        INTEGER NOT NULL DEFAULT 0,
    valid         INTEGER NOT NULL DEFAULT 1,
    best_depth    INTEGER NOT NULL DEFAULT 0,
    -- matched internal edges of the best board, out of 480; NULL on attempts
    -- recorded before the engine reported it
    matched_edges INTEGER,
    -- deliberately mismatched edges of the best board (edge slipping); 0 on
    -- attempts that never slip and NULL on attempts recorded before this was
    -- tracked, same convention as matched_edges
    breaks        INTEGER,
    nodes         INTEGER NOT NULL DEFAULT 0,
    duration_ms   INTEGER NOT NULL DEFAULT 0,
    nodes_per_sec INTEGER NOT NULL DEFAULT 0,
    restarts      INTEGER NOT NULL DEFAULT 0,
    user_defined  INTEGER NOT NULL DEFAULT 0,
    source        TEXT NOT NULL DEFAULT 'tuner',
    score         REAL NOT NULL DEFAULT 0,
    config_json   TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_attempts_started ON attempts(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_attempts_depth   ON attempts(best_depth DESC);

CREATE TABLE IF NOT EXISTS placements (
    attempt_id INTEGER NOT NULL,
    seq        INTEGER NOT NULL,
    cell       INTEGER NOT NULL,
    piece      INTEGER NOT NULL,
    rot        INTEGER NOT NULL,
    PRIMARY KEY (attempt_id, seq)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS samples (
    attempt_id INTEGER NOT NULL,
    seq        INTEGER NOT NULL,
    ms         INTEGER NOT NULL,
    nodes      INTEGER NOT NULL,
    best       INTEGER NOT NULL,
    PRIMARY KEY (attempt_id, seq)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS optimal (
    setting     TEXT PRIMARY KEY,
    value_json  TEXT NOT NULL,
    mean_score  REAL NOT NULL DEFAULT 0,
    support     INTEGER NOT NULL DEFAULT 0,
    updated_at  REAL NOT NULL,
    reason      TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS insights (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at  REAL NOT NULL,
    setting     TEXT NOT NULL,
    headline    TEXT NOT NULL,
    detail      TEXT NOT NULL DEFAULT '',
    best_value  TEXT NOT NULL DEFAULT '',
    support     INTEGER NOT NULL DEFAULT 0,
    lift        REAL NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_insights_setting ON insights(setting, created_at DESC);

-- Analytics snapshots. Each "analyze" run appends one row and replaces the
-- set of findings attached to it.
CREATE TABLE IF NOT EXISTS analyses (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    run_at        REAL NOT NULL,
    attempt_count INTEGER NOT NULL,
    duration_ms   INTEGER NOT NULL,
    finding_count INTEGER NOT NULL DEFAULT 0,
    note          TEXT
);

-- Findings are replaced on each analyze; the ``id`` is the stable finding id
-- produced by analytics.Finding so streaks can be tracked across runs.
CREATE TABLE IF NOT EXISTS findings (
    id          TEXT PRIMARY KEY,
    analysis_id INTEGER NOT NULL,
    group_name  TEXT NOT NULL,
    title       TEXT NOT NULL,
    paragraph   TEXT NOT NULL,
    strength    REAL NOT NULL,
    support     INTEGER NOT NULL,
    visual_json TEXT NOT NULL,
    related_json TEXT NOT NULL DEFAULT '[]',
    rule_json   TEXT,
    discovered_at REAL NOT NULL,
    last_seen_at  REAL NOT NULL,
    rescan_streak INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY(analysis_id) REFERENCES analyses(id)
);

-- Lessons are distilled, persistent rules the tuner respects.
CREATE TABLE IF NOT EXISTS lessons (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    paragraph   TEXT NOT NULL,
    rule_json   TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'watching',
    streak      INTEGER NOT NULL DEFAULT 0,
    misses      INTEGER NOT NULL DEFAULT 0,
    strength    REAL NOT NULL DEFAULT 0,
    support     INTEGER NOT NULL DEFAULT 0,
    based_on    TEXT NOT NULL DEFAULT '',
    accepted_at REAL,
    retired_at  REAL,
    updated_at  REAL NOT NULL,
    dismissed   INTEGER NOT NULL DEFAULT 0
);
"""


class Db:
    def __init__(self, path):
        self.path = path
        folder = os.path.dirname(os.path.abspath(path))
        if folder and not os.path.isdir(folder):
            os.makedirs(folder, exist_ok=True)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA synchronous=NORMAL")
            self._conn.executescript(_DDL)
            self._add_missing_columns()
            self._conn.commit()
        self.set_meta("schema_version", str(SCHEMA_VERSION))
        # Any attempt still marked running belongs to a previous process that
        # was killed. Ones that never searched are pure clutter, so drop them;
        # the rest are kept and labelled honestly.
        with self._lock:
            self._conn.execute(
                "DELETE FROM placements WHERE attempt_id IN "
                "(SELECT id FROM attempts WHERE status='running' AND nodes = 0)")
            self._conn.execute(
                "DELETE FROM samples WHERE attempt_id IN "
                "(SELECT id FROM attempts WHERE status='running' AND nodes = 0)")
            self._conn.execute(
                "DELETE FROM attempts WHERE status='running' AND nodes = 0")
            self._conn.execute(
                "UPDATE attempts SET status='interrupted', finished_at=? "
                "WHERE status='running'", (time.time(),))
            self._conn.commit()

    def _add_missing_columns(self):
        """Bring an older file up to the current schema.

        Only additive changes are supported, which is all this application has
        ever needed.  ``matched_edges`` stays NULL on rows written before the
        engine reported it: a board's edge count cannot be recovered from its
        depth, and calling it zero would tell the learner that those settings
        produced the worst boards on record.
        """
        present = {row["name"] for row in
                   self._conn.execute("PRAGMA table_info(attempts)")}
        if "matched_edges" not in present:
            self._conn.execute("ALTER TABLE attempts ADD COLUMN matched_edges INTEGER")
        if "breaks" not in present:
            self._conn.execute("ALTER TABLE attempts ADD COLUMN breaks INTEGER")

    def close(self):
        with self._lock:
            self._conn.close()

    # ----------------------------------------------------------------- meta

    def set_meta(self, key, value):
        with self._lock:
            self._conn.execute(
                "INSERT INTO meta(key,value) VALUES(?,?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, value))
            self._conn.commit()

    def get_meta(self, key, default=None):
        with self._lock:
            row = self._conn.execute(
                "SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row["value"] if row else default

    # -------------------------------------------------------------- attempts

    def start_attempt(self, config, user_defined, source):
        now = time.time()
        with self._lock:
            cur = self._conn.execute(
                "INSERT INTO attempts(started_at,status,user_defined,source,config_json) "
                "VALUES(?,?,?,?,?)",
                (now, "running", 1 if user_defined else 0, source, json.dumps(config)))
            self._conn.commit()
            return cur.lastrowid

    def finish_attempt(self, attempt_id, *, status, solved, valid, best_depth,
                       matched_edges, nodes, duration_ms, nodes_per_sec,
                       restarts, score, order, samples, breaks=None):
        """Store the results of a finished attempt.

        ``matched_edges`` is the score of the best board, or None when the
        engine did not report one; see :meth:`_add_missing_columns`.
        ``breaks`` is how many of that board's edges were deliberately
        mismatched (edge slipping); None on attempts recorded before this was
        tracked, 0 on every attempt that never slips.
        """
        now = time.time()
        with self._lock:
            self._conn.execute(
                "UPDATE attempts SET finished_at=?, status=?, solved=?, valid=?, "
                "best_depth=?, matched_edges=?, breaks=?, nodes=?, duration_ms=?, "
                "nodes_per_sec=?, restarts=?, score=? WHERE id=?",
                (now, status, 1 if solved else 0, 1 if valid else 0, best_depth,
                 matched_edges, breaks, nodes, duration_ms, nodes_per_sec, restarts,
                 score, attempt_id))
            if order:
                self._conn.executemany(
                    "INSERT OR REPLACE INTO placements(attempt_id,seq,cell,piece,rot) "
                    "VALUES(?,?,?,?,?)",
                    [(attempt_id, i, p[0], p[1], p[2]) for i, p in enumerate(order)])
            if samples:
                self._conn.executemany(
                    "INSERT OR REPLACE INTO samples(attempt_id,seq,ms,nodes,best) "
                    "VALUES(?,?,?,?,?)",
                    [(attempt_id, i, s[0], s[1], s[2]) for i, s in enumerate(samples)])
            self._conn.commit()

    def delete_attempt(self, attempt_id):
        """Remove an attempt entirely; used for runs that produced nothing."""
        with self._lock:
            self._conn.execute("DELETE FROM placements WHERE attempt_id=?", (attempt_id,))
            self._conn.execute("DELETE FROM samples WHERE attempt_id=?", (attempt_id,))
            self._conn.execute("DELETE FROM attempts WHERE id=?", (attempt_id,))
            self._conn.commit()

    def abandon_attempt(self, attempt_id, status="interrupted"):
        """Mark a run as not-finished; drop it entirely if it never searched."""
        with self._lock:
            row = self._conn.execute(
                "SELECT nodes FROM attempts WHERE id=?", (attempt_id,)).fetchone()
            if row is not None and (row["nodes"] or 0) == 0:
                self._conn.execute("DELETE FROM placements WHERE attempt_id=?", (attempt_id,))
                self._conn.execute("DELETE FROM samples WHERE attempt_id=?", (attempt_id,))
                self._conn.execute("DELETE FROM attempts WHERE id=?", (attempt_id,))
                self._conn.commit()
                return
            self._conn.execute(
                "UPDATE attempts SET status=?, finished_at=? WHERE id=? AND status='running'",
                (status, time.time(), attempt_id))
            self._conn.commit()

    def attempt_summaries(self, limit=60, offset=0):
        with self._lock:
            rows = self._conn.execute(
                "SELECT id,started_at,finished_at,status,solved,valid,best_depth,"
                "matched_edges,breaks,nodes,duration_ms,nodes_per_sec,restarts,"
                "user_defined,source,score,config_json "
                "FROM attempts WHERE status != 'running' "
                "ORDER BY id DESC LIMIT ? OFFSET ?", (limit, offset)).fetchall()
        return [self._summary(r) for r in rows]

    def attempt_count(self):
        with self._lock:
            row = self._conn.execute(
                "SELECT COUNT(*) AS c FROM attempts WHERE status != 'running'").fetchone()
        return row["c"] if row else 0

    def attempt_detail(self, attempt_id):
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM attempts WHERE id=?", (attempt_id,)).fetchone()
            if row is None:
                return None
            placements = self._conn.execute(
                "SELECT cell,piece,rot FROM placements WHERE attempt_id=? ORDER BY seq",
                (attempt_id,)).fetchall()
            samples = self._conn.execute(
                "SELECT ms,nodes,best FROM samples WHERE attempt_id=? ORDER BY seq",
                (attempt_id,)).fetchall()
        out = self._summary(row)
        out["placements"] = [[p["cell"], p["piece"], p["rot"]] for p in placements]
        out["samples"] = [[s["ms"], s["nodes"], s["best"]] for s in samples]
        return out

    @staticmethod
    def _summary(r):
        try:
            config = json.loads(r["config_json"])
        except (TypeError, ValueError):
            config = {}
        return {
            "id": r["id"],
            "startedAt": r["started_at"],
            "finishedAt": r["finished_at"],
            "status": r["status"],
            "solved": bool(r["solved"]),
            "valid": bool(r["valid"]),
            "bestDepth": r["best_depth"],
            "matchedEdges": r["matched_edges"],
            "breaks": r["breaks"],
            "nodes": r["nodes"],
            "durationMs": r["duration_ms"],
            "nodesPerSec": r["nodes_per_sec"],
            "restarts": r["restarts"],
            "userDefined": bool(r["user_defined"]),
            "source": r["source"],
            "score": r["score"],
            "config": config,
        }

    def finished_attempts_for_learning(self, limit=4000):
        """Attempts usable as training data: finished, and actually ran.

        Includes the attempt id and the user-defined flag, which the Analyzer
        needs to link findings back to concrete attempts and to describe them.
        """
        with self._lock:
            rows = self._conn.execute(
                "SELECT id,best_depth,matched_edges,breaks,nodes,duration_ms,score,"
                "config_json,solved,user_defined,restarts,status "
                "FROM attempts "
                # 'aborted' runs were cut short by the user, so they say nothing
                # about how good their settings were. Only self-terminating runs
                # are fair training data.
                "WHERE status IN ('budget','solved','exhausted') AND nodes > 0 "
                "ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        out = []
        for r in rows:
            try:
                cfg = json.loads(r["config_json"])
            except (TypeError, ValueError):
                continue
            out.append({
                "id": r["id"],
                "bestDepth": r["best_depth"],
                "matchedEdges": r["matched_edges"],
                "breaks": r["breaks"],
                "nodes": r["nodes"],
                "durationMs": r["duration_ms"],
                "score": r["score"],
                "solved": bool(r["solved"]),
                "userDefined": bool(r["user_defined"]),
                "restarts": r["restarts"],
                "status": r["status"],
                "config": cfg,
            })
        return out

    def stats(self):
        with self._lock:
            row = self._conn.execute(
                "SELECT COUNT(*) AS attempts, "
                "COALESCE(AVG(best_depth),0) AS avg_depth, "
                "COALESCE(SUM(nodes),0) AS nodes, "
                "COALESCE(SUM(duration_ms),0) AS ms, "
                "COALESCE(SUM(solved),0) AS solved "
                "FROM attempts WHERE status != 'running' AND nodes > 0").fetchone()
            # The pieces record only counts attempts with zero broken edges:
            # edge slipping lets a board place every piece while some of them
            # don't actually match a neighbour, and crediting that as "more
            # pieces placed" would let a slipped board quietly claim a record
            # it did not earn honestly. `breaks = 0` also excludes attempts
            # recorded before slipping was tracked (NULL), since it is not
            # actually known whether those slipped.
            best_row = self._conn.execute(
                "SELECT id,best_depth FROM attempts WHERE status != 'running' "
                "AND nodes > 0 AND breaks = 0 "
                "ORDER BY best_depth DESC, id ASC LIMIT 1").fetchone()
            # The edges record has no such caveat: matched_edges already
            # counts breaks against the board (checksBefore[depth] - breaks),
            # so a slipped board cannot inflate it -- this is the measure
            # Eternity II results are actually quoted in. Attempts from
            # before edge scoring have a NULL matched_edges and are excluded
            # rather than treated as zero.
            best_edges_row = self._conn.execute(
                "SELECT id,matched_edges FROM attempts WHERE status != 'running' "
                "AND nodes > 0 AND matched_edges IS NOT NULL "
                "ORDER BY matched_edges DESC, id ASC LIMIT 1").fetchone()
        return {
            "attempts": row["attempts"] or 0,
            "bestDepth": best_row["best_depth"] if best_row else 0,
            "avgDepth": round(row["avg_depth"] or 0, 2),
            "totalNodes": row["nodes"] or 0,
            "totalMs": row["ms"] or 0,
            "solvedCount": row["solved"] or 0,
            "bestAttemptId": best_row["id"] if best_row else None,
            "bestMatchedEdges": best_edges_row["matched_edges"] if best_edges_row else 0,
            "bestEdgesAttemptId": best_edges_row["id"] if best_edges_row else None,
        }

    def clear_history(self):
        with self._lock:
            self._conn.executescript(
                "DELETE FROM placements; DELETE FROM samples; "
                "DELETE FROM attempts; DELETE FROM insights; DELETE FROM optimal; "
                "DELETE FROM findings; DELETE FROM analyses; DELETE FROM lessons;")
            self._conn.commit()

    # ------------------------------------------------------ analytics helpers

    def samples_for_attempt(self, attempt_id):
        with self._lock:
            rows = self._conn.execute(
                "SELECT ms,nodes,best FROM samples WHERE attempt_id=? ORDER BY seq",
                (attempt_id,)).fetchall()
        return [(int(r['ms']), int(r['nodes']), int(r['best'])) for r in rows]

    def placements_for_attempt(self, attempt_id):
        with self._lock:
            rows = self._conn.execute(
                "SELECT cell,piece,rot FROM placements WHERE attempt_id=? ORDER BY seq",
                (attempt_id,)).fetchall()
        return [(int(r['cell']), int(r['piece']), int(r['rot'])) for r in rows]

    def cell_fill_counts(self, attempt_ids):
        """For a set of attempts, how many of them placed a piece in each cell."""
        if not attempt_ids:
            return {}
        counts = {}
        # chunk to stay well under the SQLite parameter limit
        with self._lock:
            for i in range(0, len(attempt_ids), 500):
                chunk = attempt_ids[i:i + 500]
                qmarks = ",".join("?" * len(chunk))
                rows = self._conn.execute(
                    "SELECT cell, COUNT(DISTINCT attempt_id) AS n "
                    "FROM placements WHERE attempt_id IN (%s) GROUP BY cell" % qmarks,
                    chunk).fetchall()
                for r in rows:
                    counts[int(r['cell'])] = counts.get(int(r['cell']), 0) + int(r['n'])
        return counts

    # ---------------------------------------------------------- analyses

    def record_analysis(self, attempt_count, duration_ms, findings, note=None):
        """Store one analyze run and its findings.

        The finding ``id`` is stable across runs, so we preserve the
        ``discovered_at`` timestamp of whatever matches, while updating
        the streak counter and the latest numbers.
        """
        now = time.time()
        with self._lock:
            cur = self._conn.execute(
                "INSERT INTO analyses(run_at,attempt_count,duration_ms,finding_count,note)"
                " VALUES(?,?,?,?,?)",
                (now, int(attempt_count), int(duration_ms),
                 len(findings), note))
            analysis_id = cur.lastrowid

            previous = {r['id']: r for r in self._conn.execute(
                "SELECT * FROM findings").fetchall()}

            kept_ids = set()
            for f in findings:
                kept_ids.add(f['id'])
                prev = previous.get(f['id'])
                discovered = prev['discovered_at'] if prev else now
                streak = (prev['rescan_streak'] + 1) if prev else 1
                self._conn.execute(
                    "INSERT INTO findings(id,analysis_id,group_name,title,paragraph,"
                    "strength,support,visual_json,related_json,rule_json,"
                    "discovered_at,last_seen_at,rescan_streak) "
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) "
                    "ON CONFLICT(id) DO UPDATE SET "
                    "analysis_id=excluded.analysis_id, group_name=excluded.group_name, "
                    "title=excluded.title, paragraph=excluded.paragraph, "
                    "strength=excluded.strength, support=excluded.support, "
                    "visual_json=excluded.visual_json, related_json=excluded.related_json, "
                    "rule_json=excluded.rule_json, "
                    "last_seen_at=excluded.last_seen_at, "
                    "rescan_streak=excluded.rescan_streak",
                    (f['id'], analysis_id, f['group'], f['title'], f['paragraph'],
                     float(f['strength']), int(f['support']),
                     json.dumps(f.get('visual') or {}),
                     json.dumps(f.get('related') or []),
                     json.dumps(f['rule']) if f.get('rule') else None,
                     discovered, now, streak))

            # findings that did not appear this time are forgotten: "removed"
            # simply means they dropped below the relevance threshold
            stale = [fid for fid in previous if fid not in kept_ids]
            if stale:
                qmarks = ",".join("?" * len(stale))
                self._conn.execute(
                    "DELETE FROM findings WHERE id IN (%s)" % qmarks, stale)
            self._conn.commit()
            return analysis_id

    def latest_findings(self):
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM findings ORDER BY strength DESC").fetchall()
        return [self._finding_row(r) for r in rows]

    def latest_analysis(self):
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM analyses ORDER BY id DESC LIMIT 1").fetchone()
        if not row:
            return None
        return dict(row)

    @staticmethod
    def _finding_row(r):
        try:
            visual = json.loads(r['visual_json'])
        except (ValueError, TypeError):
            visual = {}
        try:
            related = json.loads(r['related_json'])
        except (ValueError, TypeError):
            related = []
        rule = None
        if r['rule_json']:
            try:
                rule = json.loads(r['rule_json'])
            except ValueError:
                rule = None
        return {
            'id': r['id'],
            'group': r['group_name'],
            'title': r['title'],
            'paragraph': r['paragraph'],
            'strength': r['strength'],
            'support': r['support'],
            'visual': visual,
            'related': related,
            'rule': rule,
            'discoveredAt': r['discovered_at'],
            'lastSeenAt': r['last_seen_at'],
            'rescanStreak': r['rescan_streak'],
        }

    # ------------------------------------------------------------ lessons

    def all_lessons(self):
        with self._lock:
            rows = self._conn.execute("SELECT * FROM lessons").fetchall()
        out = []
        for r in rows:
            try:
                rule = json.loads(r['rule_json'])
            except (ValueError, TypeError):
                rule = None
            out.append({
                'id': r['id'],
                'title': r['title'],
                'paragraph': r['paragraph'],
                'rule': rule,
                'status': r['status'],
                'streak': r['streak'],
                'misses': r['misses'],
                'strength': r['strength'],
                'support': r['support'],
                'based_on': r['based_on'],
                'accepted_at': r['accepted_at'],
                'retired_at': r['retired_at'],
                'updated_at': r['updated_at'],
                'dismissed': r['dismissed'],
            })
        return out

    def upsert_lesson(self, row):
        with self._lock:
            self._conn.execute(
                "INSERT INTO lessons(id,title,paragraph,rule_json,status,streak,"
                "misses,strength,support,based_on,accepted_at,retired_at,"
                "updated_at,dismissed) "
                "VALUES(:id,:title,:paragraph,:rule,:status,:streak,"
                ":misses,:strength,:support,:based_on,:accepted_at,:retired_at,"
                ":updated_at,:dismissed) "
                "ON CONFLICT(id) DO UPDATE SET "
                "title=excluded.title, paragraph=excluded.paragraph, "
                "rule_json=excluded.rule_json, status=excluded.status, "
                "streak=excluded.streak, misses=excluded.misses, "
                "strength=excluded.strength, support=excluded.support, "
                "based_on=excluded.based_on, accepted_at=excluded.accepted_at, "
                "retired_at=excluded.retired_at, updated_at=excluded.updated_at, "
                "dismissed=excluded.dismissed",
                {
                    'id': row['id'], 'title': row['title'],
                    'paragraph': row['paragraph'],
                    'rule': json.dumps(row['rule']) if row.get('rule') else '{}',
                    'status': row.get('status') or 'watching',
                    'streak': int(row.get('streak') or 0),
                    'misses': int(row.get('misses') or 0),
                    'strength': float(row.get('strength') or 0),
                    'support': int(row.get('support') or 0),
                    'based_on': row.get('based_on') or '',
                    'accepted_at': row.get('accepted_at'),
                    'retired_at': row.get('retired_at'),
                    'updated_at': row.get('updated_at') or time.time(),
                    'dismissed': int(row.get('dismissed') or 0),
                })
            self._conn.commit()

    def update_lesson_streak(self, lesson_id, **fields):
        """Update any subset of the mutable lesson columns by id."""
        allowed = {
            'streak', 'misses', 'status', 'strength', 'support',
            'accepted_at', 'retired_at', 'updated_at', 'dismissed',
        }
        sets = []
        params = []
        for key, value in fields.items():
            if key not in allowed:
                continue
            sets.append("%s=?" % key)
            params.append(value)
        if not sets:
            return
        params.append(lesson_id)
        with self._lock:
            self._conn.execute(
                "UPDATE lessons SET " + ",".join(sets) + " WHERE id=?", params)
            self._conn.commit()

    def delete_lesson(self, lesson_id):
        with self._lock:
            self._conn.execute("DELETE FROM lessons WHERE id=?", (lesson_id,))
            self._conn.commit()

    # ---------------------------------------------------------------- optimal

    def save_optimal(self, entries):
        """entries: list of (setting, value, mean_score, support, reason)."""
        now = time.time()
        with self._lock:
            for setting, value, mean_score, support, reason in entries:
                self._conn.execute(
                    "INSERT INTO optimal(setting,value_json,mean_score,support,updated_at,reason) "
                    "VALUES(?,?,?,?,?,?) ON CONFLICT(setting) DO UPDATE SET "
                    "value_json=excluded.value_json, mean_score=excluded.mean_score, "
                    "support=excluded.support, updated_at=excluded.updated_at, "
                    "reason=excluded.reason",
                    (setting, json.dumps(value), mean_score, support, now, reason))
            self._conn.commit()

    def load_optimal(self):
        with self._lock:
            rows = self._conn.execute("SELECT * FROM optimal").fetchall()
        out = {}
        for r in rows:
            try:
                out[r["setting"]] = {
                    "value": json.loads(r["value_json"]),
                    "meanScore": r["mean_score"],
                    "support": r["support"],
                    "reason": r["reason"],
                    "updatedAt": r["updated_at"],
                }
            except (TypeError, ValueError):
                continue
        return out

    # --------------------------------------------------------------- insights

    def replace_insights(self, items):
        """items: list of dicts with setting/headline/detail/best_value/support/lift."""
        now = time.time()
        with self._lock:
            self._conn.execute("DELETE FROM insights")
            self._conn.executemany(
                "INSERT INTO insights(created_at,setting,headline,detail,best_value,support,lift) "
                "VALUES(?,?,?,?,?,?,?)",
                [(now, i["setting"], i["headline"], i.get("detail", ""),
                  str(i.get("best_value", "")), int(i.get("support", 0)),
                  float(i.get("lift", 0.0))) for i in items])
            self._conn.commit()

    def load_insights(self):
        with self._lock:
            rows = self._conn.execute(
                "SELECT setting,headline,detail,best_value,support,lift,created_at "
                "FROM insights ORDER BY lift DESC, setting ASC").fetchall()
        return [{
            "setting": r["setting"],
            "headline": r["headline"],
            "detail": r["detail"],
            "bestValue": r["best_value"],
            "support": r["support"],
            "lift": r["lift"],
            "createdAt": r["created_at"],
        } for r in rows]
