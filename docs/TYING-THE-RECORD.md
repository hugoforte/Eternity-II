# Tying the record

This engine places 250 of 256 pieces and matches 456 of 480 edges. The world record is 470. The
arithmetic is easy and the conclusion it invites — *fourteen edges short* — is wrong in a specific
way, and the way it is wrong changes what anyone should do next.

This document is the honest version. Every number in it is either quoted from a source, measured
here, or computed from quoted inputs with the arithmetic shown. Where a thing is not known, it says
so. The research behind it lives in `~\ai-notes\notes\hugoforte\Eternity-II\`, five files with
inline citations on every claim; this is the argument, not the evidence.

## 1. Where this engine stands

| | |
|---|---|
| Best board | **250 / 256 pieces, 456 / 480 matched edges** |
| Engine | fixed-order scan, two-colour candidate index, Verhaard's slip schedule |
| Throughput | ~40M nodes/sec, one core |
| Published reference | Blackwood's own solver: 248 pieces / 454 edges in 60-120 s on one Apple M1 core |

Against the published reference this is **rough parity, marginally behind per unit time**. That is a
real place to be — it is roughly where a well-built single-machine engine lands, and the academic
literature's best result on the real piece set in nineteen years is 461 — but it is not a record
attempt, and no claim in this repository should imply it is.

The puzzle has never been solved by anyone since it shipped in 2007. The $2,000,000 prize expired
unclaimed in 2010.

## 2. The record is not a score. It is a completion

This is the finding that reorganises everything else, and it comes from reading the record-holding
solver's source rather than a description of it.

Blackwood's engine allows a bounded number of mismatched edges, on a schedule indexed by depth:

```csharp
private static List<int> break_indexes_allowed =
    new List<int>() { 201, 206, 211, 216, 221, 225, 229, 233, 237, 239 };
```

**Ten entries.** It is a *cumulative ceiling*, not a quota — at most one break per placed piece,
never on a border colour, and nothing obliges the search to spend the allowance. So:

> **A run under that schedule which completes all 256 placements scores 470 or better, by
> construction.** It cannot produce a 469 as a completion. A run that finishes having spent nine of
> its ten breaks is a 471.

Everything below 470 that his engine produces is a *partial* board, which something else has to fill
in and score; his own code saves any that reach depth 252. The 248-piece board his solver is always
benchmarked at, which re-scores to 454 edges, is one of those.

**There is therefore no ladder from 456 to 470.** They are not fourteen rungs of one staircase; they
are outputs of two different devices. A search that climbs a score function and a search that runs
until it completes under a ten-break ceiling are not the same search made better. The question
"how do we get fourteen more edges" has no answer because it is not the question the record answers.

The right question is one number:

> **P — the probability that one attempt, under Blackwood's published configuration, completes.**

The cost of a 470 is then `1/P × 50 × 10⁹` nodes, because his engine caps each attempt at fifty
billion nodes and restarts.

## 3. What a 470 has cost, the one time anybody said

**One figure exists in the world.** It is Blackwood's own, and it reaches us second-hand: a 2024
community Discord message, relayed by eternity2.dev, saying the 470 took *about a month on a home
Threadripper 3970X*. The Discord has no public archive. That sentence is the entire empirical basis
for the cost of a world record.

Converted: 32 cores × 720 hours = **23,040 core-hours**, roughly 10¹⁵ nodes, roughly **4 × 10⁴
attempts** at his fifty-billion-node cap. So P ≈ 2.5 × 10⁻⁵ — from a sample of one.

**One observation is not a mean, and this distribution has a tail.** Under the mildest heavy-tail
assumption — that the waiting time is exponential — a single observation `x` gives a 90% interval
for the mean of `[x/2.996, x/0.0513]`:

| | |
|---|---|
| Point estimate | 23,000 core-hours |
| 90% interval on the **mean** | **7,700 to 449,000 core-hours** |

Every piece of evidence in the research says the tail is heavier than exponential, which pushes the
mean *higher* than that and the median lower. A month on 32 cores is a plausible lucky win and a
wholly implausible plan.

**The strongest corroboration is the silence.** The open track has stood at 470 for five and a half
years. Jef Bucas tied it in December 2024 with a fleet plausibly one to two orders of magnitude
larger, and tied it — did not beat it. Because the schedule is a ten-break ceiling, a 471 would come
out of
exactly the same runs that produce 470s. That nobody has one, in five and a half years of people
running this exact engine, is the clearest available evidence that Blackwood's month sat well below
the mean.

## 4. Why the supply curve does not answer the question

There is a real, measured curve for how the boards thin out near the top. The community fleet
publishes live telemetry, and as of 21 September 2026 its validated board counts are:

| score | boards found | ratio to the next |
|---|---|---|
| 463 | 53,244 | — |
| 464 | 2,221 | 24.0× |
| 465 | 63 | 35.3× |
| 466 | 1 | 63.0× |

The ratio is not constant — it grows, ×1.47 then ×1.79 per step. Extending it four more edges to 470
gives a factor of about 2.5 × 10⁸, and **that number is worthless**, for a reason the primary
sources state outright. Verhaard, who held the record in 2008:

> It turns out that the optimal search order **depends on the score you want to achieve**. For
> example if you want to go for 480, a scan row is quite optimal, but it is far from optimal if you
> want to achieve a score of 468 or lower.

A supply ratio measured on a configuration tuned for target *s* does not transfer to a configuration
retuned for *s+1*. Each rung is climbed by a differently-shaped search.

**The contradiction, made explicit.** Verhaard needed ~1.2 × 10¹⁶ nodes for a 468. Laddering his own
ratios to 470 gives ~1.8 × 10²⁰ nodes. Blackwood did it in ~1.6 × 10¹⁵ — a factor of **1.1 × 10⁵**
apart. That is either an implausible single-generation engine gain or, far more likely, proof that
the ladder is not a ladder. The 2020 schedule is not Verhaard's 2008 search made faster. It is a
differently-shaped search aimed directly at 470.

> **The rule to carry forward: any cost model for this puzzle must state which configuration it is a
> cost model *for*.**

This repository has already paid for ignoring that once. An earlier extrapolation here predicted a
complete board in sixteen days; it was wrong by about ninety times, because it fitted a constant
geometric ratio to four improvement points spanning ninety seconds. The failure mode is not bad
arithmetic. It is arithmetic applied across a boundary it does not cross.

## 5. The community fleet is solving a different puzzle

It is tempting to read "there is a live distributed search and it takes contributed cores" as a
route to a record. It is not, and the reason is structural rather than statistical.

The fleet runs a **five-clue** configuration. Its break schedule has fourteen entries and its own log
line names the target: `target completion score 466`. A board that engine produces is a five-clue
board, on a track whose record is 466. It cannot become an open-track 470.

It is also, at this instant, not really a fleet. Live: 48 machines, 3,163 threads — of which **2,953,
or 93.4%, are one person's**. Sixteen contributed threads run for a year would be expected to yield
about 5,200 boards at 463, six at 465, and **0.10** at 466. A 467 on that track costs somewhere
between three and eight *whole-fleet-years*.

It is a real and unusually honest scientific effort, with live telemetry and a published ledger of
what failed. It is worth reading and worth contributing to. It is not a lottery ticket for a record,
and it is not aimed at the record we are talking about.

## 6. What this project got back this week

One of the six techniques the previous work refuted was marked *mis-transcribed rather than
disproven*: Blackwood's colour-quota gate, measured here as arithmetically unsatisfiable by any
colour triple, against his published claim of ~2× on this same piece set. A rule that no triple can
satisfy cannot be the rule that gave him 2×, so something was wrong with the reading.

Reading his C# settled it, and the mis-transcription was **ours**.

His `heuristic_sides = { 13, 16, 10 }` are indices into *his* piece table, which numbers the border
colours 1, 5, 9, 13, 17. Ours numbers them 1, 2, 3, 13, 14. Both files are transcriptions of the same
physical puzzle under different labelling conventions, and the relabelling is recoverable: refine
colours by how often they sit adjacent to and opposite each other on a piece, and all 23 signature
classes come out as singletons, so the mapping is forced. Applying it to his 256 pieces reproduces
our piece multiset exactly.

**His `{13, 16, 10}` is our `{14, 22, 5}`.**

Reading his numbers as ours gives a triple of the same *shape* — one border colour, two interior,
122 sides — which is exactly why the mistake survived. What it does not give is the property he
chose them for, and remarked on in his own source: *"there is a lot of overlap between these sides"*.
Three pieces carry all three of his colours. **No piece carries all three of the ones we ran.**

Everything else had been transcribed correctly: the ramp, its five slopes, the floor semantics, the
abandon-the-whole-scan behaviour, the randomisation within equal counts. Only the three numbers.

Measured on `main`, one core, `fillOrder=banded`, `slipSchedule=verhaard`:

| budget | quota off | quota on, `14,22,5` | quota on, `13,16,10` |
|---|---|---|---|
| 100M nodes | 245 / 446 | **248 / 452** | 55 / 90 |
| 1B nodes | 247 / 450 | **249 / 454** | 70 / 119 |
| 10B nodes | 249 / 454 | **250 / 456** | 73 / 125 |
| 600 s | 249 / 454 | **250 / 456** | — |

A piece and two edges at every budget, on wall-clock as well as on nodes. The last row matters most:
250 / 456 is the best board this project has recorded, and it previously took an overnight eight-arm
run — eight cores, six hours, about 48 core-hours. The gated engine reaches the same board on **one
core in six minutes**. That is roughly **500 times less work for the same board**.

**And a caveat that came out of checking this properly.** Ranking all 680 candidate triples by how
much room the ramp leaves them puts his three 403rd, and the ranking's own top pick, `1,7,10`, matches
them exactly at 249/454. So his triple is not uniquely good, and the ranking is not useless either —
it correctly rules out the 23 triples that no arrangement of pieces can satisfy, and says nothing at
all above that line, where three triples with identical slack land at 58, 246 and 249 pieces.

This lines up with the one thing Jef Bucas has published about his own 470: the two boards that
reached it used **different** heuristic triples. The triple is a parameter worth varying, not a secret
worth recovering. What reading the source recovered was the ramp — which is satisfiable, and was
written off as impossible on the strength of three wrong colours.

**What this does not do is move the 470 question.** It is an engine improvement in the mid-450s band,
on a device that climbs a score function. Section 2 is still true.

## 7. So what would it actually take

| Route | Cost | Verdict |
|---|---|---|
| Run Blackwood's published configuration | 23,000 core-hours at the point estimate; 449,000 at the pessimistic end | **The only route that has ever worked.** A 16-thread box for a year is ~3× his month |
| Contribute to the community fleet | any | **Wrong track.** Five clues, target 466 |
| Climb there with our own engine | — | **Not a route.** No ladder exists between a score-climbing search and a ten-break completion |
| Go after a solvable target instead | ~180 core-years bought the twin | Owen's hint-free 10×10 `set_2` is the community's agreed next rung, and is tagged as sized for a personal machine |

Three multipliers are worth more than hardware, and all three are measured rather than hoped for:

1. **Port the engine.** Blackwood's C# runs ~19M nodes/s per core; McGavin's C runs ~105M on hard
   boards. That is ~5× for engineering, demonstrated by two separate people. The fleet got a further
   1.74× from compile-time specialisation alone.
2. **Vary the colour triple, not the ramp.** The two known 470s used *different* heuristic triples,
   so `{13, 16, 10}` is not uniquely good — a portfolio over triples is free diversification. Bucas's
   own sweep found the hand-tuned ramp already sits in the best zone.
3. **Rank your roots before committing compute.** 2.9× more high-scoring boards from the best third
   of ranked roots, and a root's yield halves roughly every 10,000 tickets.

Compounding those is about 25×, which turns the pessimistic 449,000 core-hours into something nearer
18,000 — and is the only honest reason to think a tie is reachable by one person.

## 8. The one number nobody has measured

**P has never been measured or published.** It is the single quantity that decides whether any of
this is a project or a daydream, and it currently rests on one sentence relayed from an unarchived
Discord message.

Measuring it does not need a fleet. It needs one machine running the published configuration long
enough to count completions and, far more cheaply, to measure the *depth distribution* — how often an
attempt reaches 248, 250, 252 — which is what P is the tail of.

**Measured here, first-hand.** His solver builds and runs unchanged on .NET Core 3.1; the only edits
were his own README's "change the number of cores" and some counters. Eleven threads, 150 seconds:

| | |
|---|---|
| Aggregate throughput | **419M nodes/sec across 11 threads** |
| Per core | **38.1M nodes/sec** |
| Deepest placement reached in 150 s | **249 of 256** |

Two things follow. First, a calibration nobody had: **his C# engine and this project's Java engine
run at the same speed on the same machine** — 38.1M against 39.6M nodes/sec per core. The often-quoted
18.9M figure is a measurement on an Apple M1, not a property of his code. The gap between this project
and the record is not throughput.

Second, the price of the missing number. One attempt at his fifty-billion-node cap takes **22 minutes
of one core** at that rate. So:

- **100 attempts — enough to measure the depth distribution's tail — costs about 37 core-hours**,
  one overnight run on this machine. Nobody has published that distribution.
- **Measuring P itself needs on the order of 40,000 attempts**, which is ~15,000 core-hours. That is
  the same scale as the record attempt, because it is the same thing.

So the tractable version of the open question is not "measure P". It is "measure the depth
distribution and read P off its tail", and that is one night's work rather than a year's.

## 9. What would not be true to say

Kept explicit, because this repository's README is read by people who did not follow the argument.

- **That we are fourteen edges from the record.** We are one device short of the one that produces
  records, and fourteen edges is not the gap on any axis that matters.
- **That we beat the best published engine.** We are marginally behind it per unit time.
- **That more compute gets us there.** eternity2.net spent more than 10¹⁹ CPU operations and stopped
  in the mid-460s.
- **That a solve is within reach.** There are ~10³³ boards scoring 470 and about nineteen scoring
  480, with a sourced argument for an empty band between them. Nobody has crossed it in nineteen
  years, and the people who got closest say in print that they do not expect anyone to.
- **That the community fleet's record and this one are the same record.** Five-clue and open-track
  are different problems with different numbers. Watch for this error; published summaries make it
  regularly, and eternity2.dev's own front page has made it.
