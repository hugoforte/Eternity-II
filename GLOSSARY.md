# Eternity II Lab

A laboratory around an Eternity II solver. The puzzle is one fixed 16x16 edge-matching
instance. Nobody has solved it since 2007.

## Language

### Scoring

**Matched edge**:
An internal edge of the board where the two pieces beside it show the same colour. The board has
480 internal edges, and the count of matched ones is the only score this puzzle has.
_Avoid_: points, fitness, matches

**Break**:
An internal edge that the solver deliberately leaves mismatched so it can keep placing pieces. A
break costs one matched edge. An empty square costs two, so a break that buys a placement gains at
least one.
_Avoid_: slip, mismatch, error, violation

**Filled board**:
A board with a piece in all 256 squares. A filled board may still carry breaks, so it is not
necessarily a solution.
_Avoid_: complete board, completed board, finished board

**Solution**:
A filled board with zero breaks: all 256 pieces placed and all 480 edges matched.
_Avoid_: complete board, solved board, win

**Perfect prefix**:
A property of one board. The number of pieces from the start of the fill order up to its first
break.
_Avoid_: perfect tiles, error-free tiles

**Error-free reach**:
A property of one search. The most pieces that search ever had on the board at once with no break
anywhere. Always at least the perfect prefix of the board it recorded, and usually more.
_Avoid_: perfect tiles, perfect prefix, deepest perfect depth

### Pieces and the board

**Piece**:
One of the 256 square tiles. Each has four coloured sides and may be placed in four rotations.
_Avoid_: tile, cell, square

**Square**:
One of the 256 positions on the board that a piece goes into.
_Avoid_: cell, slot, position

**Clue**:
A piece the puzzle fixes to a named square before the search starts. The published puzzle has five.
Only one of them is mandatory.
_Avoid_: hint, given, seed piece

### The search

**Fill order**:
The sequence in which the search visits the squares. Fixed before the search starts and never
changed during it.
_Avoid_: cell ordering, traversal, path

**Node**:
One visit to one square by the search. Counts of nodes are the currency every budget is quoted in.
Other people's solvers count differently, so a node count from elsewhere is not comparable without
checking.
_Avoid_: step, iteration, placement

**Break ceiling**:
The largest number of breaks a board may carry at a given depth. It rises as the board fills and
never falls, so a board is never asked to give a break back.
_Avoid_: slip limit, break budget, allowance

**Slip schedule**:
A named set of break ceilings, one per depth. The published ones come from Blackwood and Verhaard.
_Avoid_: break schedule, slip array

**Tail allowance**:
Extra breaks granted near the end of the board, on top of whatever the slip schedule permits there.
_Avoid_: bonus, tail bonus, extra slack

**Colour quota**:
A rule that abandons any line of play which has not spent enough sides of three chosen colours by a
given depth.
_Avoid_: colour gate, colour rule, quota gate

**Quota triple**:
The three colours a colour quota counts. There are 680 possible triples.
_Avoid_: colour set, triple, colours

**Attempt**:
One run of the solver under one set of settings, from an empty board until its node budget runs
out.
_Avoid_: run, arm, trial, experiment
