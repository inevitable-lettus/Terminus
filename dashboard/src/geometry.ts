// Camera and geometry for the map, kept free of the DOM so it can be tested.
//
// World coordinates are the simulator's projected metres (x east, y north). The
// screen has y pointing down, so the camera flips y. A camera is a centre in
// metres and a scale in pixels per metre; everything else derives from that.

export interface Camera { cx: number; cy: number; scale: number }
export interface Point { x: number; y: number }
export interface Box { minX: number; minY: number; maxX: number; maxY: number }

export const MIN_SCALE = 0.02;
export const MAX_SCALE = 40;

export function clampScale(scale: number): number {
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
}

/** The camera that shows {@code box} whole inside a {@code width x height} canvas, leaving {@code pad} px. */
export function fit(box: Box, width: number, height: number, pad = 48): Camera {
  const spanX = Math.max(box.maxX - box.minX, 1);
  const spanY = Math.max(box.maxY - box.minY, 1);
  const scale = Math.min(Math.max(1, width - 2 * pad) / spanX, Math.max(1, height - 2 * pad) / spanY);
  return { cx: (box.minX + box.maxX) / 2, cy: (box.minY + box.maxY) / 2, scale: clampScale(scale) };
}

export function toScreen(camera: Camera, width: number, height: number, x: number, y: number): Point {
  return { x: width / 2 + (x - camera.cx) * camera.scale, y: height / 2 - (y - camera.cy) * camera.scale };
}

export function toWorld(camera: Camera, width: number, height: number, sx: number, sy: number): Point {
  return { x: camera.cx + (sx - width / 2) / camera.scale, y: camera.cy - (sy - height / 2) / camera.scale };
}

/** Zooms by {@code factor} keeping the world point under the cursor where it is. */
export function zoomAt(camera: Camera, factor: number, sx: number, sy: number, width: number, height: number): Camera {
  const scale = clampScale(camera.scale * factor);
  const anchor = toWorld(camera, width, height, sx, sy);
  return {
    scale,
    cx: anchor.x - (sx - width / 2) / scale,
    cy: anchor.y + (sy - height / 2) / scale,
  };
}

export function easeInOutCubic(t: number): number {
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
}

/**
 * The camera part-way through a glide. Zoom is interpolated geometrically, so
 * zooming in by 4x feels the same speed as zooming out by 4x.
 */
export function glide(from: Camera, to: Camera, t: number): Camera {
  const e = easeInOutCubic(Math.min(1, Math.max(0, t)));
  return {
    cx: from.cx + (to.cx - from.cx) * e,
    cy: from.cy + (to.cy - from.cy) * e,
    scale: from.scale * Math.pow(to.scale / from.scale, e),
  };
}

/** How long a glide should take: longer for a bigger move, within 650 to 1400 ms. */
export function glideMillis(from: Camera, to: Camera, width: number): number {
  const pan = Math.hypot(to.cx - from.cx, to.cy - from.cy) * Math.min(from.scale, to.scale) / Math.max(1, width);
  const zoom = Math.abs(Math.log2(to.scale / from.scale));
  return Math.round(Math.min(1400, Math.max(650, 650 + 350 * pan + 180 * zoom)));
}

export function boxOf(points: Point[]): Box | null {
  if (points.length === 0) return null;
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const p of points) {
    minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
    maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y);
  }
  return { minX, minY, maxX, maxY };
}

/** A polyline with cumulative lengths, for placing a marker a fraction of the way along it. */
export interface Track { points: Point[]; cumulative: number[]; length: number }

export function track(points: Point[]): Track {
  const cumulative = [0];
  for (let i = 1; i < points.length; i++) {
    cumulative.push(cumulative[i - 1] + Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y));
  }
  return { points, cumulative, length: cumulative[cumulative.length - 1] ?? 0 };
}

/** The point {@code fraction} (0..1, wrapping) of the way along {@code t}, and the heading there in radians. */
export function along(t: Track, fraction: number): { point: Point; heading: number } {
  if (t.points.length === 0) return { point: { x: 0, y: 0 }, heading: 0 };
  if (t.points.length === 1 || t.length === 0) return { point: t.points[0], heading: 0 };
  const f = ((fraction % 1) + 1) % 1;
  const target = f * t.length;
  let lo = 0, hi = t.cumulative.length - 1;
  while (lo < hi - 1) {
    const mid = (lo + hi) >> 1;
    if (t.cumulative[mid] <= target) lo = mid; else hi = mid;
  }
  const a = t.points[lo], b = t.points[hi];
  const span = t.cumulative[hi] - t.cumulative[lo] || 1;
  const k = (target - t.cumulative[lo]) / span;
  return { point: { x: a.x + (b.x - a.x) * k, y: a.y + (b.y - a.y) * k }, heading: Math.atan2(b.y - a.y, b.x - a.x) };
}

/**
 * A uniform grid over points, for "which junction is under the cursor" in about
 * constant time instead of scanning every junction on every mouse move.
 */
export class GridIndex {
  private readonly cells = new Map<string, number[]>();

  constructor(private readonly points: Point[], private readonly cellSize: number) {
    points.forEach((p, i) => {
      const key = this.key(Math.floor(p.x / cellSize), Math.floor(p.y / cellSize));
      const cell = this.cells.get(key);
      if (cell) cell.push(i); else this.cells.set(key, [i]);
    });
  }

  private key(ix: number, iy: number): string {
    return ix + ":" + iy;
  }

  /** Index of the nearest point within {@code radius} of (x, y), or -1. */
  nearest(x: number, y: number, radius: number): number {
    const reach = Math.ceil(radius / this.cellSize);
    const cx = Math.floor(x / this.cellSize), cy = Math.floor(y / this.cellSize);
    let best = -1, bestDistance = radius;
    for (let ix = cx - reach; ix <= cx + reach; ix++) {
      for (let iy = cy - reach; iy <= cy + reach; iy++) {
        for (const i of this.cells.get(this.key(ix, iy)) ?? []) {
          const d = Math.hypot(this.points[i].x - x, this.points[i].y - y);
          if (d <= bestDistance) {
            best = i; bestDistance = d;
          }
        }
      }
    }
    return best;
  }
}
