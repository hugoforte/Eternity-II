"""Test suite for the Eternity II Lab server.

Run with:  python3 server/test_server.py

Standard library only (unittest), no pip install. Covers the settings schema,
the SQLite layer, and - most importantly - the learner's arithmetic, including
the confound removal that stops a setting looking good merely because it was
paired with long attempts.

The last test actually launches the Java engine, so it is skipped when no
compiled classes or JVM are available.
"""

import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import schema                      # noqa: E402
import tuner as tuner_mod          # noqa: E402
from db import Db                  # noqa: E402


# --------------------------------------------------------------------- schema

class SchemaTest(unittest.TestCase):

    def test_defaults_cover_every_setting(self):
        defaults = schema.defaults()
        self.assertEqual(set(defaults), {s["key"] for s in schema.SETTINGS})
        for setting in schema.SETTINGS:
            self.assertIn("label", setting)
            self.assertIn("group", setting)
            self.assertIn("blurb", setting)
            self.assertIn(setting["group"], schema.GROUP_ORDER)

    def test_defaults_are_the_measured_best_behaviour(self):
        # These are the values the benchmarking work in the Java suite settled
        # on; if someone changes them the UI's "optimal" cold start changes too.
        d = schema.defaults()
        self.assertEqual(d["cellOrder"], "mrv")
        self.assertEqual(d["tieBreak"], "mostNeighbours")
        self.assertEqual(d["valueOrder"], "natural")
        self.assertEqual(d["forwardCheck"], "fullBoard")
        self.assertTrue(d["greyInteriorPruning"])
        self.assertEqual(d["restartPolicy"], "none")
        self.assertEqual(d["shuffleStrength"], 0)

    def test_every_default_is_a_legal_arm(self):
        for setting in schema.SETTINGS:
            if not setting.get("tunable", True):
                continue
            self.assertIn(setting["default"], schema.arms(setting),
                          "default of %s is not one of its arms" % setting["key"])

    def test_arms_are_bounded(self):
        for setting in schema.tunable_settings():
            arms = schema.arms(setting)
            self.assertGreaterEqual(len(arms), 2, setting["key"])
            self.assertLessEqual(len(arms), 20, setting["key"])

    def test_coerce_clamps_and_snaps(self):
        self.assertEqual(schema.coerce("shuffleStrength", 10 ** 9), 100)
        self.assertEqual(schema.coerce("shuffleStrength", -50), 0)
        self.assertEqual(schema.coerce("cellOrder", "nonsense"), "mrv")
        self.assertEqual(schema.coerce("greyInteriorPruning", "false"), False)
        self.assertEqual(schema.coerce("greyInteriorPruning", "1"), True)
        # scale values snap to a declared step
        self.assertIn(schema.coerce("nodeBudget", 3_000_000),
                      schema.BY_KEY["nodeBudget"]["values"])
        self.assertEqual(schema.coerce("candidateCap", 5),
                         min(schema.BY_KEY["candidateCap"]["values"],
                             key=lambda v: abs(v - 5)))

    def test_coerce_survives_rubbish(self):
        self.assertEqual(schema.coerce("nodeBudget", "not-a-number"),
                         schema.BY_KEY["nodeBudget"]["default"])
        self.assertIsNone(schema.coerce("noSuchSetting", 1))

    def test_coerce_config_fills_gaps_and_drops_unknowns(self):
        cfg = schema.coerce_config({"cellOrder": "rowMajor", "bogus": 5})
        self.assertEqual(cfg["cellOrder"], "rowMajor")
        self.assertNotIn("bogus", cfg)
        self.assertEqual(set(cfg), set(schema.defaults()))

    def test_engine_args_format(self):
        args = schema.to_engine_args(schema.defaults())
        self.assertEqual(len(args), len(schema.SETTINGS))
        for arg in args:
            self.assertTrue(arg.startswith("--"))
            self.assertIn("=", arg)
        self.assertIn("--greyInteriorPruning=true", args)

    def test_a_setting_with_no_dependency_is_always_active(self):
        cfg = schema.defaults()
        self.assertTrue(schema.is_active("cellOrder", cfg))
        self.assertTrue(schema.is_active("nodeBudget", cfg))

    def test_restart_growth_only_counts_under_the_geometric_policy(self):
        # MrvSolver.restartBudget() reads restartMultiplier on the geometric
        # branch and nowhere else.
        for policy, active in (("geometric", True), ("fixed", False),
                               ("luby", False), ("none", False)):
            cfg = dict(schema.defaults(), restartPolicy=policy)
            self.assertEqual(schema.is_active("restartMultiplier", cfg), active,
                             "restartMultiplier under restartPolicy=%s" % policy)

    def test_restart_interval_counts_under_every_policy_but_none(self):
        for policy, active in (("geometric", True), ("fixed", True),
                               ("luby", True), ("none", False)):
            cfg = dict(schema.defaults(), restartPolicy=policy)
            self.assertEqual(schema.is_active("restartBase", cfg), active,
                             "restartBase under restartPolicy=%s" % policy)

    def test_hybrid_switch_point_only_counts_for_the_hybrid_cell_order(self):
        for order, active in (("hybrid", True), ("mrv", False), ("rowMajor", False)):
            cfg = dict(schema.defaults(), cellOrder=order)
            self.assertEqual(schema.is_active("hybridThreshold", cfg), active,
                             "hybridThreshold under cellOrder=%s" % order)

    def test_tie_breaker_does_not_count_for_a_row_by_row_sweep(self):
        # A row-major sweep takes the first empty cell, so nothing ever ties.
        for order, active in (("mrv", True), ("hybrid", True), ("rowMajor", False)):
            cfg = dict(schema.defaults(), cellOrder=order)
            self.assertEqual(schema.is_active("tieBreak", cfg), active,
                             "tieBreak under cellOrder=%s" % order)

    def test_a_missing_dependency_value_falls_back_to_its_default(self):
        self.assertFalse(schema.is_active("restartMultiplier", {}))
        self.assertTrue(schema.is_active("cellOrder", {}))

    def test_unknown_settings_cannot_be_asked_about(self):
        with self.assertRaises(KeyError):
            schema.is_active("noSuchSetting", schema.defaults())

    def test_every_dependency_names_a_real_setting_and_legal_values(self):
        for setting in schema.SETTINGS:
            dependency = setting.get("activeWhen")
            if dependency is None:
                continue
            other = schema.BY_KEY.get(dependency["key"])
            self.assertIsNotNone(other, "%s depends on an unknown setting"
                                 % setting["key"])
            for value in dependency["values"]:
                self.assertIn(value, schema.arms(other),
                              "%s depends on %s=%r, which is not one of its arms"
                              % (setting["key"], other["key"], value))

    def test_same_config_ignores_the_seed(self):
        a = schema.defaults()
        b = schema.defaults()
        b["randomSeed"] = a["randomSeed"] + 1
        self.assertTrue(schema.same_config(a, b))
        b["cellOrder"] = "rowMajor"
        self.assertFalse(schema.same_config(a, b))


# ------------------------------------------------------------------------- db

class DbTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.db = Db(os.path.join(self.dir, "t.sqlite"))

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.dir, ignore_errors=True)

    def _finish(self, attempt_id, **kw):
        payload = dict(status="budget", solved=False, valid=True, best_depth=100,
                       matched_edges=180, nodes=1000, duration_ms=10,
                       nodes_per_sec=100, restarts=0, score=180.0,
                       order=[], samples=[])
        payload.update(kw)
        self.db.finish_attempt(attempt_id, **payload)

    def test_round_trip_with_placements_and_samples(self):
        cfg = schema.defaults()
        aid = self.db.start_attempt(cfg, user_defined=True, source="user")
        order = [[135, 138, 1], [0, 0, 0], [15, 3, 1]]
        samples = [[0, 0, 0], [10, 500, 2], [20, 1000, 3]]
        self._finish(aid, best_depth=3, order=order, samples=samples)

        detail = self.db.attempt_detail(aid)
        self.assertEqual(detail["placements"], order)
        self.assertEqual(detail["samples"], samples)
        self.assertTrue(detail["userDefined"])
        self.assertEqual(detail["config"], cfg)
        self.assertEqual(detail["bestDepth"], 3)

    def test_the_matched_edge_count_survives_a_round_trip(self):
        aid = self.db.start_attempt(schema.defaults(), user_defined=False,
                                    source="tuner")
        self._finish(aid, matched_edges=340)
        self.assertEqual(self.db.attempt_detail(aid)["matchedEdges"], 340)
        self.assertEqual(self.db.attempt_summaries()[0]["matchedEdges"], 340)

    def test_an_attempt_with_no_edge_count_reports_none_not_zero(self):
        aid = self.db.start_attempt(schema.defaults(), user_defined=False,
                                    source="tuner")
        self._finish(aid, matched_edges=None)
        self.assertIsNone(self.db.attempt_detail(aid)["matchedEdges"])
        self.assertIsNone(
            self.db.finished_attempts_for_learning()[0]["matchedEdges"])

    def test_a_database_without_the_edge_column_is_upgraded_in_place(self):
        path = os.path.join(self.dir, "t.sqlite")
        aid = self.db.start_attempt(schema.defaults(), user_defined=False,
                                    source="tuner")
        self._finish(aid, best_depth=191)
        self.db.close()

        conn = sqlite3.connect(path)
        conn.execute("ALTER TABLE attempts DROP COLUMN matched_edges")
        conn.commit()
        conn.close()

        self.db = Db(path)
        detail = self.db.attempt_detail(aid)
        self.assertEqual(detail["bestDepth"], 191)
        self.assertIsNone(detail["matchedEdges"],
                          "an attempt from before edge scoring has no count")

    def test_running_attempts_are_hidden_until_finished(self):
        self.db.start_attempt(schema.defaults(), False, "tuner")
        self.assertEqual(self.db.attempt_count(), 0)
        self.assertEqual(self.db.attempt_summaries(), [])

    def test_delete_attempt_removes_children(self):
        aid = self.db.start_attempt(schema.defaults(), False, "tuner")
        self._finish(aid, order=[[1, 2, 3]], samples=[[1, 2, 3]])
        self.db.delete_attempt(aid)
        self.assertIsNone(self.db.attempt_detail(aid))
        self.assertEqual(self.db.attempt_count(), 0)

    def test_stats_ignore_runs_that_never_searched(self):
        good = self.db.start_attempt(schema.defaults(), False, "tuner")
        self._finish(good, best_depth=180, nodes=5000)
        empty = self.db.start_attempt(schema.defaults(), False, "tuner")
        self._finish(empty, best_depth=0, nodes=0, status="aborted")
        stats = self.db.stats()
        self.assertEqual(stats["attempts"], 1)
        self.assertEqual(stats["bestDepth"], 180)
        self.assertEqual(stats["avgDepth"], 180)

    def test_learning_data_excludes_aborted_and_empty_runs(self):
        keep = self.db.start_attempt(schema.defaults(), False, "tuner")
        self._finish(keep, status="budget", nodes=100, best_depth=150)
        aborted = self.db.start_attempt(schema.defaults(), False, "tuner")
        self._finish(aborted, status="aborted", nodes=100, best_depth=10)
        empty = self.db.start_attempt(schema.defaults(), False, "tuner")
        self._finish(empty, status="budget", nodes=0, best_depth=0)

        rows = self.db.finished_attempts_for_learning()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["bestDepth"], 150)

    def test_empty_running_attempts_are_dropped_on_reopen(self):
        empty = self.db.start_attempt(schema.defaults(), False, "tuner")
        busy = self.db.start_attempt(schema.defaults(), False, "tuner")
        # pretend the second one had searched before the process died
        with self.db._lock:
            self.db._conn.execute("UPDATE attempts SET nodes=500 WHERE id=?", (busy,))
            self.db._conn.commit()
        path = self.db.path
        self.db.close()
        reopened = Db(path)
        try:
            self.assertIsNone(reopened.attempt_detail(empty),
                              "a run that never searched should be discarded")
            kept = reopened.attempt_detail(busy)
            self.assertIsNotNone(kept)
            self.assertEqual(kept["status"], "interrupted")
        finally:
            reopened.close()

    def test_abandon_drops_attempts_that_never_searched(self):
        aid = self.db.start_attempt(schema.defaults(), False, "tuner")
        self.db.abandon_attempt(aid)
        self.assertIsNone(self.db.attempt_detail(aid))

    def test_abandon_keeps_attempts_that_did_search(self):
        aid = self.db.start_attempt(schema.defaults(), False, "tuner")
        with self.db._lock:
            self.db._conn.execute("UPDATE attempts SET nodes=99 WHERE id=?", (aid,))
            self.db._conn.commit()
        self.db.abandon_attempt(aid)
        detail = self.db.attempt_detail(aid)
        self.assertIsNotNone(detail)
        self.assertEqual(detail["status"], "interrupted")

    def test_optimal_and_insight_round_trip(self):
        self.db.save_optimal([("cellOrder", "mrv", 12.5, 9, "because")])
        loaded = self.db.load_optimal()
        self.assertEqual(loaded["cellOrder"]["value"], "mrv")
        self.assertEqual(loaded["cellOrder"]["support"], 9)

        self.db.replace_insights([
            {"setting": "cellOrder", "headline": "h", "detail": "d",
             "best_value": "mrv", "support": 10, "lift": 3.5}])
        items = self.db.load_insights()
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]["headline"], "h")
        # replace_insights replaces rather than appends
        self.db.replace_insights([])
        self.assertEqual(self.db.load_insights(), [])

    def test_clear_history_wipes_everything(self):
        aid = self.db.start_attempt(schema.defaults(), False, "tuner")
        self._finish(aid, order=[[1, 2, 3]])
        self.db.save_optimal([("cellOrder", "mrv", 1.0, 5, "x")])
        self.db.clear_history()
        self.assertEqual(self.db.attempt_count(), 0)
        self.assertEqual(self.db.load_optimal(), {})
        self.assertEqual(self.db.load_insights(), [])

    def test_pagination(self):
        for i in range(25):
            aid = self.db.start_attempt(schema.defaults(), False, "tuner")
            self._finish(aid, best_depth=i)
        first = self.db.attempt_summaries(limit=10, offset=0)
        second = self.db.attempt_summaries(limit=10, offset=10)
        self.assertEqual(len(first), 10)
        self.assertEqual(len(second), 10)
        self.assertGreater(first[0]["id"], second[0]["id"])
        self.assertEqual(self.db.attempt_count(), 25)


# ---------------------------------------------------------------------- tuner

class ScoreTest(unittest.TestCase):

    def test_matched_edges_dominate_efficiency(self):
        joined_slow = tuner_mod.score_attempt(400, 1_000_000, 1_000_000, False)
        loose_fast = tuner_mod.score_attempt(399, 1, 1_000_000, False)
        self.assertGreater(joined_slow, loose_fast,
                           "one extra edge must outrank any efficiency gain")

    def test_cheaper_run_wins_at_equal_edges(self):
        cheap = tuner_mod.score_attempt(340, 100_000, 1_000_000, False)
        dear = tuner_mod.score_attempt(340, 1_000_000, 1_000_000, False)
        self.assertGreater(cheap, dear)

    def test_solving_beats_everything(self):
        self.assertGreater(tuner_mod.score_attempt(0, 1, 1, True),
                           tuner_mod.score_attempt(480, 1, 10 ** 9, False))

    def test_an_attempt_with_no_edge_count_cannot_be_scored(self):
        # Treating a missing count as zero would tell the bandit that whatever
        # settings that attempt used produced the worst board on record.
        with self.assertRaises(ValueError):
            tuner_mod.score_attempt(None, 1000, 1_000_000, False)


class TunerTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.db = Db(os.path.join(self.dir, "t.sqlite"))
        self.tuner = tuner_mod.Tuner(self.db)

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.dir, ignore_errors=True)

    def _attempt(self, overrides, edges, nodes=None, budget=1_000_000,
                 depth=128):
        cfg = schema.defaults()
        cfg["nodeBudget"] = budget
        cfg.update(overrides)
        if nodes is None:
            nodes = budget
        aid = self.db.start_attempt(cfg, False, "tuner")
        score = tuner_mod.score_attempt(edges, nodes, cfg["nodeBudget"], False)
        self.db.finish_attempt(aid, status="budget", solved=False, valid=True,
                               best_depth=depth, matched_edges=edges, nodes=nodes,
                               duration_ms=100, nodes_per_sec=1000, restarts=0,
                               score=score, order=[], samples=[])

    def _attempt_without_edge_count(self, overrides, depth):
        """An attempt as the database held them before edge scoring."""
        cfg = schema.defaults()
        cfg["nodeBudget"] = 1_000_000
        cfg.update(overrides)
        aid = self.db.start_attempt(cfg, False, "tuner")
        self.db.finish_attempt(aid, status="budget", solved=False, valid=True,
                               best_depth=depth, matched_edges=None,
                               nodes=1_000_000, duration_ms=100,
                               nodes_per_sec=1000, restarts=0, score=0.0,
                               order=[], samples=[])

    def test_cold_start_uses_measured_defaults(self):
        cfg = self.tuner.compute_optimal()
        self.assertEqual(cfg["cellOrder"], "mrv")
        details = self.tuner.optimal_details()
        self.assertEqual(details["cellOrder"]["support"], 0)
        self.assertIn("default", details["cellOrder"]["reason"])

    def test_learns_the_better_value(self):
        for _ in range(4):
            self._attempt({"tieBreak": "mostNeighbours"}, 200)
            self._attempt({"tieBreak": "lowestIndex"}, 120)
        cfg = self.tuner.compute_optimal()
        self.assertEqual(cfg["tieBreak"], "mostNeighbours")
        details = self.tuner.optimal_details()
        self.assertGreaterEqual(details["tieBreak"]["support"], 4)

    def test_ignores_values_without_enough_support(self):
        # one lucky attempt must not be promoted to "optimal"
        self._attempt({"tieBreak": "nearestCentre"}, 256)
        for _ in range(4):
            self._attempt({"tieBreak": "mostNeighbours"}, 150)
        cfg = self.tuner.compute_optimal()
        self.assertEqual(cfg["tieBreak"], "mostNeighbours")

    def test_attempt_length_confound_is_removed(self):
        """A value paired only with long attempts must not look better.

        This is the whole reason the learner centres each score against other
        runs of the same attempt length.
        """
        for _ in range(4):
            # 'reverse' always ran with a huge budget and so reached深 depths
            self._attempt({"valueOrder": "reverse"}, 210, budget=100_000_000)
            # 'natural' always ran with a tiny budget
            self._attempt({"valueOrder": "natural"}, 120, budget=100_000)

        stats = self.tuner.arm_stats()["valueOrder"]
        raw_gap = stats["reverse"]["mean"] - stats["natural"]["mean"]
        adj_gap = stats["reverse"]["adj"] - stats["natural"]["adj"]
        self.assertGreater(raw_gap, 50, "setup should show a big raw difference")
        self.assertLess(abs(adj_gap), 1.0,
                        "adjusted scores must cancel the budget confound "
                        "(raw gap %.1f, adjusted %.1f)" % (raw_gap, adj_gap))

        # ...and no bogus lesson should be published about it
        self.tuner.rebuild_insights()
        lessons = [i for i in self.db.load_insights() if i["setting"] == "valueOrder"]
        self.assertEqual(lessons, [])

    def test_real_effect_at_equal_length_is_detected(self):
        for _ in range(5):
            self._attempt({"forwardCheck": "fullBoard"}, 200, budget=1_000_000)
            self._attempt({"forwardCheck": "none"}, 150, budget=1_000_000)
        stats = self.tuner.arm_stats()["forwardCheck"]
        gap = stats["fullBoard"]["adj"] - stats["none"]["adj"]
        self.assertAlmostEqual(gap, 50.0, delta=2.0)

        self.tuner.rebuild_insights()
        lessons = [i for i in self.db.load_insights() if i["setting"] == "forwardCheck"]
        self.assertEqual(len(lessons), 1)
        self.assertIn("Whole board", lessons[0]["headline"])
        self.assertGreaterEqual(lessons[0]["support"], 10)

    def test_insights_need_firm_evidence(self):
        # two values, only two attempts each: below INSIGHT_MIN_PER_ARM
        for _ in range(2):
            self._attempt({"tieBreak": "mostNeighbours"}, 200)
            self._attempt({"tieBreak": "lowestIndex"}, 100)
        self.tuner.rebuild_insights()
        self.assertEqual(self.db.load_insights(), [])

    def test_suggest_returns_a_complete_valid_config(self):
        cfg = self.tuner.suggest()
        self.assertEqual(set(cfg), set(schema.defaults()))
        for setting in schema.SETTINGS:
            if setting.get("tunable", True):
                self.assertIn(cfg[setting["key"]], schema.arms(setting),
                              "suggested %s=%r is not a legal arm"
                              % (setting["key"], cfg[setting["key"]]))

    def test_suggest_explores_unseen_values(self):
        """With no history the learner should not keep proposing one config."""
        seen = set()
        for _ in range(40):
            seen.add(self.tuner.suggest()["tieBreak"])
        self.assertGreater(len(seen), 1)

    def test_suggest_varies_the_seed(self):
        seeds = {self.tuner.suggest()["randomSeed"] for _ in range(12)}
        self.assertGreater(len(seeds), 1)

    def test_a_setting_that_could_not_act_collects_no_statistics(self):
        """restartMultiplier is dead unless the policy is geometric."""
        for multiplier in (110, 400):
            for _ in range(4):
                self._attempt({"restartPolicy": "none",
                               "restartMultiplier": multiplier}, 200)
        self.assertEqual(self.tuner.arm_stats()["restartMultiplier"], {})

    def test_support_counts_only_the_attempts_a_setting_could_affect(self):
        for _ in range(4):
            self._attempt({"restartPolicy": "geometric",
                           "restartMultiplier": 110}, 120)
            self._attempt({"restartPolicy": "geometric",
                           "restartMultiplier": 400}, 200)
        # plenty of deep runs that restartMultiplier had no say in
        for _ in range(20):
            self._attempt({"restartPolicy": "none",
                           "restartMultiplier": 400}, 250)

        stats = self.tuner.arm_stats()["restartMultiplier"]
        self.assertEqual(stats["400"]["n"], 4)
        self.assertEqual(stats["110"]["n"], 4)

        self.tuner.rebuild_insights()
        insights = [i for i in self.db.load_insights()
                    if i["setting"] == "restartMultiplier"]
        self.assertEqual(len(insights), 1)
        self.assertEqual(insights[0]["support"], 8)

    def test_attempts_with_no_edge_count_are_left_out_of_the_statistics(self):
        """Rows from before edge scoring are not comparable, so they sit out."""
        for _ in range(4):
            self._attempt({"tieBreak": "lowestIndex"}, 300)
        for _ in range(4):
            self._attempt_without_edge_count({"tieBreak": "nearestCentre"}, 210)

        stats = self.tuner.arm_stats()["tieBreak"]
        self.assertEqual(stats["lowestIndex"]["n"], 4)
        self.assertNotIn("nearestCentre", stats)

    def test_breakdown_only_reports_values_actually_tried(self):
        for _ in range(3):
            self._attempt({"cellOrder": "mrv"}, 200)
        rows = {b["setting"]: b for b in self.tuner.setting_breakdown()}
        self.assertIn("cellOrder", rows)
        tried = [r for r in rows["cellOrder"]["rows"] if r["attempts"] > 0]
        self.assertEqual(len(tried), 1)
        self.assertEqual(tried[0]["value"], "mrv")

    def test_after_attempt_refreshes_stored_model(self):
        for _ in range(4):
            self._attempt({"cellOrder": "mrv"}, 210)
            self._attempt({"cellOrder": "rowMajor"}, 150)
        self.tuner.after_attempt()
        self.assertEqual(self.tuner.optimal_config()["cellOrder"], "mrv")
        self.assertTrue(self.db.load_insights())


# ------------------------------------------------------------------ http api

class HttpApiTest(unittest.TestCase):
    """Drives the real HTTP server over a socket, on a reused connection.

    The reused connection matters: a POST whose body is not drained leaves those
    bytes in the socket, and the next request on the same connection then fails
    with a confusing 501. That regression is what `test_keep_alive...` guards.
    """

    @classmethod
    def setUpClass(cls):
        import http.client
        import threading
        import app as app_mod

        cls.http_client = http.client
        cls.dir = tempfile.mkdtemp()
        cls.db = Db(os.path.join(cls.dir, "api.sqlite"))

        # a supervisor that never launches an engine keeps the test hermetic
        class StubSupervisor:
            def __init__(self, db):
                self.db = db
                self.tuner = tuner_mod.Tuner(db)
                self.meta = {"n": 16, "cells": 256, "pieces": [[0, 0, 1, 2]] * 256,
                             "fixed": [[135, 138, 1]], "variants": 1024, "colours": 23}
                self.calls = []
                from supervisor import Broker
                import lessons as lessons_mod
                self.broker = Broker()
                self.lessons = lessons_mod.LessonManager(db)
                self.tuner.lessons = self.lessons

            def status(self):
                return {"attemptId": 1, "running": True, "paused": False,
                        "config": schema.defaults(), "userDefined": False,
                        "source": "tuner", "board": None, "placed": 0, "best": 0,
                        "nodes": 0, "nps": 0, "ms": 0, "restarts": 0,
                        "cells": 256, "n": 16}

            def set_config(self, config, user_defined=True):
                self.calls.append(("set_config", config))
                return schema.coerce_config(config)

            def use_optimal(self):
                self.calls.append(("use_optimal", None))
                return self.tuner.optimal_config()

            def pause(self): self.calls.append(("pause", None))
            def resume(self): self.calls.append(("resume", None))
            def skip(self): self.calls.append(("skip", None))

        cls.sup = StubSupervisor(cls.db)
        app_mod.STATE["db"] = cls.db
        app_mod.STATE["supervisor"] = cls.sup

        cls.server = app_mod.Server(("127.0.0.1", 0), app_mod.Handler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.db.close()
        shutil.rmtree(cls.dir, ignore_errors=True)

    def conn(self):
        return self.http_client.HTTPConnection("127.0.0.1", self.port, timeout=10)

    def test_bootstrap_has_everything_the_page_needs(self):
        c = self.conn()
        c.request("GET", "/api/bootstrap")
        res = c.getresponse()
        self.assertEqual(res.status, 200)
        data = json.loads(res.read())
        for key in ("settings", "groupOrder", "defaults", "optimal",
                    "optimalDetails", "meta", "live", "attempts",
                    "attemptCount", "stats", "insights", "breakdown"):
            self.assertIn(key, data)
        self.assertEqual(len(data["settings"]), len(schema.SETTINGS))
        c.close()

    def test_keep_alive_survives_posts_with_unused_bodies(self):
        """Several POSTs then a GET, all on one connection."""
        c = self.conn()
        for path, payload in (("/api/config/optimal", {}),
                              ("/api/control", {"action": "pause"}),
                              ("/api/config/optimal", {}),
                              ("/api/control", {"action": "resume"})):
            body = json.dumps(payload)
            c.request("POST", path, body=body,
                      headers={"Content-Type": "application/json"})
            res = c.getresponse()
            self.assertEqual(res.status, 200, path)
            res.read()
        # the connection must still be usable and correctly framed
        c.request("GET", "/api/status")
        res = c.getresponse()
        self.assertEqual(res.status, 200)
        self.assertIn("attemptId", json.loads(res.read()))
        c.close()

    def test_set_config_reaches_the_supervisor(self):
        c = self.conn()
        c.request("POST", "/api/config",
                  body=json.dumps({"config": {"cellOrder": "rowMajor"}}),
                  headers={"Content-Type": "application/json"})
        res = c.getresponse()
        data = json.loads(res.read())
        self.assertTrue(data["ok"])
        self.assertTrue(data["userDefined"])
        self.assertEqual(data["config"]["cellOrder"], "rowMajor")
        self.assertIn("set_config", [name for name, _ in self.sup.calls])
        c.close()

    def test_unknown_action_is_rejected(self):
        c = self.conn()
        c.request("POST", "/api/control", body=json.dumps({"action": "explode"}),
                  headers={"Content-Type": "application/json"})
        self.assertEqual(c.getresponse().status, 400)
        c.close()

    def test_bad_paths_and_ids(self):
        c = self.conn()
        for method, path, expected in (("GET", "/api/nope", 404),
                                       ("POST", "/api/nope", 404),
                                       ("GET", "/api/attempts/abc", 400),
                                       ("GET", "/api/attempts/999999", 404),
                                       ("GET", "/no-such-file.js", 404)):
            c.request(method, path, body="{}" if method == "POST" else None,
                      headers={"Content-Type": "application/json"})
            res = c.getresponse()
            self.assertEqual(res.status, expected, "%s %s" % (method, path))
            res.read()
        c.close()

    def test_directory_traversal_is_blocked(self):
        c = self.conn()
        c.request("GET", "/../server/db.py")
        res = c.getresponse()
        self.assertIn(res.status, (403, 404))
        res.read()
        c.close()

    def test_static_assets_are_served_with_sane_types(self):
        c = self.conn()
        for path, expect in (("/", "text/html"),
                             ("/css/style.css", "text/css"),
                             ("/js/app.js", "javascript")):
            c.request("GET", path)
            res = c.getresponse()
            self.assertEqual(res.status, 200, path)
            self.assertIn(expect, res.getheader("Content-Type"))
            self.assertTrue(len(res.read()) > 0)
        c.close()

    def test_malformed_json_body_does_not_crash(self):
        c = self.conn()
        c.request("POST", "/api/config", body="{not json at all",
                  headers={"Content-Type": "application/json"})
        res = c.getresponse()
        self.assertEqual(res.status, 200)
        res.read()
        c.close()


# ---------------------------------------------------------------- integration

def _engine_available():
    classes = os.path.join(ROOT, "java", "classes", "app", "Engine.class")
    return os.path.isfile(classes) and shutil.which("java") is not None


@unittest.skipUnless(_engine_available(),
                     "compiled Java engine and a JVM are required")
class EngineIntegrationTest(unittest.TestCase):
    """Proves the server's argument plumbing matches what the engine accepts."""

    def _run(self, cfg):
        args = [shutil.which("java"), "-cp", os.path.join(ROOT, "java", "classes"),
                "app.Engine", "--frameMs=50"]
        args.extend(schema.to_engine_args(cfg))
        out = subprocess.run(args, capture_output=True, text=True, timeout=180)
        self.assertEqual(out.returncode, 0, out.stderr[:2000])
        events = []
        for line in out.stdout.splitlines():
            line = line.strip()
            if line:
                events.append(json.loads(line))
        return events

    def test_engine_accepts_every_default_and_reports_an_attempt(self):
        cfg = schema.coerce_config({"nodeBudget": 250_000})
        events = self._run(cfg)
        kinds = {e["type"] for e in events}
        self.assertIn("meta", kinds)
        self.assertIn("end", kinds)

        meta = next(e for e in events if e["type"] == "meta")
        self.assertEqual(meta["n"], 16)
        self.assertEqual(meta["cells"], 256)
        self.assertEqual(len(meta["pieces"]), 256)
        self.assertEqual(meta["fixed"], [[135, 138, 1]])

        end = next(e for e in events if e["type"] == "end")
        self.assertTrue(end["valid"], "engine reported an invalid board")
        self.assertLessEqual(end["nodes"], 250_000)
        self.assertGreater(end["edges"], 0)
        self.assertLessEqual(end["edges"], 480)
        self.assertEqual(end["edges"] == 480, end["solved"],
                         "only a solved board can match all 480 edges")
        self.assertEqual(len(end["order"]), end["best"])
        self.assertEqual(len(end["board"]), 256)
        # the fixed hint piece must be in place in the recorded order
        self.assertIn([135, 138, 1], end["order"])

    def test_engine_honours_each_extreme_setting(self):
        # a deliberately extreme configuration on every axis
        cfg = schema.coerce_config({
            "cellOrder": "rowMajor", "tieBreak": "lowestIndex",
            "valueOrder": "random", "shuffleStrength": 100,
            "candidateCap": 1, "forwardCheck": "none",
            "greyInteriorPruning": False, "restartPolicy": "luby",
            "restartBase": 1000, "nodeBudget": 100_000,
            "startCell": "bottomRight",
        })
        events = self._run(cfg)
        end = next(e for e in events if e["type"] == "end")
        self.assertTrue(end["valid"])
        self.assertLessEqual(end["nodes"], 100_000)

    def test_config_is_echoed_back_unchanged(self):
        cfg = schema.coerce_config({"nodeBudget": 100_000, "cellOrder": "hybrid",
                                    "hybridThreshold": 9})
        events = self._run(cfg)
        meta = next(e for e in events if e["type"] == "meta")
        self.assertEqual(meta["config"]["cellOrder"], "hybrid")
        self.assertEqual(meta["config"]["hybridThreshold"], 9)
        self.assertEqual(meta["config"]["nodeBudget"], 100_000)


# Import analytics tests so `python3 server/test_server.py` covers everything.
try:
    from test_analytics import *   # noqa: F401,F403
except ImportError:
    pass

if __name__ == "__main__":
    unittest.main(verbosity=2)
