"""Tests for the analytics engine and the lesson lifecycle.

Run together with the rest of the server suite:
    python3 server/test_server.py
or on its own:
    python3 server/test_analytics.py
"""

import json
import os
import random
import shutil
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import schema        # noqa: E402
import tuner as tuner_mod   # noqa: E402
from analytics import Analyzer, Finding, MIN_STRENGTH, MIN_SUPPORT   # noqa: E402
from db import Db       # noqa: E402
from lessons import LessonManager, LESSON_STREAK, RETIRE_STREAK   # noqa: E402


def _seed_attempt(db, *, config_overrides=None, best_depth=100,
                  nodes=250000, restarts=0, samples=None, placements=None,
                  status='budget', solved=False):
    cfg = schema.defaults()
    if config_overrides:
        cfg.update(config_overrides)
    aid = db.start_attempt(cfg, False, "tuner")
    db.finish_attempt(
        aid, status=status, solved=solved, valid=True,
        best_depth=best_depth, nodes=nodes, duration_ms=int(nodes / 250),
        nodes_per_sec=250_000, restarts=restarts,
        score=tuner_mod.score_attempt(best_depth, nodes,
                                      cfg.get('nodeBudget'), solved),
        order=placements or [], samples=samples or [])
    return aid


class AnalyticsBaseTest(unittest.TestCase):
    """Common scaffolding: temp DB, analyzer, helpers."""

    def setUp(self):
        # Tests use the global random module; seed it so test order cannot
        # accidentally change the noise in the "nothing should be found" case.
        random.seed(20240818)
        self.dir = tempfile.mkdtemp()
        self.db = Db(os.path.join(self.dir, "t.sqlite"))
        self.analyzer = Analyzer(self.db)

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.dir, ignore_errors=True)

    def analyze(self):
        result = self.analyzer.run()
        self.db.record_analysis(
            result['attempts'], result['duration_ms'],
            result['findings'], result.get('note'))
        return result


class AnalyzerTest(AnalyticsBaseTest):

    def test_empty_database_returns_a_friendly_note(self):
        result = self.analyzer.run()
        self.assertEqual(result['findings'], [])
        self.assertIn('finished attempts', result['note'])

    def test_finds_a_setting_effect_with_enough_evidence(self):
        """A and B at equal budget, A deeper -> one finding about cellOrder."""
        for _ in range(12):
            _seed_attempt(self.db, config_overrides={'cellOrder': 'mrv'},
                          best_depth=210)
            _seed_attempt(self.db, config_overrides={'cellOrder': 'rowMajor'},
                          best_depth=150)
        result = self.analyzer.run()
        matches = [f for f in result['findings'] if f['id'] == 'setting_cellOrder']
        self.assertTrue(matches, 'expected a setting_cellOrder finding')
        f = matches[0]
        self.assertIn('cellOrder', f['id'])
        # the visual refers to the human label, which the UI shows
        self.assertIn('Most constrained', json.dumps(f['visual']))
        # ...and the learnable rule refers to the enum value
        self.assertEqual(f['rule']['value'], 'mrv')
        self.assertGreaterEqual(f['strength'], MIN_STRENGTH)
        self.assertEqual(f['visual']['kind'], 'diverging_bars')

    def test_support_counts_only_attempts_a_setting_could_affect(self):
        """hybridThreshold does nothing unless the cell order is hybrid."""
        for _ in range(6):
            _seed_attempt(self.db, config_overrides={'cellOrder': 'hybrid',
                                                     'hybridThreshold': 1},
                          best_depth=150)
            _seed_attempt(self.db, config_overrides={'cellOrder': 'hybrid',
                                                     'hybridThreshold': 64},
                          best_depth=210)
        for _ in range(30):
            _seed_attempt(self.db, config_overrides={'cellOrder': 'mrv',
                                                     'hybridThreshold': 64},
                          best_depth=180)
        result = self.analyzer.run()
        matches = [f for f in result['findings']
                   if f['id'] == 'setting_hybridThreshold']
        self.assertTrue(matches, 'expected a setting_hybridThreshold finding')
        self.assertEqual(matches[0]['support'], 12,
                         'only the hybrid attempts are relevant evidence')

    def test_strength_is_clamped_to_something_readable(self):
        """z blows up when two groups are totally separable; clamp kicks in."""
        for _ in range(30):
            _seed_attempt(self.db, config_overrides={'cellOrder': 'mrv'},
                          best_depth=256)
            _seed_attempt(self.db, config_overrides={'cellOrder': 'rowMajor'},
                          best_depth=0)
        result = self.analyzer.run()
        matches = [f for f in result['findings'] if f['id'] == 'setting_cellOrder']
        self.assertTrue(matches)
        self.assertLessEqual(matches[0]['strength'], Finding.STRENGTH_CAP,
                             'raw z escaped the reporting cap')

    def test_budget_is_reported_as_an_expected_confound(self):
        """nodeBudget finding says so explicitly rather than pretending it's strategy."""
        for _ in range(8):
            _seed_attempt(self.db, config_overrides={'nodeBudget': 100_000},
                          best_depth=120)
            _seed_attempt(self.db, config_overrides={'nodeBudget': 10_000_000},
                          best_depth=220)
        result = self.analyzer.run()
        matches = [f for f in result['findings'] if f['id'] == 'setting_nodeBudget']
        self.assertTrue(matches, 'the budget finding should appear')
        self.assertIn('expected confound', matches[0]['paragraph'])
        # a confound is not worth promoting to a rule
        self.assertIsNone(matches[0]['rule'])

    def test_noise_alone_produces_no_findings(self):
        """All attempts identical in every way but the seed: nothing to say."""
        for _ in range(30):
            _seed_attempt(self.db, best_depth=180 + random.randint(-2, 2))
        result = self.analyzer.run()
        # any finding that sneaks through must not claim a settings effect
        for f in result['findings']:
            self.assertFalse(f['id'].startswith('setting_') and f['id'] != 'setting_nodeBudget',
                             'bogus setting finding: %s' % f['id'])

    def test_ids_are_stable_across_rescans(self):
        """The same dataset analysed twice must produce the same finding ids."""
        for _ in range(10):
            _seed_attempt(self.db, config_overrides={'cellOrder': 'mrv'}, best_depth=210)
            _seed_attempt(self.db, config_overrides={'cellOrder': 'rowMajor'}, best_depth=150)
        a = {f['id'] for f in self.analyzer.run()['findings']}
        b = {f['id'] for f in self.analyzer.run()['findings']}
        self.assertEqual(a, b)
        self.assertIn('setting_cellOrder', a)

    def test_weak_pattern_drops_out_on_rescan(self):
        """A finding that stops showing up is removed (that is what the user sees)."""
        for _ in range(10):
            _seed_attempt(self.db, config_overrides={'cellOrder': 'mrv'}, best_depth=210)
            _seed_attempt(self.db, config_overrides={'cellOrder': 'rowMajor'}, best_depth=150)
        self.analyze()
        self.assertIn('setting_cellOrder',
                      {f['id'] for f in self.db.latest_findings()})
        # Wash out the effect by adding symmetric new evidence on both sides,
        # so the two groups end up with indistinguishable distributions.
        for _ in range(120):
            for cell in ('mrv', 'rowMajor'):
                _seed_attempt(self.db, config_overrides={'cellOrder': cell}, best_depth=210)
                _seed_attempt(self.db, config_overrides={'cellOrder': cell}, best_depth=150)
        self.analyze()
        remaining = {f['id'] for f in self.db.latest_findings()}
        self.assertNotIn('setting_cellOrder', remaining,
                         'faded finding should have been dropped on rescan')

    def test_depth_distribution_finds_the_wall(self):
        """Most attempts should cluster -> the histogram finding fires."""
        for _ in range(60):
            _seed_attempt(self.db, best_depth=190 + random.randint(-2, 2))
        result = self.analyzer.run()
        matches = [f for f in result['findings'] if f['id'] == 'depth_distribution']
        self.assertTrue(matches)
        self.assertEqual(matches[0]['visual']['kind'], 'histogram')

    def test_learning_trajectory_fires_on_clear_upward_trend(self):
        depths = list(range(120, 220))  # oldest to newest, rising
        for d in depths:
            _seed_attempt(self.db, best_depth=d)
        result = self.analyzer.run()
        matches = [f for f in result['findings'] if f['id'] == 'learning_trajectory']
        self.assertTrue(matches)
        self.assertGreater(matches[0]['strength'], MIN_STRENGTH)

    def test_cell_hotness_uses_placements(self):
        # 10 "deep" attempts cover the border; 10 "shallow" only cover a few cells
        border = [(r * 16 + c, 1, 0) for r in (0, 15) for c in range(16)] + \
                 [(r * 16 + c, 1, 0) for c in (0, 15) for r in range(1, 15)]
        for _ in range(10):
            _seed_attempt(self.db, best_depth=215, placements=border)
            _seed_attempt(self.db, best_depth=50, placements=border[:8])
        result = self.analyzer.run()
        matches = [f for f in result['findings'] if f['id'] == 'cell_hotness']
        self.assertTrue(matches, 'cell_hotness should fire with obviously different placements')
        self.assertEqual(matches[0]['visual']['kind'], 'heatmap')
        self.assertEqual(len(matches[0]['visual']['values']), 256)

    def test_placement_signature_spots_border_first(self):
        border_cells = [(r * 16 + c, 1, 0) for r in (0, 15) for c in range(16)] + \
                       [(r * 16 + c, 1, 0) for c in (0, 15) for r in range(1, 15)]
        interior = [((1 + r) * 16 + (1 + c), 1, 0)
                    for r in range(14) for c in range(14)]
        for _ in range(10):
            _seed_attempt(self.db, best_depth=215, placements=border_cells + interior[:20])
            _seed_attempt(self.db, best_depth=50, placements=interior[:20])
        result = self.analyzer.run()
        matches = [f for f in result['findings'] if f['id'] == 'placement_signature']
        self.assertTrue(matches, 'placement_signature should fire')
        self.assertIn('frame', matches[0]['title'].lower())

    def test_outliers_are_detected(self):
        # 25 tight + two extremes big enough to survive the rising group std
        for _ in range(25):
            _seed_attempt(self.db, best_depth=180)
        _seed_attempt(self.db, best_depth=256)
        _seed_attempt(self.db, best_depth=5)
        result = self.analyzer.run()
        matches = [f for f in result['findings'] if f['id'] == 'outlier_attempts']
        self.assertTrue(matches)
        ids_flagged = [item['id'] for item in matches[0]['visual']['items']]
        self.assertGreaterEqual(len(ids_flagged), 2)


class LessonLifecycleTest(AnalyticsBaseTest):

    def setUp(self):
        super().setUp()
        self.lessons = LessonManager(self.db)

    def _strong_finding(self, fid='setting_cellOrder', value='mrv', strength=5.0):
        return {
            'id': fid,
            'group': 'settings',
            'title': '%s is strong' % fid,
            'paragraph': 'seeded',
            'strength': strength,
            'support': 50,
            'visual': {},
            'related': [],
            'rule': {'kind': 'prefer_value', 'setting': 'cellOrder',
                     'value': value, 'confidence': 0.8},
        }

    def test_streak_promotes_to_active(self):
        """A strong finding seen LESSON_STREAK times becomes active."""
        for i in range(LESSON_STREAK - 1):
            changes = self.lessons.ingest([self._strong_finding()])
            self.assertEqual(changes['added'], [])
        changes = self.lessons.ingest([self._strong_finding()])
        self.assertIn('prefer_cellOrder_mrv', changes['added'])
        rules = self.lessons.active_rules()
        self.assertEqual(len(rules), 1)
        self.assertEqual(rules[0]['setting'], 'cellOrder')
        self.assertEqual(rules[0]['value'], 'mrv')

    def test_absence_retires_an_active_lesson(self):
        """Once promoted, a lesson that stops appearing retires itself."""
        for _ in range(LESSON_STREAK):
            self.lessons.ingest([self._strong_finding()])
        self.assertTrue(self.lessons.active_rules())
        for _ in range(RETIRE_STREAK):
            self.lessons.ingest([])
        self.assertEqual(self.lessons.active_rules(), [])
        statuses = [l['status'] for l in self.lessons.listing()]
        self.assertIn('retired', statuses)

    def test_weak_finding_does_not_reach_the_lesson_stage(self):
        """Only findings at LESSON_STRENGTH or above can become lessons."""
        weak = self._strong_finding(strength=MIN_STRENGTH + 0.1)
        for _ in range(LESSON_STREAK + 2):
            self.lessons.ingest([weak])
        self.assertEqual(self.lessons.active_rules(), [],
                         'a weak rule should never be promoted')

    def test_dismiss_prevents_future_promotion(self):
        """A user dismissal is persistent and blocks re-promotion."""
        for _ in range(LESSON_STREAK):
            self.lessons.ingest([self._strong_finding()])
        lid = self.lessons.active_rules()[0]['lessonId']
        self.lessons.dismiss(lid)
        self.assertEqual(self.lessons.active_rules(), [])
        # even repeated strong findings should not re-activate after dismissal
        for _ in range(LESSON_STREAK * 2):
            self.lessons.ingest([self._strong_finding()])
        self.assertEqual(self.lessons.active_rules(), [])

    def test_restore_brings_a_lesson_back_to_watching(self):
        for _ in range(LESSON_STREAK):
            self.lessons.ingest([self._strong_finding()])
        lid = self.lessons.active_rules()[0]['lessonId']
        self.lessons.dismiss(lid)
        self.lessons.restore(lid)
        for _ in range(LESSON_STREAK):
            self.lessons.ingest([self._strong_finding()])
        self.assertTrue(self.lessons.active_rules())

    def test_tuner_is_soft_biased_not_overridden(self):
        """Active lesson adds a UCB bonus but does not ban exploration."""
        for _ in range(LESSON_STREAK):
            self.lessons.ingest([self._strong_finding(value='mrv')])
        bonus_matching = self.lessons.bonus_for('cellOrder', 'mrv')
        bonus_other = self.lessons.bonus_for('cellOrder', 'rowMajor')
        self.assertGreater(bonus_matching, 0.0)
        self.assertEqual(bonus_other, 0.0)
        # other settings untouched
        self.assertEqual(self.lessons.bonus_for('tieBreak', 'mostNeighbours'), 0.0)


class RecordAnalysisPersistenceTest(AnalyticsBaseTest):

    def test_stream_of_findings_replaces_stale_ones(self):
        """Each analyze run replaces the stored set; stale ids disappear."""
        self.db.record_analysis(10, 5, [
            Finding('setting_A', 'settings', 'A wins', 'x', 5.0, 20).to_dict(),
            Finding('setting_B', 'settings', 'B wins', 'x', 5.0, 20).to_dict(),
        ])
        ids1 = {r['id'] for r in self.db.latest_findings()}
        self.assertEqual(ids1, {'setting_A', 'setting_B'})
        self.db.record_analysis(10, 5, [
            Finding('setting_A', 'settings', 'A wins', 'x', 5.0, 20).to_dict(),
            Finding('setting_C', 'settings', 'C wins', 'x', 5.0, 20).to_dict(),
        ])
        ids2 = {r['id'] for r in self.db.latest_findings()}
        self.assertEqual(ids2, {'setting_A', 'setting_C'})
        # the persisted A must have its streak bumped
        stored = {r['id']: r for r in self.db.latest_findings()}
        self.assertEqual(stored['setting_A']['rescanStreak'], 2)
        self.assertEqual(stored['setting_C']['rescanStreak'], 1)

    def test_clear_history_also_clears_analysis(self):
        self.db.record_analysis(10, 5, [
            Finding('x', 'settings', 't', 'p', 5.0, 10).to_dict()])
        self.db.clear_history()
        self.assertEqual(self.db.latest_findings(), [])
        self.assertIsNone(self.db.latest_analysis())


if __name__ == '__main__':
    unittest.main(verbosity=2)
