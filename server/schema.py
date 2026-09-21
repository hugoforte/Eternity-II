"""Single source of truth for every tunable solver option.

The UI builds its controls from this schema (served at /api/schema), the tuner
treats each entry as a bandit arm set, and the supervisor turns values into
``--key=value`` arguments for the Java engine.  Adding a knob in one place makes
it appear in all three.

Field meanings
--------------
key          argument name understood by core.SolverConfig
label        short human label for the UI
kind         'enum' | 'bool' | 'int' | 'scale'
options      for 'enum': list of {value, label, blurb}
values       for 'scale': explicit ordered list of allowed numbers
min/max/step for 'int'
default      the cold-start value (what we measured as best)
group        UI grouping
blurb        one-line explanation
low/high     what happens at each extreme (shown under the slider)
tunable      whether the learner is allowed to change it
activeWhen   {'key', 'values'}: this setting only reaches the search when the
             named setting holds one of those values.  A setting without it is
             always active.  The learner credits a setting only for attempts it
             could actually have influenced, so these have to match what the
             engines read.

A note on ``engine``
--------------------
Cell-choice and pruning settings belong to the most-constrained engine and are
dead weight under the fixed-scan one, so they say so.  Piece order and restarts
reach both, because the fixed scan reads its seed there too -- without them it
runs one descent and repeats it however long the attempt lasts.  The condition
is a single level deep: ``hybridThreshold`` depends on ``cellOrder``, which in
turn depends on ``engine``, and ``is_active`` does not follow that chain.
"""

SETTINGS = [
    # -------------------------------------------------------------- engine
    {
        "key": "engine",
        "label": "Engine",
        "kind": "enum",
        "group": "Search strategy",
        "default": "scan",
        "tunable": True,
        "blurb": "Which search engine runs the attempt. They obey different settings.",
        "options": [
            {"value": "mrv", "label": "Most-constrained",
             "blurb": "Picks the hardest square each step and prunes hard. ~1.4M steps/sec."},
            {"value": "scan", "label": "Fixed scan",
             "blurb": "Fills a fixed order with a precomputed candidate table. ~50M steps/sec, and reaches further. Deterministic for a given seed, so a fresh seed each attempt is what makes repeats differ."},
        ],
    },
    {
        "key": "fillOrder",
        "label": "Fill order",
        "kind": "enum",
        "group": "Search strategy",
        "default": "banded",
        "tunable": True,
        "activeWhen": {"key": "engine", "values": ["scan"]},
        "blurb": "Fixed scan only: the route the solver takes across the board.",
        "options": [
            {"value": "banded", "label": "Banded",
             "blurb": "Row scan, then a narrowed scan, then the bottom band column by column, then nested L-shapes. A mistake surfaces sooner where the search is deepest."},
            {"value": "rowMajor", "label": "Plain rows",
             "blurb": "Straight left-to-right, top-to-bottom. Simplest route, longest wait before a mistake shows."},
        ],
    },
    {
        "key": "slipSchedule",
        "label": "Edge slipping",
        "kind": "enum",
        "group": "Search strategy",
        "default": "verhaard",
        "tunable": True,
        "activeWhen": {"key": "engine", "values": ["scan"]},
        "blurb": "Fixed scan only: how many edges the solver may leave deliberately mismatched, and from how deep into the board.",
        "options": [
            {"value": "none", "label": "Never",
             "blurb": "Every placed edge must match. Exact, but the board can never score above the best perfect start it happens to find."},
            {"value": "blackwood", "label": "Blackwood",
             "blurb": "One mismatch allowed from square 201, rising to ten by square 239. The published schedule behind the best known result."},
            {"value": "verhaard", "label": "Verhaard",
             "blurb": "Starts at square 193 and allows twelve by square 240. More generous, and measured further on this engine."},
        ],
    },

    {
        "key": "quotaSchedule",
        "label": "Colour quota",
        "kind": "enum",
        "group": "Search strategy",
        "default": "none",
        "tunable": True,
        "activeWhen": {"key": "engine", "values": ["scan"]},
        "blurb": "Fixed scan only: make the solver spend three chosen colours early, and abandon any line of play that falls behind.",
        "options": [
            {"value": "none", "label": "Never",
             "blurb": "No quota. The solver spends the colours whenever they happen to fit."},
            {"value": "blackwood", "label": "Blackwood",
             "blurb": "The published ramp: 28 of those sides down by square 26, rising to 119 by square 160. Measured here as far too demanding for this board's route across it -- it stalls the search around square 70."},
        ],
    },
    {
        "key": "quotaColours",
        "label": "Quota colours",
        "kind": "enum",
        "group": "Search strategy",
        "default": "13,16,10",
        "tunable": True,
        "activeWhen": {"key": "quotaSchedule", "values": ["blackwood"]},
        "blurb": "Which three colours the quota counts: one border colour and two interior ones. Ranked by how much room the ramp leaves them on our piece table.",
        "options": [
            {"value": "13,16,10", "label": "Blackwood's",
             "blurb": "His three colour numbers read as ours. One border colour and two interior ones, as he described, and 2 sides clear of impossible."},
            {"value": "2,9,12", "label": "Most room",
             "blurb": "The roomiest of all 680 triples on this piece table, and still only 4 sides clear of impossible."},
            {"value": "3,9,12", "label": "Second roomiest",
             "blurb": "Same two interior colours, a different border one."},
            {"value": "13,9,12", "label": "Third roomiest",
             "blurb": "Same again, on Blackwood's border colour."},
        ],
    },

    # ------------------------------------------------------------ strategy
    {
        "key": "cellOrder",
        "label": "Cell order",
        "kind": "enum",
        "group": "Search strategy",
        "default": "mrv",
        "tunable": True,
        "activeWhen": {"key": "engine", "values": ["mrv"]},
        "blurb": "How the solver picks which empty square to fill next.",
        "options": [
            {"value": "mrv", "label": "Most constrained",
             "blurb": "Always fill the square with the fewest legal pieces. Strongest pruning."},
            {"value": "hybrid", "label": "Hybrid",
             "blurb": "Most-constrained while choices are few, then sweep in order."},
            {"value": "rowMajor", "label": "Row by row",
             "blurb": "Simple left-to-right, top-to-bottom sweep. Cheapest per step."},
        ],
    },
    {
        "key": "hybridThreshold",
        "label": "Hybrid switch point",
        "kind": "int",
        "group": "Search strategy",
        "min": 1, "max": 64, "step": 1,
        "default": 4,
        "tunable": True,
        "activeWhen": {"key": "cellOrder", "values": ["hybrid"]},
        "blurb": "Hybrid mode only: use most-constrained while the best square has at most this many choices.",
        "low": "1 = almost always sweep in order",
        "high": "64 = almost always most-constrained",
    },
    {
        "key": "tieBreak",
        "label": "Tie breaker",
        "kind": "enum",
        "group": "Search strategy",
        "default": "mostNeighbours",
        "tunable": True,
        "activeWhen": {"key": "cellOrder", "values": ["mrv", "hybrid"]},
        "blurb": "Which square wins when several are equally constrained.",
        "options": [
            {"value": "mostNeighbours", "label": "Most neighbours",
             "blurb": "Prefer squares already surrounded. Keeps the filled area compact."},
            {"value": "fewestNeighbours", "label": "Fewest neighbours",
             "blurb": "Prefer isolated squares. Spreads the search out."},
            {"value": "lowestIndex", "label": "Top-left first",
             "blurb": "Plain reading order."},
            {"value": "nearestFixed", "label": "Near the fixed piece",
             "blurb": "Grow outwards from the mandatory piece 139."},
            {"value": "nearestCentre", "label": "Near the centre",
             "blurb": "Grow outwards from the middle of the board."},
        ],
    },
    {
        "key": "startCell",
        "label": "Opening move",
        "kind": "enum",
        "group": "Search strategy",
        "default": "auto",
        "tunable": True,
        "activeWhen": {"key": "engine", "values": ["mrv"]},
        "blurb": "Which square the solver is forced to fill first.",
        "options": [
            {"value": "auto", "label": "Let it choose", "blurb": "No constraint on the opening move."},
            {"value": "topLeft", "label": "Top-left corner", "blurb": ""},
            {"value": "topRight", "label": "Top-right corner", "blurb": ""},
            {"value": "bottomLeft", "label": "Bottom-left corner", "blurb": ""},
            {"value": "bottomRight", "label": "Bottom-right corner", "blurb": ""},
            {"value": "centre", "label": "Centre", "blurb": ""},
        ],
    },

    # --------------------------------------------------------- piece choice
    {
        "key": "valueOrder",
        "label": "Piece order",
        "kind": "enum",
        "group": "Piece choice",
        "default": "natural",
        "tunable": True,
        "activeWhen": {"key": "engine", "values": ["mrv", "scan"]},
        "blurb": "The order in which candidate pieces are tried in a square. This is the only thing the seed changes on the fixed scan.",
        "options": [
            {"value": "natural", "label": "Natural",
             "blurb": "Piece number order. Deterministic and cache friendly."},
            {"value": "reverse", "label": "Reversed",
             "blurb": "Highest piece number first. Deterministic, and a second descent for free."},
            {"value": "random", "label": "Shuffled",
             "blurb": "Random order from the seed. Pairs well with restarts, and is what lets the fixed scan give a second opinion."},
            {"value": "rarestColour", "label": "Rarest colours first",
             "blurb": "Try pieces whose colours are scarce, to spend rare pieces early. Most-constrained only; the fixed scan reads it as Natural."},
        ],
    },
    {
        "key": "shuffleStrength",
        "label": "Shuffle strength",
        "kind": "int",
        "group": "Piece choice",
        "min": 0, "max": 100, "step": 5,
        "default": 0,
        "tunable": True,
        "activeWhen": {"key": "engine", "values": ["mrv", "scan"]},
        "blurb": "How much randomness is mixed into the piece order. On the fixed scan it is the share of squares whose order is scrambled; the rest keep theirs.",
        "low": "0 = keep the chosen order exactly",
        "high": "100 = fully scrambled every time",
    },
    {
        "key": "candidateCap",
        "label": "Choices per square",
        "kind": "scale",
        "group": "Piece choice",
        "values": [1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 256, 512, 1024],
        "default": 1024,
        "tunable": True,
        "activeWhen": {"key": "engine", "values": ["mrv"]},
        "blurb": "Maximum number of pieces tried in any one square before giving up on it.",
        "low": "1 = greedy. Blisteringly fast, can never find a full solution",
        "high": "1024 = try everything. Complete search",
    },

    # -------------------------------------------------------------- pruning
    {
        "key": "forwardCheck",
        "label": "Look ahead",
        "kind": "enum",
        "group": "Pruning",
        "default": "fullBoard",
        "tunable": True,
        "activeWhen": {"key": "engine", "values": ["mrv"]},
        "blurb": "How hard the solver looks for dead ends before committing.",
        "options": [
            {"value": "none", "label": "None", "blurb": "Never look ahead. Maximum speed per step, worst pruning."},
            {"value": "neighbours", "label": "Neighbours",
             "blurb": "Check the squares next to the piece just placed."},
            {"value": "fullBoard", "label": "Whole board",
             "blurb": "Backtrack as soon as any empty square has no legal piece."},
        ],
    },
    {
        "key": "greyInteriorPruning",
        "label": "Grey edge pruning",
        "kind": "bool",
        "group": "Pruning",
        "default": True,
        "tunable": True,
        "blurb": "Forbid grey (border) edges in the middle of the board. Always safe: the piece set has exactly enough grey edges for the border.",
        "low": "off = more candidates to sift through",
        "high": "on = fewer candidates, same solutions",
    },

    # ------------------------------------------------------------- restarts
    {
        "key": "restartPolicy",
        "label": "Restart policy",
        "kind": "enum",
        "group": "Restarts",
        "default": "none",
        "tunable": True,
        "activeWhen": {"key": "engine", "values": ["mrv", "scan"]},
        "blurb": "Abandon and restart the search to escape an unlucky early choice. A restart re-draws the piece order, so it does nothing unless there is randomness to re-draw.",
        "options": [
            {"value": "none", "label": "Never", "blurb": "One long search."},
            {"value": "fixed", "label": "Fixed", "blurb": "Restart every N nodes."},
            {"value": "geometric", "label": "Geometric", "blurb": "Each run is longer than the last by a fixed factor."},
            {"value": "luby", "label": "Luby", "blurb": "The classic 1,1,2,1,1,2,4,... schedule. Robust against heavy-tailed runtimes."},
        ],
    },
    {
        "key": "restartBase",
        "label": "Restart interval",
        "kind": "scale",
        "group": "Restarts",
        "values": [1000, 5000, 10000, 50000, 100000, 500000, 1000000, 5000000],
        "default": 100000,
        "tunable": True,
        "activeWhen": {"key": "restartPolicy", "values": ["fixed", "geometric", "luby"]},
        "blurb": "Nodes in the first run before the first restart.",
        "low": "1k = restart constantly, never goes deep",
        "high": "5M = restarts almost never happen",
    },
    {
        "key": "restartMultiplier",
        "label": "Restart growth",
        "kind": "int",
        "group": "Restarts",
        "min": 110, "max": 400, "step": 10,
        "default": 150,
        "tunable": True,
        "activeWhen": {"key": "restartPolicy", "values": ["geometric"]},
        "blurb": "Geometric policy only: each run is this much longer than the last (percent).",
        "low": "110% = barely grows, many short runs",
        "high": "400% = runs get long very quickly",
    },

    # -------------------------------------------------------------- attempt
    {
        "key": "nodeBudget",
        "label": "Attempt length",
        "kind": "scale",
        "group": "Attempt",
        "values": [100000, 250000, 500000, 1000000, 2000000,
                   5000000, 10000000, 25000000, 50000000, 100000000],
        "default": 5000000,
        "tunable": True,
        "blurb": "How many search steps one attempt gets before the board resets and a new attempt begins.",
        "low": "100k = very short attempts, lots of history",
        "high": "100M = long attempts that dig deep",
    },
    {
        "key": "randomSeed",
        "label": "Random seed",
        "kind": "int",
        "group": "Attempt",
        "min": 0, "max": 999999, "step": 1,
        "default": 12345,
        "tunable": False,
        "blurb": "Apply & restart reproduces a run exactly with this seed for its first attempt. Every later attempt, pinned or learner-chosen, gets a fresh one.",
        "low": "0",
        "high": "999999",
    },
]

BY_KEY = {s["key"]: s for s in SETTINGS}

GROUP_ORDER = ["Search strategy", "Piece choice", "Pruning", "Restarts", "Attempt"]


def defaults():
    """Cold-start configuration: what measurement said was best."""
    return {s["key"]: s["default"] for s in SETTINGS}


def arms(setting):
    """Discrete choices the learner may pick from for one setting."""
    kind = setting["kind"]
    if kind == "enum":
        return [o["value"] for o in setting["options"]]
    if kind == "bool":
        return [True, False]
    if kind == "scale":
        return list(setting["values"])
    if kind == "int":
        lo, hi, step = setting["min"], setting["max"], setting.get("step", 1)
        vals = list(range(lo, hi + 1, max(1, step)))
        # Keep the arm count manageable for the bandit, but never drop the
        # default: the learner has to be able to choose the value the UI starts
        # on, otherwise "optimal" could never be reproduced.
        if len(vals) > 12:
            idx = [round(i * (len(vals) - 1) / 11) for i in range(12)]
            vals = [vals[i] for i in sorted(set(idx))]
        default = setting.get("default")
        if default is not None and default not in vals:
            vals.append(default)
            vals.sort()
        return vals
    return [setting["default"]]


def tunable_settings():
    return [s for s in SETTINGS if s.get("tunable", True)]


def is_active(key, config):
    """Could this setting have changed what an attempt using ``config`` did?

    A conditional setting (``activeWhen``) is dead weight unless the setting it
    depends on holds one of the listed values: ``restartMultiplier`` never
    reaches the search unless the policy is geometric, for example.  Crediting
    an arm for attempts it could not influence is how noise gets promoted to a
    conclusion, so every per-setting statistic asks this first.
    """
    setting = BY_KEY.get(key)
    if setting is None:
        raise KeyError("no such setting: %r" % (key,))
    dependency = setting.get("activeWhen")
    if dependency is None:
        return True
    # A config that omits the dependency ran with whatever the engine defaults
    # to, which is what coerce() returns for a missing value.
    actual = coerce(dependency["key"], (config or {}).get(dependency["key"]))
    return actual in dependency["values"]


def coerce(key, value):
    """Force a value coming from the browser into the right type and range."""
    s = BY_KEY.get(key)
    if s is None:
        return None
    kind = s["kind"]
    try:
        if kind == "enum":
            allowed = [o["value"] for o in s["options"]]
            return value if value in allowed else s["default"]
        if kind == "bool":
            if isinstance(value, bool):
                return value
            return str(value).lower() in ("1", "true", "yes", "on")
        if kind == "scale":
            v = int(value)
            allowed = s["values"]
            # snap to the nearest allowed step
            return min(allowed, key=lambda a: abs(a - v))
        if kind == "int":
            v = int(value)
            v = max(s["min"], min(s["max"], v))
            return v
    except (TypeError, ValueError):
        return s["default"]
    return s["default"]


def coerce_config(raw):
    """Build a complete, valid config from a partial dict."""
    cfg = defaults()
    if isinstance(raw, dict):
        for key, value in raw.items():
            if key in BY_KEY:
                cfg[key] = coerce(key, value)
    return cfg


def to_engine_args(cfg):
    """Turn a config dict into engine command-line arguments."""
    args = []
    for key, value in cfg.items():
        if key not in BY_KEY:
            continue
        if isinstance(value, bool):
            value = "true" if value else "false"
        args.append("--%s=%s" % (key, value))
    return args


def same_config(a, b):
    """Compare two configs ignoring the random seed (which always varies)."""
    for s in SETTINGS:
        if not s.get("tunable", True):
            continue
        if a.get(s["key"]) != b.get(s["key"]):
            return False
    return True
