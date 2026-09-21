"""Tests for backfill_breaks.recover_breaks.

Run with:  python3 server/test_backfill_breaks.py
"""

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from backfill_breaks import recover_breaks, CHECKS_BEFORE, MAX_PLAUSIBLE_BREAKS  # noqa: E402


class RecoverBreaksTest(unittest.TestCase):

    def test_mrv_never_slips(self):
        # Regardless of depth or edges, an MRV attempt's breaks is trivially 0.
        self.assertEqual(recover_breaks({"engine": "mrv"}, 200, 300), 0)

    def test_missing_engine_predates_slipping_entirely(self):
        # Attempts from before "engine" existed as a concept are from the
        # single-solver app, which never had a slipping mechanism at all.
        self.assertEqual(recover_breaks({}, 200, 300), 0)
        self.assertEqual(recover_breaks(None, 200, 300), 0)

    def test_scan_attempt_recovers_the_exact_break_count(self):
        depth = 227
        perfect = CHECKS_BEFORE["banded"][depth]
        for breaks in (0, 6, 12):
            cfg = {"engine": "scan", "fillOrder": "banded"}
            got = recover_breaks(cfg, depth, perfect - breaks)
            self.assertEqual(got, breaks)

    def test_row_major_uses_its_own_table(self):
        depth = 245
        perfect = CHECKS_BEFORE["rowMajor"][depth]
        cfg = {"engine": "scan", "fillOrder": "rowMajor"}
        self.assertEqual(recover_breaks(cfg, depth, perfect - 3), 3)

    def test_fill_order_defaults_to_banded_when_missing(self):
        depth = 200
        perfect = CHECKS_BEFORE["banded"][depth]
        cfg = {"engine": "scan"}
        self.assertEqual(recover_breaks(cfg, depth, perfect - 4), 4)

    def test_non_scan_attempt_with_no_edge_count_is_still_clean(self):
        # The oldest attempts predate edge scoring, so they have no count,
        # but they also predate slipping, so they cannot have any breaks.
        self.assertEqual(recover_breaks({}, 227, None), 0)
        self.assertEqual(recover_breaks({"engine": "mrv"}, 227, None), 0)

    def test_no_matched_edges_is_unrecoverable(self):
        self.assertIsNone(recover_breaks({"engine": "scan"}, 200, None))

    def test_no_depth_is_unrecoverable(self):
        self.assertIsNone(recover_breaks({"engine": "scan"}, 0, 300))
        self.assertIsNone(recover_breaks({"engine": "scan"}, None, 300))

    def test_a_negative_computed_break_count_is_refused_not_guessed(self):
        # matched_edges above the perfect score for that depth means one of
        # our assumptions about this row doesn't hold -- safer to leave NULL.
        depth = 200
        perfect = CHECKS_BEFORE["banded"][depth]
        cfg = {"engine": "scan", "fillOrder": "banded"}
        self.assertIsNone(recover_breaks(cfg, depth, perfect + 5))

    def test_an_implausibly_large_break_count_is_refused_not_guessed(self):
        depth = 240
        perfect = CHECKS_BEFORE["banded"][depth]
        cfg = {"engine": "scan", "fillOrder": "banded"}
        self.assertIsNone(recover_breaks(cfg, depth, perfect - (MAX_PLAUSIBLE_BREAKS + 1)))

    def test_an_unknown_fill_order_is_unrecoverable(self):
        self.assertIsNone(
            recover_breaks({"engine": "scan", "fillOrder": "spiral"}, 200, 300))


if __name__ == "__main__":
    unittest.main()
