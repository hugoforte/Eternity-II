/* Colour palette for the 23 Eternity II edge patterns.
 *
 * Index 0 is the grey "outside edge" marker, which the real game prints as a
 * plain grey border; everything else is one of the 22 coloured patterns. The
 * hues are picked to stay distinguishable from each other even at 40px per
 * tile, and to echo the bright geometric look of the printed board.
 */

export const PALETTE = [
  '#36365c', // 0  edge / grey
  '#ff5d5d', // 1  red
  '#ffa23a', // 2  orange
  '#ffd93d', // 3  yellow
  '#a5e84d', // 4  lime
  '#4ddb8b', // 5  green
  '#3ad0c8', // 6  teal
  '#43b8ff', // 7  sky
  '#5b7cfa', // 8  indigo
  '#9b6bff', // 9  violet
  '#e86bd8', // 10 magenta
  '#ff6fa5', // 11 pink
  '#ff8a5b', // 12 coral
  '#e0b23c', // 13 gold
  '#7ad3a0', // 14 mint
  '#6fd0e8', // 15 cyan
  '#8aa0ff', // 16 periwinkle
  '#c49bff', // 17 lavender
  '#ffb3c7', // 18 blush
  '#d9e85b', // 19 chartreuse
  '#57c4a3', // 20 seafoam
  '#c98a54', // 21 bronze
  '#9a96c4', // 22 slate lilac
];

export const EDGE_COLOUR = PALETTE[0];

export function colourOf(index) {
  return PALETTE[index] || '#555';
}

/** Rotate a [left, top, right, bottom] tuple clockwise `rot` quarter turns. */
export function rotatedSides(sides, rot) {
  let l = sides[0], t = sides[1], r = sides[2], b = sides[3];
  for (let i = 0; i < (rot & 3); i++) {
    const nl = b, nt = l, nr = t, nb = r;
    l = nl; t = nt; r = nr; b = nb;
  }
  return [l, t, r, b];
}
