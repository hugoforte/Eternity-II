"""Learns which settings produce better boards, and proposes the next config.

How it works
------------
Each finished attempt is scored by the matched internal edges of its best
board (480 on a solved puzzle), then every setting is treated as an independent
multi-armed bandit over its discrete choices (``schema.arms``).  For the *next*
attempt the tuner picks, per setting, the arm with the best UCB1 value:

    ucb(arm) = mean_score(arm) + C * sqrt(2 * ln(total) / n(arm))

Unseen arms are tried first, so the learner explores the whole space before it
commits.  On top of that there is an epsilon-greedy nudge that occasionally
randomises one setting outright, which stops the search getting stuck in a local
optimum when the landscape shifts.

"Optimal settings" (what the button and the UI defaults use) is the greedy view
of the same data: the arm with the highest *mean* score, provided it has enough
support, otherwise the measured cold-start default.

This is deliberately a simple, explainable model.  Every number the UI shows in
the Insights panel comes straight out of these tables, so a user can always see
why a value was chosen.
"""

import math
import random

import schema

# LessonManager is optional: the tuner works without it, but when one is
# attached (see Tuner.lessons), active lessons add a soft UCB bonus so the
# automatic learner is *nudged* toward what the Lessons page has concluded
# without the nudge becoming an iron rule.
LESSONS_REQUIRED = False

# Exploration weight, in matched-edge units. Scores run 0..480, so a handful
# of edges' worth of optimism is a sensible nudge.
UCB_C = 6.0

# Minimum attempts on a value before it can be called "optimal".
MIN_SUPPORT = 3

# Insights make a public claim, so they need firmer evidence than the
# exploration policy does.
INSIGHT_MIN_PER_ARM = 3
INSIGHT_MIN_TOTAL = 8


def score_attempt(matched_edges, nodes, node_budget, solved):
    """Score an attempt by the puzzle's own measure: matched internal edges.

    Eternity II is scored in edges, not pieces placed: the board has 480
    internal adjacencies and the grey rim is not scored.  Edges dominate: one
    extra matched edge always beats any efficiency gain.  Between two boards
    worth the same, the one that got there with fewer nodes scores slightly
    higher, which rewards configurations that are cheap as well as good.  A
    solve is worth a clear bonus above any partial board.

    ``matched_edges`` is required: attempts recorded before the engine
    reported it have none, and they are excluded from edge statistics rather
    than counted as zero (see :meth:`Tuner.arm_stats`).
    """
    if matched_edges is None:
        raise ValueError(
            "cannot score an attempt with no matched-edge count; attempts "
            "recorded before edge scoring have to be excluded instead")
    if solved:
        return 1000.0
    edges = float(matched_edges)
    budget = float(node_budget or 0)
    used = float(nodes or 0)
    if budget > 0 and used > 0:
        efficiency = 1.0 - min(1.0, used / budget)
    else:
        efficiency = 0.0
    # efficiency contributes strictly less than one extra matched edge
    return edges + 0.95 * efficiency


class Tuner:
    def __init__(self, db):
        self.db = db
        self._rng = random.Random()
        # Soft biasing toward lessons, set from the outside so lessons.py does
        # not need to be imported here (avoids a cycle).
        self.lessons = None

    # ------------------------------------------------------------ statistics

    def arm_stats(self):
        """Per-setting, per-value statistics.

        Returns ``{setting: {arm: {n, mean, adj, best}}}`` where

          mean  plain average score of attempts that used that value
          adj   average score *relative to other attempts of the same attempt
                length*, i.e. "edges better than a typical run of that budget"

        Only attempts a setting could have influenced count toward its arms:
        see ``schema.is_active``.  ``n`` is therefore the number of *relevant*
        attempts, which is what the optimal table and the insights report as
        their support.

        ``adj`` is what the learner actually optimises.  Attempt length is
        itself a tunable setting, so a 100k-step run can never reach as deep as
        a 100M-step one; comparing raw depths across budgets would make every
        other setting look good or bad purely because of the budget it happened
        to be paired with.  Centring each score against the mean for its own
        budget removes that confound.  For the budget setting itself the raw
        mean is the meaningful number, since there the effect *is* the budget.
        """
        history = self.db.finished_attempts_for_learning()

        # mean score per attempt-length bucket, used as the baseline
        by_budget = {}
        rows = []
        for row in history:
            # An attempt with no edge count predates edge scoring: its stored
            # score is in the old depth units and no edge count can be
            # recovered from its depth, so it is evidence about nothing here.
            if row["matchedEdges"] is None:
                continue
            cfg = row["config"]
            score = row["score"]
            if score is None:
                score = score_attempt(row["matchedEdges"], row["nodes"],
                                      cfg.get("nodeBudget"), row["solved"])
            budget = _arm_key(cfg.get("nodeBudget"))
            rows.append((row, cfg, score, budget))
            slot = by_budget.setdefault(budget, {"n": 0, "sum": 0.0})
            slot["n"] += 1
            slot["sum"] += score
        for slot in by_budget.values():
            slot["mean"] = slot["sum"] / slot["n"] if slot["n"] else 0.0

        stats = {s["key"]: {} for s in schema.tunable_settings()}
        for row, cfg, score, budget in rows:
            baseline = by_budget[budget]["mean"]
            for setting in schema.tunable_settings():
                key = setting["key"]
                if key not in cfg:
                    continue
                # A conditional setting that could not reach the search says
                # nothing about this attempt; crediting its arm anyway is how a
                # value chosen at random ends up looking significant.
                if not schema.is_active(key, cfg):
                    continue
                arm = _arm_key(schema.coerce(key, cfg[key]))
                bucket = stats[key].setdefault(
                    arm, {"n": 0, "sum": 0.0, "adjSum": 0.0, "best": 0})
                bucket["n"] += 1
                bucket["sum"] += score
                # nodeBudget is compared on its own terms, everything else is
                # compared against runs of the same length
                bucket["adjSum"] += score if key == "nodeBudget" else (score - baseline)
                if row["bestDepth"] > bucket["best"]:
                    bucket["best"] = row["bestDepth"]

        for key in stats:
            for bucket in stats[key].values():
                n = bucket["n"] or 1
                bucket["mean"] = bucket["sum"] / n
                bucket["adj"] = bucket["adjSum"] / n
        return stats

    # --------------------------------------------------------------- optimal

    def compute_optimal(self):
        """Greedy best-known settings, plus the reasoning behind each choice."""
        stats = self.arm_stats()
        entries = []
        config = schema.defaults()

        for setting in schema.tunable_settings():
            key = setting["key"]
            buckets = stats.get(key, {})
            eligible = [(arm, b) for arm, b in buckets.items() if b["n"] >= MIN_SUPPORT]
            if not eligible:
                entries.append((key, setting["default"], 0.0, 0,
                                "not enough data yet, using the measured default"))
                continue
            eligible.sort(key=lambda kv: kv[1]["adj"], reverse=True)
            best_arm, best_bucket = eligible[0]
            value = _unarm(key, best_arm)
            config[key] = value

            if len(eligible) == 1:
                reason = "only value tried so far (%d attempts, avg %.1f edges)" % (
                    best_bucket["n"], best_bucket["mean"])
            elif key == "nodeBudget":
                worst = eligible[-1]
                reason = "matches %.1f more edges than %s (avg %.1f over %d attempts)" % (
                    best_bucket["mean"] - worst[1]["mean"], _label(key, worst[0]),
                    best_bucket["mean"], best_bucket["n"])
            else:
                worst = eligible[-1]
                reason = ("%+.1f edges vs a typical run of the same length "
                          "over %d attempts; %.1f ahead of %s") % (
                    best_bucket["adj"], best_bucket["n"],
                    best_bucket["adj"] - worst[1]["adj"], _label(key, worst[0]))
            entries.append((key, value, best_bucket["adj"], best_bucket["n"], reason))

        self.db.save_optimal(entries)
        return schema.coerce_config(config)

    def optimal_config(self):
        """Read the stored optimal, falling back to defaults for gaps."""
        stored = self.db.load_optimal()
        cfg = schema.defaults()
        for key, info in stored.items():
            if key in schema.BY_KEY:
                cfg[key] = schema.coerce(key, info["value"])
        return cfg

    def optimal_details(self):
        stored = self.db.load_optimal()
        out = {}
        for setting in schema.SETTINGS:
            key = setting["key"]
            info = stored.get(key)
            out[key] = {
                "value": info["value"] if info else setting["default"],
                "support": info["support"] if info else 0,
                "meanScore": round(info["meanScore"], 2) if info else 0.0,
                "reason": info["reason"] if info else "measured default",
            }
        return out

    # ------------------------------------------------------- next suggestion

    def suggest(self):
        """Config for the next automatic attempt (UCB1 + epsilon exploration)."""
        stats = self.arm_stats()
        total = self.db.attempt_count()
        cfg = schema.defaults()

        for setting in schema.tunable_settings():
            key = setting["key"]
            options = schema.arms(setting)
            buckets = stats.get(key, {})

            unseen = [o for o in options if _arm_key(o) not in buckets]
            if unseen:
                cfg[key] = self._rng.choice(unseen)
                continue

            log_total = math.log(max(2, total))
            best_value, best_ucb = setting["default"], -1e18
            for option in options:
                bucket = buckets[_arm_key(option)]
                bonus = UCB_C * math.sqrt(2.0 * log_total / max(1, bucket["n"]))
                lesson_bonus = (
                    self.lessons.bonus_for(key, option) if self.lessons else 0.0)
                ucb = bucket["adj"] + bonus + lesson_bonus
                if ucb > best_ucb:
                    best_ucb, best_value = ucb, option
            cfg[key] = best_value

        # epsilon-greedy: occasionally shake one setting to keep exploring
        epsilon = max(0.08, 0.6 / math.sqrt(max(1, total)))
        if self._rng.random() < epsilon:
            setting = self._rng.choice(schema.tunable_settings())
            cfg[setting["key"]] = self._rng.choice(schema.arms(setting))

        # the seed is noise, not a strategy: always vary it
        cfg["randomSeed"] = self._rng.randint(0, 999999)
        return schema.coerce_config(cfg)

    # -------------------------------------------------------------- insights

    def rebuild_insights(self):
        """Turn the arm statistics into readable lessons, newest analysis wins."""
        stats = self.arm_stats()
        items = []
        for setting in schema.tunable_settings():
            key = setting["key"]
            # Each value needs its own evidence before we claim anything, and
            # the setting as a whole needs a reasonable sample.
            buckets = [(arm, b) for arm, b in stats.get(key, {}).items()
                       if b["n"] >= INSIGHT_MIN_PER_ARM]
            if len(buckets) < 2:
                continue
            support = sum(b["n"] for _, b in buckets)
            if support < INSIGHT_MIN_TOTAL:
                continue

            buckets.sort(key=lambda kv: kv[1]["adj"], reverse=True)
            best_arm, best = buckets[0]
            worst_arm, worst = buckets[-1]
            lift = best["adj"] - worst["adj"]
            if lift < 0.5:
                continue          # nothing worth reporting

            if key == "nodeBudget":
                headline = "%s: %s matches %.1f more edges than %s" % (
                    setting["label"], _label(key, best_arm), lift, _label(key, worst_arm))
                detail = ("Longer attempts dig deeper, as expected: %s averaged %.1f edges "
                          "(best depth %d) over %d attempts, %s averaged %.1f over %d.") % (
                    _label(key, best_arm), best["mean"], best["best"], best["n"],
                    _label(key, worst_arm), worst["mean"], worst["n"])
            else:
                headline = "%s: %s beats %s by %.1f edges" % (
                    setting["label"], _label(key, best_arm), _label(key, worst_arm), lift)
                detail = ("Compared only against runs of the same attempt length: "
                          "%s scored %+.1f edges (best depth %d, %d attempts), "
                          "%s scored %+.1f (%d attempts).") % (
                    _label(key, best_arm), best["adj"], best["best"], best["n"],
                    _label(key, worst_arm), worst["adj"], worst["n"])

            items.append({
                "setting": key,
                "headline": headline,
                "detail": detail,
                "best_value": _label(key, best_arm),
                "support": support,
                "lift": lift,
            })
        items.sort(key=lambda i: i["lift"], reverse=True)
        self.db.replace_insights(items)
        return items

    def setting_breakdown(self):
        """Per-setting, per-arm table for the Insights charts."""
        stats = self.arm_stats()
        out = []
        for setting in schema.tunable_settings():
            key = setting["key"]
            rows = []
            for option in schema.arms(setting):
                bucket = stats.get(key, {}).get(_arm_key(option))
                rows.append({
                    "value": option if not isinstance(option, bool) else bool(option),
                    "label": _label(key, _arm_key(option)),
                    "attempts": bucket["n"] if bucket else 0,
                    "meanScore": round(bucket["mean"], 2) if bucket else None,
                    "adjScore": round(bucket["adj"], 2) if bucket else None,
                    "bestDepth": bucket["best"] if bucket else 0,
                })
            if any(r["attempts"] for r in rows):
                out.append({
                    "setting": key,
                    "label": setting["label"],
                    "group": setting["group"],
                    "rows": rows,
                })
        return out

    # ------------------------------------------------------------ bookkeeping

    def after_attempt(self):
        """Refresh the learned model. Cheap enough to run after every attempt."""
        self.compute_optimal()
        return self.rebuild_insights()


# --------------------------------------------------------------------- helpers

def _arm_key(value):
    """Stable dict key for an arm value."""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _unarm(key, arm):
    """Inverse of _arm_key, guided by the setting's declared kind."""
    setting = schema.BY_KEY[key]
    kind = setting["kind"]
    if kind == "bool":
        return arm == "true"
    if kind in ("int", "scale"):
        try:
            return int(arm)
        except ValueError:
            return setting["default"]
    return arm


def _label(key, arm):
    """Human label for an arm, using the enum labels where available."""
    setting = schema.BY_KEY.get(key)
    if setting is None:
        return str(arm)
    if setting["kind"] == "enum":
        for option in setting["options"]:
            if option["value"] == arm:
                return option["label"]
    if setting["kind"] == "bool":
        return "on" if arm == "true" else "off"
    if setting["kind"] == "scale" or setting["kind"] == "int":
        try:
            return _pretty_number(int(arm))
        except ValueError:
            return str(arm)
    return str(arm)


def _pretty_number(value):
    if value >= 1000000 and value % 100000 == 0:
        return "%gM" % (value / 1000000.0)
    if value >= 1000 and value % 100 == 0:
        return "%gk" % (value / 1000.0)
    return str(value)
