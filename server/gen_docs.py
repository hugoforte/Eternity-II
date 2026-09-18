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

## A note on safety

No setting can break the rules of the puzzle. Board size, the piece set and the
mandatory hint piece (139 at row 8, column 7) are fixed, and every placement is
still checked for matching edges. The worst a setting can do is make the search
slow, or make it incomplete so it can never find a full solution --
`Choices per square = 1` is the clearest example, and it is labelled as such.

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
