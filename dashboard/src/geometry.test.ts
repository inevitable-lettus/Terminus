import { describe, expect, it } from "vitest";
import { along, fit, glide, GridIndex, toScreen, toWorld, track, zoomAt } from "./geometry";

describe("camera", () => {
  const box = { minX: -500, minY: -300, maxX: 1500, maxY: 700 };

  it("fits the whole box inside the canvas, centred", () => {
    const camera = fit(box, 1000, 600, 50);
    const corner = toScreen(camera, 1000, 600, box.minX, box.maxY);
    const opposite = toScreen(camera, 1000, 600, box.maxX, box.minY);
    expect(corner.x).toBeGreaterThanOrEqual(49.999);
    expect(opposite.x).toBeLessThanOrEqual(950.001);
    expect(corner.y).toBeGreaterThanOrEqual(0);
    expect(toScreen(camera, 1000, 600, 500, 200)).toEqual({ x: 500, y: 300 });
  });

  it("puts north up: a larger y is higher on screen", () => {
    const camera = fit(box, 1000, 600);
    expect(toScreen(camera, 1000, 600, 0, 100).y).toBeLessThan(toScreen(camera, 1000, 600, 0, 0).y);
  });

  it("round-trips between world and screen", () => {
    const camera = { cx: 12, cy: -40, scale: 0.7 };
    const p = toWorld(camera, 800, 500, 123, 456);
    const back = toScreen(camera, 800, 500, p.x, p.y);
    expect(back.x).toBeCloseTo(123, 9);
    expect(back.y).toBeCloseTo(456, 9);
  });

  it("zooms about the cursor, leaving the point under it where it was", () => {
    const camera = { cx: 0, cy: 0, scale: 1 };
    const before = toWorld(camera, 800, 600, 600, 150);
    const zoomed = zoomAt(camera, 2, 600, 150, 800, 600);
    const after = toWorld(zoomed, 800, 600, 600, 150);
    expect(zoomed.scale).toBe(2);
    expect(after.x).toBeCloseTo(before.x, 9);
    expect(after.y).toBeCloseTo(before.y, 9);
  });

  it("glides from start to end, with zoom interpolated geometrically", () => {
    const a = { cx: 0, cy: 0, scale: 1 };
    const b = { cx: 100, cy: 50, scale: 4 };
    expect(glide(a, b, 0)).toEqual(a);
    expect(glide(a, b, 1)).toEqual(b);
    expect(glide(a, b, 0.5).scale).toBeCloseTo(2, 9);
  });
});

describe("tracks", () => {
  const t = track([{ x: 0, y: 0 }, { x: 10, y: 0 }, { x: 10, y: 10 }]);

  it("measures a polyline and places points along it by fraction", () => {
    expect(t.length).toBe(20);
    expect(along(t, 0.25).point).toEqual({ x: 5, y: 0 });
    expect(along(t, 0.75).point).toEqual({ x: 10, y: 5 });
  });

  it("wraps past the end, so a marker loops round a line", () => {
    expect(along(t, 1.25).point).toEqual(along(t, 0.25).point);
    expect(along(t, -0.75).point).toEqual(along(t, 0.25).point);
  });
});

describe("grid index", () => {
  it("finds the same nearest point as a full scan", () => {
    let seed = 7;
    const random = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    const points = Array.from({ length: 400 }, () => ({ x: random() * 1000, y: random() * 1000 }));
    const index = new GridIndex(points, 50);
    for (let q = 0; q < 200; q++) {
      const x = random() * 1000, y = random() * 1000, radius = 5 + random() * 60;
      let expected = -1, best = radius;
      points.forEach((p, i) => {
        const d = Math.hypot(p.x - x, p.y - y);
        if (d <= best) { expected = i; best = d; }
      });
      const found = index.nearest(x, y, radius);
      if (expected < 0) expect(found).toBe(-1);
      else expect(Math.hypot(points[found].x - x, points[found].y - y)).toBeCloseTo(best, 9);
    }
  });
});
