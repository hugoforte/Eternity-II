"""Generate docs/SETTINGS.md from schema.py.

The settings reference is produced from the same definitions the UI and the
learner use, so the documentation can never drift out of step with the
application. Regenerate after changing schema.py:

    python3 server/gen_docs.py
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import schema  # noqa: E402

INTRO = """# Settings reference

Every strategic decision the solver used to make by hard-coding is exposed here
as a control you can change while it runs. Each attempt stores the exact values
it used, so the History tab always tells you what produced a given result.

**This file is generated from `server/schema.py` by `server/gen_docs.py`.**
Do not edit it by hand; change the schema and regenerate.

## How to read this

* **Default** is the cold-start value, chosen from the benchmarking described in
  `docs/SOLVER.md`. Before you touch anything, the UI shows these, and the
  "Use optimal" button returns to whatever the learner currently believes is
  best.
* **Extremes** describe what happens at each end of a slider. Both ends are
  reachable on purpose: part of the fun is watching a setting fail.
* **Learned** says whether the built-in learner is allowed to tune the value.
  The random seed is excluded, because it is noise rather than strategy.
* **Only has an effect when ...** marks a setting that depends on another one.
  Attempts where the condition did not hold are not counted as evidence about
  it, so what the Insights tab says about it is based on the runs it could
  really have changed.

## A note on safety

No setting can change the puzzle. Board size, the piece set and the mandatory
hint piece (139 at row 8, column 7) are fixed, every piece is used at most
once, and no setting can put a colour against the border.

What a setting can change is what counts as good enough. `Edge slipping` lets
the solver leave a bounded number of squares deliberately mismatched so it can
keep going where an exact search would have to back up; those boards are scored
honestly on matched edges, and a board with any mismatch is never reported as
solved. A setting can also make the search slow, or make it incomplete so it
can never find a full solution -- `Choices per square = 1` is the clearest
example, and it is labelled as such.

---

"""

OUTRO = """
---

## Combinations worth trying

| Goal | Try this |
|---|---|
| Deepest boards, slow and steady | Cell order **Most constrained**, Look ahead **Whole board**, Choices per square **unlimited**, Attempt length **25M+** |
| Maximum raw speed | Cell order **Row by row**, Look ahead **Neighbours**, Attempt length **500k** |
| Escape unlucky starts | Piece order **Shuffled**, Restart policy **Luby**, Restart interval **10k-100k** |
| See a search collapse | Choices per square **1** -- finishes in milliseconds and can never solve |
| See pruning matter | Look ahead **None** vs **Whole board**, everything else equal |
| Watch the cost of bad ordering | Tie breaker **Fewest neighbours** (scatters the search) vs **Most neighbours** |
| Reproduce a run exactly | Pin the same **Random seed** and all other values |

## Fair comparisons

Attempt length is itself a setting, and a 100k-step attempt obviously cannot
reach as deep as a 100M-step one. The learner therefore compares every other
setting only against attempts of the *same* length, and the Insights tab shows
that adjusted number ("pieces better / worse than a same-length run"). If you
want to compare two settings by hand, keep Attempt length fixed between them.
"""


def fmt_value(setting, value):
    if isinstance(value, bool):
        return "`on`" if value else "`off`"
    if setting["kind"] == "enum":
        for option in setting["options"]:
            if option["value"] == value:
                return "**%s** (`%s`)" % (option["label"], option["value"])
        return "`%s`" % value
    if setting["key"] == "restartMultiplier":
        return "`%s` (%.2fx)" % (value, value / 100.0)
    if isinstance(value, int) and value >= 1000:
        return "`%s`" % pretty_int(value)
    return "`%s`" % value


def option_label(setting, value):
    if setting["kind"] == "enum":
        for option in setting["options"]:
            if option["value"] == value:
                return option["label"]
    return str(value)


def fmt_dependency(setting):
    """The "this only matters when ..." line, or None for a plain setting."""
    dependency = setting.get("activeWhen")
    if dependency is None:
        return None
    other = schema.BY_KEY[dependency["key"]]
    values = ["**%s**" % option_label(other, v) for v in dependency["values"]]
    if len(values) > 1:
        values = ["%s or %s" % (", ".join(values[:-1]), values[-1])]
    return "Only has an effect when %s is %s." % (other["label"], values[0])


def pretty_int(value):
    if value >= 1_000_000 and value % 100_000 == 0:
        return "%gM" % (value / 1_000_000.0)
    if value >= 1000 and value % 100 == 0:
        return "%gk" % (value / 1000.0)
    return str(value)


def render():
    out = [INTRO]
    for group in schema.GROUP_ORDER:
        members = [s for s in schema.SETTINGS if s["group"] == group]
        if not members:
            continue
        out.append("## %s\n" % group)
        for setting in members:
            out.append("### %s\n" % setting["label"])
            out.append("`%s` &middot; %s &middot; %s\n" % (
                setting["key"],
                {"enum": "choice", "bool": "on/off", "int": "slider",
                 "scale": "stepped slider"}[setting["kind"]],
                "learned automatically" if setting.get("tunable", True)
                else "**not** tuned by the learner"))
            out.append("%s\n" % setting["blurb"])
            dependency = fmt_dependency(setting)
            if dependency:
                out.append("%s\n" % dependency)

            if setting["kind"] == "enum":
                out.append("| Choice | What it does |")
                out.append("|---|---|")
                for option in setting["options"]:
                    blurb = option.get("blurb") or ""
                    out.append("| **%s** | %s |" % (option["label"], blurb))
                out.append("")
            elif setting["kind"] == "bool":
                out.append("| Value | Effect |")
                out.append("|---|---|")
                out.append("| on | %s |" % (setting.get("high") or "enabled"))
                out.append("| off | %s |" % (setting.get("low") or "disabled"))
                out.append("")
            else:
                if setting["kind"] == "scale":
                    steps = ", ".join(pretty_int(v) for v in setting["values"])
                    out.append("Range: %s\n" % steps)
                else:
                    out.append("Range: `%s` to `%s` in steps of `%s`\n" % (
                        setting["min"], setting["max"], setting.get("step", 1)))
                if setting.get("low") or setting.get("high"):
                    out.append("| End of the slider | What happens |")
                    out.append("|---|---|")
                    if setting.get("low"):
                        out.append("| lowest | %s |" % setting["low"])
                    if setting.get("high"):
                        out.append("| highest | %s |" % setting["high"])
                    out.append("")

            out.append("Default: %s\n" % fmt_value(setting, setting["default"]))
    out.append(OUTRO)
    return "\n".join(out)


def main():
    target = os.path.join(ROOT, "docs", "SETTINGS.md")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    text = render()
    with open(target, "w", encoding="utf-8") as handle:
        handle.write(text)
    print("wrote %s (%d bytes, %d settings)" % (target, len(text), len(schema.SETTINGS)))


if __name__ == "__main__":
    main()
