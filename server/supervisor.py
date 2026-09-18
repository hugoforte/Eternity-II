"""Runs Eternity II solve attempts back to back and publishes what they do.

One attempt == one Java engine process (``app.Engine``) that streams JSONL.  The
supervisor owns the "live" view of the world:

  * it starts attempts, using either the learner's suggestion or a configuration
    the user pinned;
  * it parses the engine's event stream, keeps the newest board in memory and
    fans events out to every connected browser;
  * when an attempt finishes it stores everything in SQLite, asks the learner to
    update itself, and immediately starts the next attempt.

Replaying history never touches any of this: the browser fetches a finished
attempt's placement list over plain HTTP and scrubs through it locally, so the
live search keeps running undisturbed.
"""

import json
import os
import queue
import shutil
import subprocess
import sys
import threading
import time

import schema
import tuner as tuner_mod
import lessons as lessons_mod


class Broker:
    """Tiny pub/sub fan-out for Server-Sent Events."""

    def __init__(self, max_queue=64):
        self._subs = []
        self._lock = threading.Lock()
        self._max_queue = max_queue

    def subscribe(self):
        q = queue.Queue(maxsize=self._max_queue)
        with self._lock:
            self._subs.append(q)
        return q

    def unsubscribe(self, q):
        with self._lock:
            if q in self._subs:
                self._subs.remove(q)

    def publish(self, event, payload):
        message = (event, payload)
        with self._lock:
            subs = list(self._subs)
        for q in subs:
            try:
                q.put_nowait(message)
            except queue.Full:
                # Slow client: drop the oldest frame rather than stalling the
                # solver or growing without bound.
                try:
                    q.get_nowait()
                    q.put_nowait(message)
                except (queue.Empty, queue.Full):
                    pass

    def count(self):
        with self._lock:
            return len(self._subs)


def find_java():
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = os.path.join(java_home, "bin", "java")
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
        if os.path.isfile(candidate + ".exe"):
            return candidate + ".exe"
    found = shutil.which("java")
    if found:
        return found
    return None


class Supervisor:
    def __init__(self, db, classes_dir, broker=None, java_bin=None):
        self.db = db
        self.classes_dir = classes_dir
        self.broker = broker or Broker()
        self.tuner = tuner_mod.Tuner(db)
        self.lessons = lessons_mod.LessonManager(db)
        # the tuner biases its UCB by active lessons; see tuner.py
        self.tuner.lessons = self.lessons
        self.java_bin = java_bin or find_java()

        self._lock = threading.RLock()
        self._proc = None
        self._thread = None
        self._stop_all = False
        self._paused = False
        self._skip_requested = False

        # pinned user configuration, or None to follow the learner
        self._pinned_config = None
        self._pinned_is_user = False

        # live state
        self.live = {
            "attemptId": None,
            "running": False,
            "paused": False,
            "config": None,
            "userDefined": False,
            "source": "tuner",
            "board": None,
            "placed": 0,
            "best": 0,
            "nodes": 0,
            "nps": 0,
            "ms": 0,
            "restarts": 0,
            "cells": 256,
            "n": 16,
            "startedAt": None,
        }
        self.meta = None
        cached = db.get_meta("board_meta")
        if cached:
            try:
                self.meta = json.loads(cached)
            except ValueError:
                self.meta = None

    # ------------------------------------------------------------- lifecycle

    def start(self):
        if self.java_bin is None:
            raise RuntimeError(
                "Could not find a 'java' executable. Install a JDK (17 or newer) "
                "or set JAVA_HOME.")
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._stop_all = False
            self._thread = threading.Thread(target=self._loop, name="supervisor",
                                            daemon=True)
            self._thread.start()

    def shutdown(self):
        self._stop_all = True
        self._kill_engine()
        thread = self._thread
        if thread is not None:
            thread.join(timeout=10)

    def _kill_engine(self):
        with self._lock:
            proc = self._proc
        if proc is None:
            return
        try:
            if proc.stdin and not proc.stdin.closed:
                proc.stdin.write("stop\n")
                proc.stdin.flush()
        except (OSError, ValueError):
            pass
        try:
            proc.wait(timeout=6)
        except subprocess.TimeoutExpired:
            try:
                proc.kill()
            except OSError:
                pass

    # --------------------------------------------------------------- controls

    def set_config(self, config, user_defined=True):
        """Pin a configuration and restart the current attempt with it."""
        cfg = schema.coerce_config(config)
        with self._lock:
            self._pinned_config = cfg
            self._pinned_is_user = bool(user_defined)
            self._skip_requested = True
            self._paused = False
        self._kill_engine()
        return cfg

    def use_optimal(self):
        """Drop any pinned config and go back to following the learner."""
        with self._lock:
            self._pinned_config = None
            self._pinned_is_user = False
            self._skip_requested = True
            self._paused = False
        self._kill_engine()
        return self.tuner.optimal_config()

    def pause(self):
        with self._lock:
            self._paused = True
        self._kill_engine()

    def resume(self):
        with self._lock:
            self._paused = False
        self.start()

    def skip(self):
        """End the current attempt now; it is still recorded."""
        with self._lock:
            self._skip_requested = True
        self._kill_engine()

    def status(self):
        with self._lock:
            live = dict(self.live)
            live["paused"] = self._paused
            live["pinned"] = self._pinned_config is not None
            live["listeners"] = self.broker.count()
        return live

    # ------------------------------------------------------------- main loop

    def _loop(self):
        while not self._stop_all:
            if self._paused:
                with self._lock:
                    self.live["running"] = False
                    self.live["paused"] = True
                self.broker.publish("state", self.status())
                time.sleep(0.25)
                continue
            try:
                self._run_one_attempt()
            except Exception as exc:           # never let the loop die
                sys.stderr.write("supervisor error: %r\n" % (exc,))
                sys.stderr.flush()
                time.sleep(1.0)

    def _next_config(self):
        with self._lock:
            pinned = self._pinned_config
            is_user = self._pinned_is_user
        if pinned is not None:
            cfg = dict(pinned)
            # a pinned config keeps its seed so the user can reproduce a run
            return cfg, is_user, "user"
        cfg = self.tuner.suggest()
        optimal = self.tuner.optimal_config()
        # Suggestions explore around the optimum; flag it honestly when the
        # learner chose to deviate, but never call it user-defined.
        source = "tuner" if schema.same_config(cfg, optimal) else "explore"
        return cfg, False, source

    def _run_one_attempt(self):
        config, user_defined, source = self._next_config()
        attempt_id = self.db.start_attempt(config, user_defined, source)

        with self._lock:
            self._skip_requested = False
            self.live.update({
                "attemptId": attempt_id,
                "running": True,
                "paused": False,
                "config": config,
                "userDefined": user_defined,
                "source": source,
                "board": None,
                "placed": 0,
                "best": 0,
                "nodes": 0,
                "nps": 0,
                "ms": 0,
                "restarts": 0,
                "startedAt": time.time(),
            })
        self.broker.publish("attempt_started", {
            "attemptId": attempt_id,
            "config": config,
            "userDefined": user_defined,
            "source": source,
        })

        cmd = [self.java_bin, "-cp", self.classes_dir, "app.Engine",
               "--watchStdin=1", "--frameMs=110"]
        cmd.extend(schema.to_engine_args(config))

        end_record = None
        try:
            proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stdin=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True, bufsize=1)
        except OSError as exc:
            self.db.abandon_attempt(attempt_id, "error")
            self.broker.publish("error", {"message": "could not start engine: %s" % exc})
            time.sleep(2.0)
            return

        with self._lock:
            self._proc = proc

        try:
            for line in proc.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                kind = event.get("type")
                if kind == "meta":
                    self._handle_meta(event)
                elif kind == "frame" or kind == "best":
                    self._handle_progress(event, kind)
                elif kind == "restart":
                    with self._lock:
                        self.live["restarts"] = event.get("index", 0)
                elif kind == "end":
                    end_record = event
        finally:
            stderr_text = ""
            try:
                if proc.stderr is not None:
                    stderr_text = proc.stderr.read() or ""
            except (OSError, ValueError):
                pass
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
            with self._lock:
                self._proc = None
            if stderr_text.strip():
                sys.stderr.write("engine stderr: %s\n" % stderr_text.strip()[:2000])
                sys.stderr.flush()

        if end_record is None:
            self.db.abandon_attempt(attempt_id, "interrupted")
            self.broker.publish("attempt_aborted", {"attemptId": attempt_id})
            return

        self._finish_attempt(attempt_id, config, user_defined, end_record)

    def _handle_meta(self, event):
        meta = {
            "n": event.get("n", 16),
            "cells": event.get("cells", 256),
            "variants": event.get("variants", 1024),
            "colours": event.get("colours", 23),
            "pieces": event.get("pieces", []),
            "fixed": event.get("fixed", []),
        }
        first_time = self.meta is None
        self.meta = meta
        with self._lock:
            self.live["n"] = meta["n"]
            self.live["cells"] = meta["cells"]
        if first_time:
            self.db.set_meta("board_meta", json.dumps(meta))
            self.broker.publish("meta", meta)

    def _handle_progress(self, event, kind):
        with self._lock:
            board = event.get("board")
            if board is not None:
                self.live["board"] = board
            self.live["placed"] = event.get("placed", self.live["placed"])
            self.live["nodes"] = event.get("nodes", self.live["nodes"])
            self.live["ms"] = event.get("ms", self.live["ms"])
            if "nps" in event:
                self.live["nps"] = event["nps"]
            if kind == "best":
                self.live["best"] = max(self.live["best"], event.get("placed", 0))
            else:
                self.live["best"] = max(self.live["best"], event.get("best", 0))
            payload = {
                "attemptId": self.live["attemptId"],
                "kind": kind,
                "board": board,
                "placed": self.live["placed"],
                "best": self.live["best"],
                "nodes": self.live["nodes"],
                "nps": self.live["nps"],
                "ms": self.live["ms"],
                "restarts": self.live["restarts"],
            }
        self.broker.publish("live", payload)

    def _finish_attempt(self, attempt_id, config, user_defined, end):
        best_depth = int(end.get("best", 0))
        nodes = int(end.get("nodes", 0))
        solved = bool(end.get("solved", False))
        status = end.get("status", "completed")
        if status == "stopped":
            # The run was cut short by the user (settings change, skip, pause).
            # With no search done there is nothing worth keeping; otherwise the
            # board is still replayable but must not be used as training data,
            # because the settings never got their full budget.
            if nodes == 0:
                self.db.delete_attempt(attempt_id)
                with self._lock:
                    self.live["running"] = False
                return
            status = "aborted"
        score = tuner_mod.score_attempt(best_depth, nodes,
                                        config.get("nodeBudget"), solved)

        self.db.finish_attempt(
            attempt_id,
            status=status,
            solved=solved,
            valid=bool(end.get("valid", True)),
            best_depth=best_depth,
            nodes=nodes,
            duration_ms=int(end.get("ms", 0)),
            nodes_per_sec=int(end.get("nps", 0)),
            restarts=int(end.get("restarts", 0)),
            score=score,
            order=end.get("order") or [],
            samples=end.get("samples") or [],
        )

        try:
            self.tuner.after_attempt()
        except Exception as exc:
            sys.stderr.write("tuner error: %r\n" % (exc,))

        summary = self.db.attempt_detail(attempt_id)
        if summary is not None:
            summary.pop("placements", None)
            summary.pop("samples", None)
        self.broker.publish("attempt_finished", {
            "attempt": summary,
            "stats": self.db.stats(),
            "optimal": self.tuner.optimal_details(),
        })

        with self._lock:
            self.live["running"] = False
