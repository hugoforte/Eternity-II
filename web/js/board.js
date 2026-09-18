/* Canvas renderer for the 16x16 board.
 *
 * Each tile is drawn the way the printed Eternity II pieces look: a square cut
 * into four triangles by its diagonals, one triangle per edge colour. A piece's
 * rotation is baked into the colour tuple before drawing, so rotating a piece
 * visibly turns its pattern.
 *
 * Newly placed tiles pop in (scale + fade) and the mandatory hint piece keeps a
 * gold ring so it is always easy to spot.
 */

import { colourOf, rotatedSides } from './palette.js';

export class BoardView {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.n = 16;
    this.cells = 256;
    this.pieces = [];            // [[l,t,r,b], ...] in stored orientation
    this.fixedCells = new Set();
    this.board = new Array(256).fill(-1);
    this.appeared = new Float64Array(256);   // timestamp per cell, for the pop-in
    this.highlight = -1;
    this.dpr = Math.min(window.devicePixelRatio || 1, 2);
    this.animating = false;
    this._raf = 0;
    this.images = new Map();     // "pieceNumber_rot" -> { img, status }

    this._resize();
    window.addEventListener('resize', () => { this._resize(); this.draw(); });
  }

  setMeta(meta) {
    if (!meta) return;
    this.n = meta.n || 16;
    this.cells = meta.cells || this.n * this.n;
    this.pieces = meta.pieces || [];
    this.fixedCells = new Set((meta.fixed || []).map((f) => f[0]));
    if (this.board.length !== this.cells) {
      this.board = new Array(this.cells).fill(-1);
      this.appeared = new Float64Array(this.cells);
    }
    this._preloadImages();
    this._resize();
    this.draw();
  }

  /** Fetch the printed-piece artwork (one pre-rotated photo per quarter-turn)
   *  up front so tiles don't flash from drawn-triangle to photo the moment
   *  they're placed. Pieces without a matching file just fail once and keep
   *  using the drawn-triangle fallback. */
  _preloadImages() {
    for (let i = 0; i < this.pieces.length; i++) {
      const num = i + 1;
      for (let rot = 0; rot < 4; rot++) {
        const key = `${num}_${rot}`;
        if (this.images.has(key)) continue;
        const img = new Image();
        const entry = { img, status: 'loading' };
        this.images.set(key, entry);
        img.onload = () => { entry.status = 'ready'; this.draw(); };
        img.onerror = () => { entry.status = 'error'; };
        img.src = `/pieces/${key}.jpg`;
      }
    }
  }

  _resize() {
    const rect = this.canvas.getBoundingClientRect();
    const cssSize = Math.max(240, Math.floor(rect.width || 720));
    this.canvas.width = Math.floor(cssSize * this.dpr);
    this.canvas.height = Math.floor(cssSize * this.dpr);
    this.pad = Math.max(6, Math.round(cssSize * 0.012)) * this.dpr;
    this.tile = (this.canvas.width - this.pad * 2) / this.n;
  }

  /** Replace the whole board; cells that newly appeared get animated. */
  setBoard(board, { animate = true } = {}) {
    if (!board) return;
    const now = performance.now();
    let changed = false;
    for (let i = 0; i < this.cells; i++) {
      const next = board[i] === undefined ? -1 : board[i];
      const prev = this.board[i];
      if (next !== prev) {
        changed = true;
        if (next >= 0 && animate) this.appeared[i] = now;
        else if (next < 0) this.appeared[i] = 0;
      }
      this.board[i] = next;
    }
    if (changed) this._scheduleAnimation();
    else this.draw();
  }

  clear() {
    this.board.fill(-1);
    this.appeared.fill(0);
    this.draw();
  }

  setHighlight(cell) {
    this.highlight = cell === undefined ? -1 : cell;
  }

  _scheduleAnimation() {
    if (this.animating) return;
    this.animating = true;
    const step = () => {
      const more = this.draw();
      if (more) {
        this._raf = requestAnimationFrame(step);
      } else {
        this.animating = false;
      }
    };
    this._raf = requestAnimationFrame(step);
  }

  /** @returns true while any tile is still animating. */
  draw() {
    const { ctx } = this;
    const W = this.canvas.width;
    const now = performance.now();
    const POP = 260;

    ctx.clearRect(0, 0, W, W);

    // board backdrop
    ctx.fillStyle = 'rgba(6,5,20,0.92)';
    roundRect(ctx, 0, 0, W, W, 12 * this.dpr);
    ctx.fill();

    let animating = false;

    for (let cell = 0; cell < this.cells; cell++) {
      const row = Math.floor(cell / this.n);
      const col = cell - row * this.n;
      const x = this.pad + col * this.tile;
      const y = this.pad + row * this.tile;
      const variant = this.board[cell];

      if (variant < 0) {
        drawEmpty(ctx, x, y, this.tile, this.dpr);
        continue;
      }

      const pieceId = variant >> 2;
      const rot = variant & 3;
      const base = this.pieces[pieceId];
      if (!base) { drawEmpty(ctx, x, y, this.tile, this.dpr); continue; }
      const sides = rotatedSides(base, rot);
      const imgEntry = this.images.get(`${pieceId + 1}_${rot}`);
      const img = imgEntry && imgEntry.status === 'ready' ? imgEntry.img : null;

      let scale = 1;
      let alpha = 1;
      const t0 = this.appeared[cell];
      if (t0 > 0) {
        const k = (now - t0) / POP;
        if (k < 1) {
          animating = true;
          const e = easeOutBack(Math.max(0, k));
          scale = 0.55 + 0.45 * e;
          alpha = Math.min(1, 0.25 + k * 1.6);
        } else {
          this.appeared[cell] = 0;
        }
      }

      ctx.save();
      ctx.globalAlpha = alpha;
      if (scale !== 1) {
        const cx = x + this.tile / 2;
        const cy = y + this.tile / 2;
        ctx.translate(cx, cy);
        ctx.scale(scale, scale);
        ctx.translate(-cx, -cy);
      }
      drawTile(ctx, x, y, this.tile, sides, this.dpr, img);
      ctx.restore();

      if (this.fixedCells.has(cell)) {
        ctx.save();
        ctx.strokeStyle = 'rgba(255,217,61,0.95)';
        ctx.lineWidth = 2.4 * this.dpr;
        ctx.shadowColor = 'rgba(255,217,61,0.8)';
        ctx.shadowBlur = 9 * this.dpr;
        roundRect(ctx, x + 1.2 * this.dpr, y + 1.2 * this.dpr,
                  this.tile - 2.4 * this.dpr, this.tile - 2.4 * this.dpr, 4 * this.dpr);
        ctx.stroke();
        ctx.restore();
      }
    }

    // the cell placed most recently in a replay
    if (this.highlight >= 0 && this.highlight < this.cells) {
      const row = Math.floor(this.highlight / this.n);
      const col = this.highlight - row * this.n;
      const x = this.pad + col * this.tile;
      const y = this.pad + row * this.tile;
      ctx.save();
      ctx.strokeStyle = 'rgba(255,255,255,0.95)';
      ctx.lineWidth = 2.6 * this.dpr;
      ctx.shadowColor = 'rgba(255,255,255,0.7)';
      ctx.shadowBlur = 10 * this.dpr;
      roundRect(ctx, x, y, this.tile, this.tile, 4 * this.dpr);
      ctx.stroke();
      ctx.restore();
    }

    return animating;
  }
}

function drawEmpty(ctx, x, y, size, dpr) {
  const inset = 1.1 * dpr;
  ctx.fillStyle = 'rgba(255,255,255,0.028)';
  roundRect(ctx, x + inset, y + inset, size - inset * 2, size - inset * 2, 3.5 * dpr);
  ctx.fill();
  ctx.strokeStyle = 'rgba(150,130,255,0.08)';
  ctx.lineWidth = 1;
  ctx.stroke();
}

/** Draws one tile. Uses the piece's printed-artwork photo when it has loaded,
 *  falling back to the drawn-triangle rendering (both share the same border
 *  and glossy overlay so the two styles sit together on the board). */
function drawTile(ctx, x, y, size, sides, dpr, img) {
  const x1 = x + size;
  const y1 = y + size;

  if (img) {
    drawImageTile(ctx, x, y, size, img);
  } else {
    drawVectorTile(ctx, x, y, size, sides, dpr);
  }

  // tile border + soft inner highlight
  ctx.strokeStyle = 'rgba(6,5,18,0.85)';
  ctx.lineWidth = Math.max(1, 1 * dpr);
  ctx.strokeRect(x + 0.5, y + 0.5, size - 1, size - 1);

  const grad = ctx.createLinearGradient(x, y, x, y1);
  grad.addColorStop(0, 'rgba(255,255,255,0.14)');
  grad.addColorStop(0.45, 'rgba(255,255,255,0.02)');
  grad.addColorStop(1, 'rgba(0,0,0,0.17)');
  ctx.fillStyle = grad;
  ctx.fillRect(x, y, size, size);
}

function drawImageTile(ctx, x, y, size, img) {
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(img, x, y, size, size);
}

function drawVectorTile(ctx, x, y, size, sides, dpr) {
  const cx = x + size / 2;
  const cy = y + size / 2;
  const x1 = x + size;
  const y1 = y + size;

  // four triangles: top, right, bottom, left
  tri(ctx, x, y, x1, y, cx, cy, colourOf(sides[1]));
  tri(ctx, x1, y, x1, y1, cx, cy, colourOf(sides[2]));
  tri(ctx, x1, y1, x, y1, cx, cy, colourOf(sides[3]));
  tri(ctx, x, y1, x, y, cx, cy, colourOf(sides[0]));

  // seams along the diagonals give the printed-tile look
  ctx.strokeStyle = 'rgba(8,6,22,0.5)';
  ctx.lineWidth = Math.max(1, 0.7 * dpr);
  ctx.beginPath();
  ctx.moveTo(x, y); ctx.lineTo(x1, y1);
  ctx.moveTo(x1, y); ctx.lineTo(x, y1);
  ctx.stroke();
}

function tri(ctx, ax, ay, bx, by, cx, cy, fill) {
  ctx.fillStyle = fill;
  ctx.beginPath();
  ctx.moveTo(ax, ay);
  ctx.lineTo(bx, by);
  ctx.lineTo(cx, cy);
  ctx.closePath();
  ctx.fill();
}

function roundRect(ctx, x, y, w, h, r) {
  const rr = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + rr, y);
  ctx.arcTo(x + w, y, x + w, y + h, rr);
  ctx.arcTo(x + w, y + h, x, y + h, rr);
  ctx.arcTo(x, y + h, x, y, rr);
  ctx.arcTo(x, y, x + w, y, rr);
  ctx.closePath();
}

function easeOutBack(t) {
  const c1 = 1.70158;
  const c3 = c1 + 1;
  const x = t - 1;
  return 1 + c3 * x * x * x + c1 * x * x;
}
