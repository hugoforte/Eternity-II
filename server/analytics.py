"""Pattern-finding engine for the Insights page.

The Tuner (tuner.py) is a tight automatic learner that runs after every attempt
and only looks at one question at a time: "for each setting, which value works
best?".  The Analyzer here is the opposite: deliberately triggered by the user,
looking at every angle it can think of across every attempt on file, and
producing findings with visuals the page can render.

Each analysis returns zero or more :class:`Finding` objects.  A finding has a
**stable identifier** (``setting_cellOrder``, ``board_cell_hotness``, ...), so
successive rescans can tell whether the same pattern has weakened, and
dominated patterns drop out of the report naturally when a rescan does not find
them again.

Strength of a finding is a z-like score: *effect size divided by its standard
error*, so both a big difference and enough data to trust it are required.
The strength threshold is deliberately modest for visibility, but the Lessons
layer (lessons.py) only promotes findings that stay strong across multiple
rescans.

Nothing here depends on scipy or numpy.  All statistics are recomputed from
``statistics`` primitives so the app keeps the zero-dependency story.
"""

import json
import math
import statistics
import time
from collections import defaultdict

import schema


# ---------------------------------------------------------------- thresholds

# Minimum z-like strength for a finding to be reported at all.  Below this we
# don't have enough evidence that the effect is real.
MIN_STRENGTH = 2.0

# Don't report a finding whose observation count is below this floor; with
# tiny samples every difference looks significant.
MIN_SUPPORT = 8

# Above this the finding is "strong" and becomes a candidate for promotion to
# a persistent Lesson (see lessons.py).
STRONG_STRENGTH = 3.5

# Very small positive constant to avoid 0/0 when every attempt produced the
# same number.
EPS = 1e-9


# =======================================================================
#                                Finding
# =======================================================================

class Finding:
    """One pattern the Analyzer has spotted.

    Attributes
    ----------
    id
        Stable identifier.  Two rescans of the same analysis produce the same
        id, which is how the system detects a pattern that has persisted (and
        hence might become a lesson) or one that has vanished.
    group
        ``'settings'``, ``'board'`` or ``'dynamics'``.  The UI groups cards by
        this.
    strength
        Roughly ``|effect| / stderr``.  Interpretable as a z-score.
    rule
        If non-None, a dict describing how a lesson derived from this finding
        would bias future attempts: e.g. ``{'kind': 'prefer_value',
        'setting': 'cellOrder', 'value': 'mrv'}``.  The tuner reads this.
    """

    __slots__ = ('id', 'group', 'title', 'paragraph', 'strength', 'raw_strength',
                 'support', 'visual', 'related', 'rule')

    # Past ~10 the exact z-score stops being informative -- "z = 400" just
    # means "really, really sure", not ten times more sure than "z = 40".
    # The UI uses strength for sorting and for a simple three-band label
    # ("weak" / "moderate" / "strong"), so clamping the reported value keeps
    # it legible without changing the ordering of the first several dozen.
    STRENGTH_CAP = 30.0

    def __init__(self, id, group, title, paragraph, strength, support,
                 visual=None, related=None, rule=None):
        self.id = id
        self.group = group
        self.title = title
        self.paragraph = paragraph
        raw = float(strength)
        self.raw_strength = round(raw, 3)
        self.strength = round(min(raw, self.STRENGTH_CAP), 3)
        self.support = int(support)
        self.visual = visual or {}
        self.related = related or []
        self.rule = rule

    def to_dict(self):
        return {
            'id': self.id,
            'group': self.group,
            'title': self.title,
            'paragraph': self.paragraph,
            'strength': self.strength,
            'support': self.support,
            'visual': self.visual,
            'related': self.related,
            'rule': self.rule,
        }


# =======================================================================
#                              The Analyzer
# =======================================================================

class Analyzer:
    """Run every analysis over the attempts currently in the database."""

    def __init__(self, db):
        self.db = db

    def run(self):
        t0 = time.time()
        attempts = self._load_attempts()

        result = {
            'findings': [],
            'attempts': len(attempts),
            'duration_ms': 0,
            'at': time.time(),
            'note': None,
        }

        if len(attempts) < MIN_SUPPORT:
            result['note'] = (
                'Need at least %d finished attempts on file before the '
                'analyser has enough signal to separate patterns from noise. '
                'Currently on file: %d.' % (MIN_SUPPORT, len(attempts)))
            result['duration_ms'] = int((time.time() - t0) * 1000)
            return result

        findings = []
        findings.extend(_setting_effects(attempts))
        findings.extend(_setting_interactions(attempts))
        findings.extend(_winning_profile(attempts))
        findings.extend(_depth_distribution(attempts))
        findings.extend(_budget_returns(attempts))
        findings.extend(_restart_effect(attempts))
        findings.extend(_learning_trajectory(attempts))
        findings.extend(_time_to_depth(attempts, self.db))
        findings.extend(_cell_hotness(attempts, self.db))
        findings.extend(_placement_signature(attempts, self.db))
        findings.extend(_outlier_attempts(attempts))

        # keep only patterns that clear the floor, then dedupe by id (a rare
        # case where two analyses both produced the same stable key)
        findings = [f for f in findings
                    if f.strength >= MIN_STRENGTH and f.support >= MIN_SUPPORT]
        best_by_id = {}
        for f in findings:
            existing = best_by_id.get(f.id)
            if existing is None or existing.strength < f.strength:
                best_by_id[f.id] = f
        findings = sorted(best_by_id.values(), key=lambda f: -f.strength)

        result['findings'] = [f.to_dict() for f in findings]
        result['duration_ms'] = int((time.time() - t0) * 1000)
        return result

    def _load_attempts(self):
        rows = self.db.finished_attempts_for_learning(limit=100000)
        # sort oldest first so the learning-trajectory analysis can see order
        rows.sort(key=lambda r: r.get('id') or 0)
        return [a for a in rows if a.get('bestDepth') is not None]


# =======================================================================
#                               Statistics
# =======================================================================
# Small helpers so every analysis speaks the same language.

def _mean(xs):
    return statistics.fmean(xs) if xs else 0.0


def _var(xs):
    """Sample variance; 0 for degenerate inputs."""
    if len(xs) < 2:
        return 0.0
    return statistics.variance(xs)


def _welch_z(a, b):
    """Return (effect, z-like) for two samples using Welch's formula."""
    if len(a) < 2 or len(b) < 2:
        return 0.0, 0.0
    effect = _mean(a) - _mean(b)
    se = math.sqrt(_var(a) / len(a) + _var(b) / len(b))
    if se < EPS:
        return effect, 0.0 if abs(effect) < EPS else 99.0
    return effect, effect / se


def _pearson(xs, ys):
    """Pearson correlation; returns 0 for degenerate inputs."""
    n = len(xs)
    if n < 3 or len(ys) != n:
        return 0.0
    mx = _mean(xs); my = _mean(ys)
    num = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    dx = math.sqrt(sum((x - mx) ** 2 for x in xs))
    dy = math.sqrt(sum((y - my) ** 2 for y in ys))
    if dx < EPS or dy < EPS:
        return 0.0
    return num / (dx * dy)


def _corr_z(r, n):
    """Fisher transform -> approximate z-score for a correlation coefficient."""
    if n < 4 or abs(r) >= 1.0:
        return 0.0
    z = 0.5 * math.log((1 + r) / (1 - r))
    se = 1.0 / math.sqrt(n - 3)
    return z / se


def _score_of(attempt):
    """The scalar we feed every analysis: best depth."""
    return float(attempt.get('bestDepth') or 0)


def _budget_of(attempt):
    return (attempt.get('config') or {}).get('nodeBudget')


def _adjusted_scores(attempts):
    """score - mean(score | same nodeBudget).

    This is the same confound-removal used by the tuner: a 100M-step attempt
    cannot help but reach deeper than a 100k-step one, so to judge any other
    setting fairly we compare each attempt only against runs of its own length.
    """
    by_budget = defaultdict(list)
    for a in attempts:
        by_budget[_budget_of(a)].append(_score_of(a))
    baseline = {k: _mean(v) for k, v in by_budget.items()}
    return [(a, _score_of(a) - baseline.get(_budget_of(a), 0.0)) for a in attempts]


# Stable keys and pretty labels for arm values ------------------------------
def _arm_key(value):
    if isinstance(value, bool):
        return 'true' if value else 'false'
    return str(value)


def _unarm(key, arm):
    setting = schema.BY_KEY.get(key)
    if setting is None:
        return arm
    kind = setting['kind']
    if kind == 'bool':
        return arm == 'true'
    if kind in ('int', 'scale'):
        try:
            return int(arm)
        except ValueError:
            return setting.get('default')
    return arm


def _label(key, arm):
    """Human-readable label for a setting value."""
    setting = schema.BY_KEY.get(key)
    if setting is None:
        return str(arm)
    if setting['kind'] == 'enum':
        for opt in setting['options']:
            if _arm_key(opt['value']) == str(arm):
                return opt['label']
    if setting['kind'] == 'bool':
        return 'on' if str(arm) == 'true' else 'off'
    try:
        v = int(arm)
    except (ValueError, TypeError):
        return str(arm)
    if v >= 1_000_000 and v % 100_000 == 0:
        return '%gM' % (v / 1_000_000.0)
    if v >= 1000 and v % 100 == 0:
        return '%gk' % (v / 1000.0)
    return str(v)


# =======================================================================
#                        Analyses, one per function
# =======================================================================
#
# Each analysis is free to return zero, one or several findings.  The runner
# above filters by MIN_STRENGTH / MIN_SUPPORT, so each analysis is allowed to
# be noisy - only the ones that really stand out will survive.


def _setting_effects(attempts):
    """For every tunable setting, best arm vs worst arm at equal budget.

    One card per setting that has a real spread.  This is the headline
    analysis: it tells the user which knobs matter.

    An attempt only counts toward a setting it could have influenced
    (``schema.is_active``), so the support a card reports -- and the support a
    lesson inherits from it -- is the number of *relevant* attempts.
    """
    adjusted = _adjusted_scores(attempts)

    by_setting = defaultdict(lambda: defaultdict(list))
    raw_by_setting = defaultdict(lambda: defaultdict(list))
    for a, adj in adjusted:
        cfg = a.get('config') or {}
        for setting in schema.tunable_settings():
            key = setting['key']
            if key not in cfg or not schema.is_active(key, cfg):
                continue
            arm = _arm_key(schema.coerce(key, cfg[key]))
            value = adj if key != 'nodeBudget' else _score_of(a)
            by_setting[key][arm].append(value)
            raw_by_setting[key][arm].append(_score_of(a))

    out = []
    for setting in schema.tunable_settings():
        key = setting['key']
        arms = by_setting[key]
        eligible = [(k, v) for k, v in arms.items() if len(v) >= 3]
        if len(eligible) < 2:
            continue
        eligible.sort(key=lambda kv: _mean(kv[1]), reverse=True)
        best_key, best_vals = eligible[0]
        worst_key, worst_vals = eligible[-1]
        effect, z = _welch_z(best_vals, worst_vals)
        if z < 0:
            z = -z
        total = sum(len(v) for _, v in eligible)

        rows = []
        for k, vs in sorted(arms.items(),
                            key=lambda kv: (-_mean(kv[1]) if kv[1] else 0)):
            rows.append({
                'label': _label(key, k),
                'value': round(_mean(vs), 2),
                'raw': round(_mean(raw_by_setting[key][k]), 1),
                'n': len(vs),
                'best': k == best_key,
            })

        best_raw = _mean(raw_by_setting[key][best_key])
        worst_raw = _mean(raw_by_setting[key][worst_key])

        if key == 'nodeBudget':
            title = '%s: longer attempts reach deeper (expected)' % setting['label']
            paragraph = (
                'Over %d attempts, runs with %s reached an average depth of '
                '%.1f pieces, against %.1f for runs with %s. This is the '
                'expected confound: the Analyzer compensates for it when '
                'comparing every other setting.' % (
                    total, _label(key, best_key), best_raw,
                    worst_raw, _label(key, worst_key)))
            rule = None
        else:
            title = '%s: "%s" beats "%s" at equal attempt length' % (
                setting['label'], _label(key, best_key), _label(key, worst_key))
            paragraph = (
                'Compared only against attempts of the same length so budget '
                'does not distort the picture, "%s" averages %+.1f pieces '
                'relative to a typical run, while "%s" averages %+.1f. The '
                'effect is worth %.1f pieces per attempt (%d attempts '
                'supporting, z = %.1f).' % (
                    _label(key, best_key), _mean(best_vals),
                    _label(key, worst_key), _mean(worst_vals),
                    effect, total, z))
            rule = None
            if z >= STRONG_STRENGTH:
                rule = {
                    'kind': 'prefer_value',
                    'setting': key,
                    'value': _unarm(key, best_key),
                    'confidence': min(0.95, z / 10.0),
                }

        out.append(Finding(
            id='setting_%s' % key,
            group='settings',
            title=title,
            paragraph=paragraph,
            strength=z,
            support=total,
            visual={
                'kind': 'diverging_bars',
                'rows': rows,
                'xlabel': 'pieces vs a same-length run'
                          if key != 'nodeBudget' else 'average depth (pieces)',
                'centered': key != 'nodeBudget',
            },
            rule=rule,
        ))
    return out


def _setting_interactions(attempts):
    """Pairs of settings whose combination beats what their marginals predict.

    For every ordered pair of settings we compute, for each (value_a, value_b)
    combination with enough samples, the observed mean *and* the additive
    prediction from each marginal.  A large gap with enough data is an
    interaction - a combo that is *especially* good or *especially* bad.

    A pair is only measured over attempts where *both* settings were active,
    for the same reason the per-setting analysis is.
    """
    adjusted = _adjusted_scores(attempts)
    # skip nodeBudget: it's the thing we're controlling for
    keys = [s['key'] for s in schema.tunable_settings() if s['key'] != 'nodeBudget']

    grand_mean = _mean([adj for _, adj in adjusted])

    # marginal means per (key, arm)
    margin = defaultdict(lambda: defaultdict(list))
    for a, adj in adjusted:
        cfg = a.get('config') or {}
        for key in keys:
            v = cfg.get(key)
            if v is None or not schema.is_active(key, cfg):
                continue
            margin[key][_arm_key(schema.coerce(key, v))].append(adj)
    marginal_mean = {k: {arm: _mean(vs) for arm, vs in d.items()}
                     for k, d in margin.items()}

    # joint cells
    joint = defaultdict(lambda: defaultdict(list))
    for a, adj in adjusted:
        cfg = a.get('config') or {}
        for i, k1 in enumerate(keys):
            for k2 in keys[i + 1:]:
                v1 = cfg.get(k1); v2 = cfg.get(k2)
                if v1 is None or v2 is None:
                    continue
                if not (schema.is_active(k1, cfg) and schema.is_active(k2, cfg)):
                    continue
                arm1 = _arm_key(schema.coerce(k1, v1))
                arm2 = _arm_key(schema.coerce(k2, v2))
                joint[(k1, k2)][(arm1, arm2)].append(adj)

    out = []
    # rank candidate pairs by the biggest interaction anywhere
    for (k1, k2), cells in joint.items():
        best = None
        best_abs = 0.0
        total_n = 0
        rows = []
        for (a1, a2), vs in cells.items():
            if len(vs) < 4:
                continue
            total_n += len(vs)
            observed = _mean(vs)
            additive = (grand_mean
                        + (marginal_mean[k1].get(a1, 0.0) - grand_mean)
                        + (marginal_mean[k2].get(a2, 0.0) - grand_mean))
            interaction = observed - additive
            # stderr of the mean of vs, for a z-like strength
            se = math.sqrt(_var(vs) / len(vs)) if len(vs) > 1 else 0.0
            z = abs(interaction) / se if se > EPS else 0.0
            rows.append({
                'a': _label(k1, a1), 'b': _label(k2, a2),
                'observed': round(observed, 2),
                'additive': round(additive, 2),
                'interaction': round(interaction, 2),
                'n': len(vs),
            })
            score = z * math.sqrt(len(vs))
            if score > best_abs:
                best_abs = score
                best = {
                    'k1': k1, 'a1': a1, 'k2': k2, 'a2': a2,
                    'observed': observed, 'additive': additive,
                    'interaction': interaction, 'z': z, 'n': len(vs),
                }
        if best is None or best['z'] < MIN_STRENGTH or total_n < MIN_SUPPORT:
            continue

        label1 = schema.BY_KEY[k1]['label']
        label2 = schema.BY_KEY[k2]['label']
        sign = 'boosts' if best['interaction'] > 0 else 'hurts'
        v_label1 = _label(k1, best['a1'])
        v_label2 = _label(k2, best['a2'])
        title = ('%s = "%s" + %s = "%s" %s each other'
                 % (label1, v_label1, label2, v_label2, sign))
        paragraph = (
            'The pair is worth %.1f pieces on top of what either setting '
            'predicts on its own (observed %+.1f vs additive prediction %+.1f,'
            ' over %d matching attempts).  This is an interaction: the two '
            'settings are not independent, and the combination is '
            'informative beyond the individual effects.' % (
                best['interaction'], best['observed'],
                best['additive'], best['n']))

        rows.sort(key=lambda r: -abs(r['interaction']))
        rows = rows[:9]

        out.append(Finding(
            id='interaction_%s__%s' % (k1, k2),
            group='settings',
            title=title,
            paragraph=paragraph,
            strength=best['z'],
            support=total_n,
            visual={
                'kind': 'interaction_table',
                'rows': rows,
                'labelA': label1,
                'labelB': label2,
            },
            rule=None,  # interactions don't become rigid lessons
        ))
    return out


def _winning_profile(attempts):
    """The single best (observed) configuration on record."""
    # collapse each attempt's config to a canonical tuple (ignore the seed)
    by_profile = defaultdict(list)
    for a in attempts:
        cfg = a.get('config') or {}
        profile = tuple(sorted(
            (k, _arm_key(schema.coerce(k, cfg[k])))
            for k in cfg if k in schema.BY_KEY and k != 'randomSeed'
        ))
        by_profile[profile].append(a)
    eligible = [(p, runs) for p, runs in by_profile.items() if len(runs) >= 3]
    if not eligible:
        return []

    def depth(runs):
        return _mean([_score_of(a) for a in runs])

    eligible.sort(key=lambda kv: -depth(kv[1]))
    best_profile, best_runs = eligible[0]
    others = [a for runs in (r for p, r in eligible[1:]) for a in runs]
    if len(others) < 3:
        return []

    best_vals = [_score_of(a) for a in best_runs]
    rest_vals = [_score_of(a) for a in others]
    effect, z = _welch_z(best_vals, rest_vals)
    if effect <= 0 or abs(z) < MIN_STRENGTH:
        return []

    ids = sorted([a['id'] for a in best_runs if a.get('id')], reverse=True)[:5]
    profile_rows = [{'label': schema.BY_KEY[k]['label'],
                     'value': _label(k, a)} for k, a in best_profile]
    paragraph = (
        'One configuration stands out.  Across %d attempts it averaged '
        '%.1f pieces, against %.1f for the rest of the history (effect '
        '%+.1f, z = %.1f).  It is the closest thing to a "house style" '
        'found so far.' % (
            len(best_runs), _mean(best_vals), _mean(rest_vals),
            effect, abs(z)))
    return [Finding(
        id='winning_profile',
        group='settings',
        title='A single configuration is outperforming the field',
        paragraph=paragraph,
        strength=abs(z),
        support=len(best_runs) + len(others),
        visual={'kind': 'profile', 'rows': profile_rows,
                'mean': round(_mean(best_vals), 1),
                'others': round(_mean(rest_vals), 1)},
        related=ids,
        rule=None,
    )]


def _depth_distribution(attempts):
    """Histogram of best depths, with mode detection ("depth ceilings")."""
    depths = [_score_of(a) for a in attempts]
    if len(depths) < MIN_SUPPORT:
        return []

    # bin into 10-piece buckets
    bin_size = 10
    bins = defaultdict(int)
    for d in depths:
        bins[int(d // bin_size) * bin_size] += 1
    total = len(depths)
    buckets = sorted(bins.items())
    hist = [{'x0': b, 'x1': b + bin_size, 'count': c,
             'pct': round(100 * c / total, 1)} for b, c in buckets]

    # find peaks: a bucket whose count is a local max and > 15% of attempts
    peaks = []
    counts = [c for _, c in buckets]
    for i, (b, c) in enumerate(buckets):
        left = counts[i - 1] if i > 0 else 0
        right = counts[i + 1] if i + 1 < len(counts) else 0
        if c > left and c > right and c / total >= 0.15:
            peaks.append((b, c))
    if not peaks:
        return []

    top = max(peaks, key=lambda bc: bc[1])
    depth_lo, count = top
    pct = count / total
    # Strength: how tightly does the distribution concentrate on this peak,
    # weighted by how many attempts support the observation?  `pct *
    # sqrt(total)` behaves sensibly at both extremes: all-in-one-bucket with
    # 60 attempts gives 7.7; a 20% peak with 60 attempts gives 1.5 and is
    # correctly ignored.  Crucially, it does not divide by zero when the
    # distribution collapses to a single bucket.
    pct = count / total
    z = pct * math.sqrt(total)
    if z < MIN_STRENGTH:
        return []

    paragraph = (
        '%.0f%% of the %d attempts finished between depth %d and %d.  '
        'This spike is the search "wall": the point most configurations '
        'reach before they run out of legal moves.  Pushing past it '
        'is the hard part of the problem.' % (
            pct * 100, total, depth_lo, depth_lo + bin_size))

    return [Finding(
        id='depth_distribution',
        group='dynamics',
        title='Most attempts stall in a narrow depth band',
        paragraph=paragraph,
        strength=abs(z),
        support=total,
        visual={'kind': 'histogram', 'bins': hist,
                'peak': depth_lo, 'xlabel': 'best depth reached'},
        rule=None,
    )]


def _budget_returns(attempts):
    """Depth vs attempt length, to reveal diminishing returns."""
    by_budget = defaultdict(list)
    for a in attempts:
        b = _budget_of(a)
        if b is None:
            continue
        by_budget[b].append(_score_of(a))
    rows = [(int(b), vs) for b, vs in by_budget.items() if len(vs) >= 3]
    if len(rows) < 3:
        return []
    rows.sort(key=lambda bv: bv[0])

    points = [{'x': b, 'y': round(_mean(vs), 1), 'n': len(vs)} for b, vs in rows]
    depths = [p['y'] for p in points]

    # fit log-ish: compare slopes of adjacent segments to detect a knee
    knee_index = None
    knee_drop = 0.0
    for i in range(1, len(points) - 1):
        left_slope = ((depths[i] - depths[i - 1])
                      / max(1, math.log10(points[i]['x'] / points[i - 1]['x'])))
        right_slope = ((depths[i + 1] - depths[i])
                       / max(1, math.log10(points[i + 1]['x'] / points[i]['x'])))
        drop = left_slope - right_slope
        if drop > knee_drop and right_slope < 0.5 * max(1, left_slope):
            knee_drop = drop
            knee_index = i

    # strength: span of the depth curve vs noise within each budget
    within_var = _mean([_var(vs) for _, vs in rows if len(vs) > 1]) or EPS
    span = max(depths) - min(depths)
    total_n = sum(len(vs) for _, vs in rows)
    z = span / math.sqrt(within_var / max(3, total_n // len(rows)))
    if z < MIN_STRENGTH or span < 2:
        return []

    if knee_index is not None:
        knee_budget = points[knee_index]['x']
        knee_depth = points[knee_index]['y']
        paragraph = (
            'Average best depth rises with attempt length, but the curve '
            'flattens at around %s steps (%.1f pieces).  Longer attempts '
            'past that point pay off very little: %+.1f pieces from there '
            'to the longest runs on record.  For quick experiments you '
            'are better off running several shorter attempts.' % (
                _label('nodeBudget', str(knee_budget)),
                knee_depth, depths[-1] - knee_depth))
    else:
        paragraph = (
            'Depth increases steadily with attempt length across the range '
            'on file (%+.1f pieces from shortest to longest).  No clear '
            'diminishing-returns knee yet.' % span)

    return [Finding(
        id='budget_returns',
        group='dynamics',
        title='Longer attempts reach deeper, up to a point',
        paragraph=paragraph,
        strength=z,
        support=total_n,
        visual={'kind': 'line', 'points': points,
                'logx': True, 'knee': knee_index,
                'xlabel': 'attempt length (search steps)',
                'ylabel': 'average depth reached'},
        rule=None,
    )]


def _restart_effect(attempts):
    """Correlation of restart count and best depth, *within same config*.

    Restarts are a knob: what we want to know is whether turning them up tends
    to produce a deeper board, all else being equal.  All-else-being-equal
    is approximated by looking within same-length attempts only.
    """
    data_x, data_y = [], []
    for a in attempts:
        r = int(a.get('restarts') or 0)
        data_x.append(r)
        data_y.append(_score_of(a))
    if len(data_x) < MIN_SUPPORT or max(data_x) == 0:
        return []

    adjusted = _adjusted_scores(attempts)
    xs = [int(a.get('restarts') or 0) for a, _ in adjusted]
    ys = [adj for _, adj in adjusted]
    r = _pearson(xs, ys)
    z = _corr_z(r, len(xs))
    if abs(z) < MIN_STRENGTH:
        return []

    # downsample to a scatter of at most 200 points
    pts = list(zip(xs, ys))
    if len(pts) > 200:
        step = len(pts) // 200
        pts = pts[::step]
    scatter = [{'x': x, 'y': round(y, 1)} for x, y in pts]

    direction = 'helps' if r > 0 else 'hurts'
    paragraph = (
        'The restart policy has a measurable effect here: attempts with more '
        'restarts score %+.1f pieces per restart on average (Pearson r = '
        '%.2f, n = %d, z = %.1f).  In other words, the "Restarts" knob '
        '%s.' % (
            _slope(xs, ys), r, len(xs), abs(z), direction))

    rule = None
    if abs(z) >= STRONG_STRENGTH:
        # translate "restarts help / hurt" into a lesson about restart policy
        rule = {
            'kind': 'prefer_value',
            'setting': 'restartPolicy',
            # enable them if they help, disable if they hurt
            'value': 'luby' if r > 0 else 'none',
            'confidence': min(0.9, abs(z) / 10.0),
        }

    return [Finding(
        id='restart_effect',
        group='dynamics',
        title='More restarts %s reach deeper boards' % ('tend to' if r > 0 else 'tend NOT to'),
        paragraph=paragraph,
        strength=abs(z),
        support=len(xs),
        visual={'kind': 'scatter', 'points': scatter,
                'xlabel': 'restarts during an attempt',
                'ylabel': 'depth vs same-length average'},
        rule=rule,
    )]


def _slope(xs, ys):
    """Ordinary least-squares slope, stable against zero variance."""
    if len(xs) < 2:
        return 0.0
    mx = _mean(xs); my = _mean(ys)
    num = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    den = sum((x - mx) ** 2 for x in xs)
    return num / den if den > EPS else 0.0


def _learning_trajectory(attempts):
    """Is the app actually getting better over time?

    Compares the mean depth of the most recent quarter of attempts against
    the previous quarter.  A clear upward step means the tuner's exploration
    is paying off.
    """
    if len(attempts) < 2 * MIN_SUPPORT:
        return []
    ordered = sorted(attempts, key=lambda a: a.get('id') or 0)
    depths = [_score_of(a) for a in ordered]
    q = max(4, len(depths) // 4)
    recent = depths[-q:]
    previous = depths[-2 * q:-q]
    effect, z = _welch_z(recent, previous)
    if abs(z) < MIN_STRENGTH:
        return []

    # line chart: rolling mean of depth over attempts
    win = max(4, len(depths) // 20)
    rolling = []
    for i in range(len(depths)):
        lo = max(0, i - win // 2)
        hi = min(len(depths), i + win // 2 + 1)
        rolling.append(round(_mean(depths[lo:hi]), 2))
    points = [{'x': i, 'y': rolling[i]} for i in range(len(depths))]

    direction = 'better' if effect > 0 else 'worse'
    verb = 'has been improving' if effect > 0 else 'has been regressing'
    paragraph = (
        'The most recent %d attempts averaged %.1f pieces, against %.1f for '
        'the %d before them: the solver %s by %+.1f pieces over the '
        'history (z = %.1f).  This is a sign the tuner is (or is not) '
        'finding %s settings.' % (
            len(recent), _mean(recent), _mean(previous),
            len(previous), verb, effect, abs(z), direction))
    return [Finding(
        id='learning_trajectory',
        group='dynamics',
        title='The tuner %s %s settings over time' % (
            'is converging on' if effect > 0 else 'appears stuck with',
            'better' if effect > 0 else 'no better'),
        paragraph=paragraph,
        strength=abs(z),
        support=len(depths),
        visual={'kind': 'line', 'points': points,
                'xlabel': 'attempt (oldest -> newest)',
                'ylabel': 'rolling average depth'},
        rule=None,
    )]


def _time_to_depth(attempts, db):
    """Typical search steps needed to first reach depths {50, 100, ...}."""
    milestones = [50, 100, 150, 200]
    # sample the most recent attempts for which we have samples
    ids = [a['id'] for a in attempts[-200:] if a.get('id')]
    needed = defaultdict(list)
    any_loaded = 0
    for attempt_id in ids:
        samples = db.samples_for_attempt(attempt_id)
        if not samples:
            continue
        any_loaded += 1
        for m in milestones:
            for (ms, nodes, best) in samples:
                if best >= m:
                    needed[m].append(int(nodes))
                    break
    if any_loaded < MIN_SUPPORT:
        return []

    points = []
    for m in milestones:
        vs = needed.get(m) or []
        if len(vs) >= max(3, MIN_SUPPORT // 2):
            vs.sort()
            median = vs[len(vs) // 2]
            points.append({'x': m, 'y': median, 'n': len(vs),
                           'p25': vs[len(vs) // 4],
                           'p75': vs[(3 * len(vs)) // 4]})
    if len(points) < 2:
        return []

    # strength: log-ratio of steepest segment to shallowest segment
    log_steps = [math.log10(p['y']) for p in points]
    diffs = [log_steps[i] - log_steps[i - 1] for i in range(1, len(log_steps))]
    if not diffs:
        return []
    ratio = max(diffs) / (min(diffs) + EPS)
    z = math.log10(max(1.01, ratio)) * 4.0   # scale so typical 10x gap -> 4
    if z < MIN_STRENGTH:
        return []

    paragraph = (
        'Typical number of search steps to first reach these depths, '
        'estimated from %d recent attempts: the first half of the board '
        'goes down cheaply; the last few dozen pieces each cost an order '
        'of magnitude more work than the one before.' % any_loaded)
    return [Finding(
        id='time_to_depth',
        group='dynamics',
        title='Each extra piece costs more than the one before',
        paragraph=paragraph,
        strength=z,
        support=any_loaded,
        visual={'kind': 'line', 'points': points,
                'logy': True,
                'xlabel': 'depth reached',
                'ylabel': 'median search steps to get there'},
        rule=None,
    )]


def _cell_hotness(attempts, db):
    """Which cells are filled by the deepest attempts but not by shallow ones?

    A 16x16 heatmap.  Cells that appear in nearly every deep attempt are
    "easy"; cells that rarely appear are the ones the search struggles to
    keep covered, which is where any future improvement has to land.
    """
    if len(attempts) < 2 * MIN_SUPPORT:
        return []
    ordered = sorted(attempts, key=lambda a: _score_of(a))
    top_n = max(5, len(ordered) // 4)
    bot_n = top_n
    top_attempts = ordered[-top_n:]
    bot_attempts = ordered[:bot_n]

    top_ids = [a['id'] for a in top_attempts if a.get('id')]
    bot_ids = [a['id'] for a in bot_attempts if a.get('id')]
    top_counts = db.cell_fill_counts(top_ids)
    bot_counts = db.cell_fill_counts(bot_ids)
    if not top_counts or not bot_counts or not top_ids or not bot_ids:
        return []

    values = []
    deltas = []
    for cell in range(256):
        t = top_counts.get(cell, 0) / max(1, len(top_ids))
        b = bot_counts.get(cell, 0) / max(1, len(bot_ids))
        values.append({'cell': cell, 'top': round(t, 3), 'bot': round(b, 3),
                       'delta': round(t - b, 3)})
        deltas.append(t - b)

    # strength: largest positive delta scaled by sqrt(n)
    best_delta = max(deltas) if deltas else 0.0
    # approximate stderr for a proportion difference
    se = math.sqrt(0.5 * 0.5 * (1 / len(top_ids) + 1 / len(bot_ids))) + EPS
    z = best_delta / se
    if z < MIN_STRENGTH or best_delta < 0.2:
        return []

    # simple count of "cold cells" - filled by deepest less than half the time
    cold_cells = sum(1 for v in values if v['top'] < 0.5)
    paragraph = (
        'The deepest %d attempts agree on most of the board: %d of the 256 '
        'cells are filled in more than half of them.  The remaining cells '
        '(highlighted dark on the heatmap) are where the search loses '
        'momentum, and where a breakthrough would have to start.' % (
            len(top_ids), 256 - cold_cells))
    return [Finding(
        id='cell_hotness',
        group='board',
        title='The hard cells cluster in one region of the board',
        paragraph=paragraph,
        strength=z,
        support=len(top_ids) + len(bot_ids),
        visual={'kind': 'heatmap', 'n': 16, 'values': values,
                'metric': 'top'},
        rule=None,
    )]


def _placement_signature(attempts, db):
    """Do the deepest attempts fill the border early?

    For the top quartile vs bottom quartile, compute how many of the first 60
    placements land on a border cell.  A large gap between the two is a
    clear "start by finishing the frame" signal.
    """
    if len(attempts) < 2 * MIN_SUPPORT:
        return []

    ordered = sorted(attempts, key=lambda a: _score_of(a))
    top_n = max(5, len(ordered) // 4)
    top_ids = [a['id'] for a in ordered[-top_n:] if a.get('id')]
    bot_ids = [a['id'] for a in ordered[:top_n] if a.get('id')]
    top_frac = _border_fraction(db, top_ids)
    bot_frac = _border_fraction(db, bot_ids)
    if top_frac is None or bot_frac is None:
        return []
    top_vals, bot_vals = top_frac, bot_frac
    if len(top_vals) < MIN_SUPPORT // 2 or len(bot_vals) < MIN_SUPPORT // 2:
        return []
    effect, z = _welch_z(top_vals, bot_vals)
    if abs(z) < MIN_STRENGTH:
        return []

    bars = [
        {'label': 'top quartile', 'value': round(_mean(top_vals), 3),
         'n': len(top_vals), 'best': effect > 0},
        {'label': 'bottom quartile', 'value': round(_mean(bot_vals), 3),
         'n': len(bot_vals), 'best': effect < 0},
    ]
    verb = 'start by placing' if effect > 0 else 'spread out more'
    paragraph = (
        'Deep attempts %s a higher fraction of border tiles in their '
        'first 60 placements than shallow ones do (%.0f%% vs %.0f%%, '
        'difference %+.0f%%, z = %.1f).  In practice that means the most '
        'successful runs finish the frame before venturing inward.' % (
            verb, 100 * _mean(top_vals), 100 * _mean(bot_vals),
            100 * effect, abs(z)))
    return [Finding(
        id='placement_signature',
        group='board',
        title='Successful runs build the frame first',
        paragraph=paragraph,
        strength=abs(z),
        support=len(top_vals) + len(bot_vals),
        visual={'kind': 'bars', 'items': bars,
                'xlabel': 'border tiles in first 60 placements',
                'unit': 'fraction'},
        rule=None,
    )]


def _border_fraction(db, attempt_ids):
    """For each attempt, fraction of its first 60 placements that are border cells."""
    BORDER = set()
    for r in range(16):
        for c in range(16):
            if r == 0 or r == 15 or c == 0 or c == 15:
                BORDER.add(r * 16 + c)
    out = []
    for aid in attempt_ids:
        placements = db.placements_for_attempt(aid)
        if not placements or len(placements) < 5:
            continue
        first = placements[:60]
        on_border = sum(1 for (cell, _p, _r) in first if cell in BORDER)
        out.append(on_border / len(first))
    return out if out else None


def _outlier_attempts(attempts):
    """Attempts whose depth is unusually far from what their config predicts.

    Uses the simple baseline of "mean depth at this nodeBudget".  Attempts
    more than 2.5 standard deviations above it are celebrated as anomalies
    worth inspecting; large negative outliers are reported too so a user
    noticing "why did that fail?" has somewhere to start.
    """
    if len(attempts) < 2 * MIN_SUPPORT:
        return []
    by_budget = defaultdict(list)
    for a in attempts:
        by_budget[_budget_of(a)].append(a)

    stars = []
    for budget, group in by_budget.items():
        depths = [_score_of(a) for a in group]
        if len(depths) < 4:
            continue
        mu = _mean(depths)
        sd = math.sqrt(_var(depths)) or EPS
        for a in group:
            z = (_score_of(a) - mu) / sd
            if abs(z) >= 2.0:          # classical outlier threshold
                stars.append((z, a))
    if not stars:
        return []
    stars.sort(key=lambda za: -abs(za[0]))
    stars = stars[:8]
    items = [{
        'id': a['id'], 'depth': int(_score_of(a)),
        'z': round(z, 2), 'nodes': a.get('nodes'),
        'budget': (a.get('config') or {}).get('nodeBudget'),
        'userDefined': a.get('userDefined'),
    } for z, a in stars]
    best_z = abs(stars[0][0])
    paragraph = (
        'These attempts scored unusually far from the average for their '
        'attempt length: good anomalies point at configurations worth '
        'understanding, bad ones at likely dead ends.  The most extreme is '
        'attempt #%d at %.1f standard deviations from the mean for its '
        'budget.' % (stars[0][1]['id'], best_z))
    return [Finding(
        id='outlier_attempts',
        group='dynamics',
        title='A handful of attempts sit far from the pack',
        paragraph=paragraph,
        strength=best_z,
        # support is the attempts this finding was computed over, NOT the
        # number of outliers found - otherwise the MIN_SUPPORT filter above
        # drops any "found two outliers in 1000 runs" result.
        support=len(attempts),
        visual={'kind': 'outliers', 'items': items},
        related=[a['id'] for _, a in stars],
        rule=None,
    )]
