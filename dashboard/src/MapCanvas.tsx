import { forwardRef, useEffect, useImperativeHandle, useMemo, useRef } from "react";
import type { BuildStep, Journey } from "./api";
import {
  along, boxOf, type Camera, fit, glide, glideMillis, GridIndex, type Point, toScreen, toWorld, zoomAt,
} from "./geometry";
import { congestionColour, demandColour, DIJKSTRA, JOURNEY, lineColour, type Scene, WALK } from "./scene";

export interface Layers { congestion: boolean; coverage: boolean; buses: boolean }

export interface Overlay {
  focusLine: number | null;
  journey: Journey | null;
  endpoints: { from: number | null; to: number | null };
  /** Settled junctions of each search, and how far through the replay we are (0..1). */
  search: { astar: number[]; dijkstra: number[]; progress: number } | null;
  /** The builder's decisions, and how many of them to show. */
  build: { steps: BuildStep[]; shown: number } | null;
  layers: Layers;
  picking: "from" | "to" | null;
}

export interface Hover { x: number; y: number; node: number; stop: boolean }

export interface MapHandle {
  fitAll(): void;
  fitNodes(ids: number[]): void;
  zoomBy(factor: number): void;
}

interface Props {
  scene: Scene;
  overlay: Overlay;
  /** Screen area the floating panel covers on the right, so fits land in the visible part. */
  rightInset: number;
  onPick(node: number): void;
  onHover(hover: Hover | null): void;
}

const ROAD_WIDTH: Record<string, number> = {
  motorway: 3, primary: 2.6, secondary: 2.2, tertiary: 1.8, residential: 1.3, service: 0.9,
};

const reducedMotion = () =>
  typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

/**
 * The map: two canvases, as the DSTNS map does it. The base holds the roads and
 * is redrawn only when the camera or a layer changes; the dynamic canvas above
 * it is redrawn every frame for everything that moves.
 */
export const MapCanvas = forwardRef<MapHandle, Props>(function MapCanvas(
  { scene, overlay, rightInset, onPick, onHover }, ref,
) {
  const holder = useRef<HTMLDivElement>(null);
  const base = useRef<HTMLCanvasElement>(null);
  const dynamic = useRef<HTMLCanvasElement>(null);
  const camera = useRef<Camera>({ cx: 0, cy: 0, scale: 1 });
  const size = useRef({ w: 1, h: 1, dpr: 1 });
  const flight = useRef<{ from: Camera; to: Camera; start: number; ms: number } | null>(null);
  const baseDirty = useRef(true);
  /** Until the user pans or zooms, a resize refits the city rather than keeping a stale view. */
  const userMoved = useRef(false);
  const latest = useRef({ scene, overlay, rightInset, onPick, onHover });
  latest.current = { scene, overlay, rightInset, onPick, onHover };

  const points = useMemo(() => scene.world.junctions.map((j) => ({ x: j.x, y: j.y })), [scene]);
  const index = useMemo(() => new GridIndex(points, 60), [points]);
  const stopAt = useMemo(() => new Map(scene.world.stops.map((s) => [s.node, s])), [scene]);

  /** The camera that fits {@code box} into the part of the canvas the panel does not cover. */
  const fitVisible = (box: { minX: number; minY: number; maxX: number; maxY: number }, pad = 64): Camera => {
    const { w, h } = size.current;
    const inset = Math.min(latest.current.rightInset, w * 0.5);
    const top = 84;
    const cam = fit(box, w - inset, h - top, pad);
    return { ...cam, cx: cam.cx + inset / 2 / cam.scale, cy: cam.cy + top / 2 / cam.scale };
  };

  const flyTo = (to: Camera) => {
    if (reducedMotion()) {
      camera.current = to;
      baseDirty.current = true;
      return;
    }
    flight.current = { from: camera.current, to, start: performance.now(), ms: glideMillis(camera.current, to, size.current.w) };
  };

  const worldBox = () => {
    const b = latest.current.scene.world.bounds;
    return { minX: b.min_x, minY: b.min_y, maxX: b.max_x, maxY: b.max_y };
  };

  useImperativeHandle(ref, () => ({
    fitAll: () => {
      userMoved.current = false;
      flyTo(fitVisible(worldBox()));
    },
    fitNodes: (ids) => {
      const box = boxOf(ids.map((id) => latest.current.scene.position.get(id)).filter((p): p is Point => !!p));
      if (!box) return;
      const grow = Math.max(80, (box.maxX - box.minX + box.maxY - box.minY) * 0.08);
      flyTo(fitVisible({ minX: box.minX - grow, minY: box.minY - grow, maxX: box.maxX + grow, maxY: box.maxY + grow }, 96));
    },
    zoomBy: (factor) => {
      userMoved.current = true;
      const { w, h } = size.current;
      flyTo(zoomAt(camera.current, factor, (w - latest.current.rightInset) / 2, h / 2, w, h));
    },
  }));

  // Size the canvases to their box at device resolution, and fit the city the first time.
  useEffect(() => {
    const el = holder.current!;
    const observer = new ResizeObserver(() => {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const w = el.clientWidth, h = el.clientHeight;
      size.current = { w, h, dpr };
      for (const canvas of [base.current!, dynamic.current!]) {
        canvas.width = Math.round(w * dpr);
        canvas.height = Math.round(h * dpr);
      }
      if (!userMoved.current) {
        camera.current = fitVisible(worldBox());
        flight.current = null;
      }
      baseDirty.current = true;
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    baseDirty.current = true;
  }, [scene, overlay.layers.congestion]);

  // The frame loop.
  useEffect(() => {
    let frame = 0;
    const started = performance.now();
    const loop = (now: number) => {
      const f = flight.current;
      if (f) {
        const t = Math.min(1, (now - f.start) / f.ms);
        camera.current = glide(f.from, f.to, t);
        baseDirty.current = true;
        if (t >= 1) flight.current = null;
      }
      if (baseDirty.current) {
        drawBase();
        baseDirty.current = false;
      }
      drawDynamic(reducedMotion() ? 0 : (now - started) / 1000);
      frame = requestAnimationFrame(loop);
    };
    frame = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(frame);
  }, []);

  const project = (p: Point) => toScreen(camera.current, size.current.w, size.current.h, p.x, p.y);

  function begin(canvas: HTMLCanvasElement) {
    const ctx = canvas.getContext("2d")!;
    const { dpr } = size.current;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, size.current.w, size.current.h);
    return ctx;
  }

  /** Road widths grow gently as you zoom in, so streets stay streets at any scale. */
  const zoomWidth = () => Math.min(2.2, Math.max(0.7, Math.sqrt(camera.current.scale / 0.35)));

  function drawBase() {
    const ctx = begin(base.current!);
    const { w, h } = size.current;
    const { scene: s, overlay: o } = latest.current;

    const glow = ctx.createRadialGradient(w * 0.42, h * 0.5, 0, w * 0.42, h * 0.5, Math.max(w, h) * 0.75);
    glow.addColorStop(0, "#0b1d2c");
    glow.addColorStop(1, "#050f19");
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, w, h);

    const zw = zoomWidth();
    ctx.lineCap = "round";
    ctx.lineJoin = "round";

    // Casings first, one batched path per width, then the roads over them.
    const byClass = new Map<string, typeof s.world.roads>();
    for (const road of s.world.roads) {
      const key = road.road_class ?? "residential";
      const list = byClass.get(key);
      if (list) list.push(road); else byClass.set(key, [road]);
    }
    for (const pass of ["casing", "road"] as const) {
      for (const [roadClass, roads] of byClass) {
        const width = (ROAD_WIDTH[roadClass] ?? 1) * zw;
        if (pass === "casing") {
          ctx.strokeStyle = "#030b13";
          ctx.lineWidth = width + 2.2;
          ctx.globalAlpha = 0.9;
          strokeRoads(ctx, roads, s);
          continue;
        }
        ctx.lineWidth = width;
        if (o.layers.congestion) {
          for (const colourGroup of groupBy(roads, (r) => (r.closed ? "#ff5b65" : congestionColour(r.congestion)))) {
            ctx.strokeStyle = colourGroup.key;
            ctx.globalAlpha = roadClass === "service" ? 0.5 : 0.85;
            strokeRoads(ctx, colourGroup.items, s);
          }
        } else {
          ctx.strokeStyle = "#51697a";
          ctx.globalAlpha = roadClass === "service" ? 0.55 : 0.95;
          strokeRoads(ctx, roads, s);
        }
      }
    }
    ctx.globalAlpha = 1;
  }

  function strokeRoads(ctx: CanvasRenderingContext2D, roads: Scene["world"]["roads"], s: Scene) {
    ctx.beginPath();
    for (const road of roads) {
      const a = s.position.get(road.from), b = s.position.get(road.to);
      if (!a || !b) continue;
      const pa = project(a), pb = project(b);
      ctx.moveTo(pa.x, pa.y);
      ctx.lineTo(pb.x, pb.y);
    }
    ctx.stroke();
  }

  function strokePath(ctx: CanvasRenderingContext2D, pts: Point[]) {
    if (pts.length < 2) return;
    ctx.beginPath();
    const first = project(pts[0]);
    ctx.moveTo(first.x, first.y);
    for (let i = 1; i < pts.length; i++) {
      const p = project(pts[i]);
      ctx.lineTo(p.x, p.y);
    }
    ctx.stroke();
  }

  function drawDynamic(seconds: number) {
    const ctx = begin(dynamic.current!);
    const { scene: s, overlay: o } = latest.current;
    const pulse = (seconds / 2.4) % 1;
    const zw = zoomWidth();
    const at = (ids: number[]) => ids.map((id) => s.position.get(id)).filter((p): p is Point => !!p);
    ctx.lineCap = "round";
    ctx.lineJoin = "round";

    // ---- lines, or the construction replay in their place ----
    if (o.build) {
      drawBuild(ctx, o.build.steps.slice(0, o.build.shown), pulse, at);
    } else {
      for (const line of s.lines) {
        const focused = o.focusLine === line.line.id;
        const dimmed = (o.focusLine !== null && !focused) || (o.journey !== null && o.focusLine === null);
        ctx.globalAlpha = dimmed ? 0.16 : 0.92;
        ctx.strokeStyle = line.colour;
        ctx.lineWidth = (focused ? 4.6 : 3.4) * Math.min(1.4, zw);
        if (focused) {
          ctx.shadowColor = line.colour;
          ctx.shadowBlur = 14;
        }
        strokePath(ctx, line.outbound);
        if (line.back) {
          ctx.globalAlpha *= 0.8;
          strokePath(ctx, line.back);
        }
        ctx.shadowBlur = 0;
      }
      ctx.globalAlpha = 1;

      // Buses: representative markers going round each line, as DSTNS samples its traffic.
      if (o.layers.buses) {
        for (const line of s.lines) {
          if (o.focusLine !== null && o.focusLine !== line.line.id) continue;
          if (o.journey && o.focusLine === null) continue;
          const count = Math.max(2, Math.min(6, Math.round(line.loop.length / 900)));
          for (let i = 0; i < count; i++) {
            const { point, heading } = along(line.loop, i / count + seconds / line.lapSeconds);
            drawBus(ctx, project(point), -heading, line.colour);
          }
        }
      }
    }

    // ---- stops, sized and coloured by demand ----
    const max = s.maxDemand || 1;
    for (const stop of s.world.stops) {
      const p = s.position.get(stop.node);
      if (!p) continue;
      const q = project(p);
      const served = stop.lines.length > 0;
      const r = (2.4 + 4.6 * Math.sqrt(stop.demand / max)) * Math.min(1.3, zw);
      if (o.layers.coverage && served) {
        ctx.beginPath();
        ctx.arc(q.x, q.y, s.world.provenance.budget.walk_metres * camera.current.scale, 0, Math.PI * 2);
        ctx.fillStyle = "rgb(178 235 255 / 5%)";
        ctx.fill();
        ctx.strokeStyle = "rgb(178 235 255 / 16%)";
        ctx.lineWidth = 1;
        ctx.stroke();
      }
      ctx.beginPath();
      ctx.arc(q.x, q.y, r, 0, Math.PI * 2);
      if (!stop.main) {
        ctx.strokeStyle = "#6f8290";
        ctx.lineWidth = 1.2;
        ctx.stroke();
        continue;
      }
      ctx.fillStyle = demandColour(stop.demand / max);
      ctx.globalAlpha = served ? 0.95 : 0.45;
      ctx.fill();
      ctx.globalAlpha = 1;
      ctx.strokeStyle = "#071420";
      ctx.lineWidth = 1.4;
      ctx.stroke();
    }

    // ---- the two searches' wavefronts ----
    if (o.search) {
      const longest = Math.max(o.search.astar.length, o.search.dijkstra.length);
      const shown = Math.round(o.search.progress * longest);
      drawWave(ctx, o.search.dijkstra.slice(0, shown), DIJKSTRA, s, false);
      drawWave(ctx, o.search.astar.slice(0, shown), JOURNEY, s, true);
    }

    // ---- the journey ----
    if (o.journey?.found) {
      for (const leg of o.journey.legs) {
        const pts = at(leg.path);
        if (leg.kind === "RIDE") {
          ctx.strokeStyle = JOURNEY;
          ctx.globalAlpha = 0.28;
          ctx.lineWidth = 11 * Math.min(1.3, zw);
          strokePath(ctx, pts);
          ctx.globalAlpha = 1;
          ctx.lineWidth = 4 * Math.min(1.3, zw);
          ctx.shadowColor = JOURNEY;
          ctx.shadowBlur = 12;
          strokePath(ctx, pts);
          ctx.shadowBlur = 0;
          ctx.strokeStyle = "rgb(230 250 255 / 85%)";
          ctx.lineWidth = 1.4;
          ctx.setLineDash([10, 14]);
          ctx.lineDashOffset = -pulse * 48;
          strokePath(ctx, pts);
          ctx.setLineDash([]);
        } else if (leg.kind !== "WAIT" && leg.kind !== "TRANSFER") {
          ctx.strokeStyle = WALK;
          ctx.lineWidth = 2.4;
          ctx.setLineDash([2, 6]);
          ctx.lineDashOffset = -pulse * 16;
          strokePath(ctx, pts);
          ctx.setLineDash([]);
        }
      }
      // Where the rider changes, a ring.
      for (const leg of o.journey.legs) {
        if (leg.kind === "TRANSFER" || leg.kind === "WALK" || leg.kind === "WAIT") {
          const p = s.position.get(leg.from_node);
          if (p) ring(ctx, project(p), 9, "#dfe9f2", 0.9);
        }
      }
    }

    // ---- endpoints, pinging ----
    const { from, to } = o.endpoints;
    for (const [node, colour] of [[from, WALK], [to, JOURNEY]] as const) {
      if (node === null) continue;
      const p = s.position.get(node);
      if (!p) continue;
      const q = project(p);
      for (const k of [0, 0.5]) {
        const t = (pulse + k) % 1;
        ring(ctx, q, 6 + t * 22, colour, (1 - t) * 0.7);
      }
      ctx.beginPath();
      ctx.arc(q.x, q.y, 6, 0, Math.PI * 2);
      ctx.fillStyle = colour;
      ctx.fill();
      ctx.strokeStyle = "#071420";
      ctx.lineWidth = 2;
      ctx.stroke();
    }
  }

  function drawBus(ctx: CanvasRenderingContext2D, p: Point, angle: number, colour: string) {
    ctx.save();
    ctx.translate(p.x, p.y);
    ctx.rotate(angle);
    ctx.fillStyle = colour;
    ctx.shadowColor = colour;
    ctx.shadowBlur = 8;
    ctx.beginPath();
    ctx.roundRect(-6, -3.2, 12, 6.4, 2.2);
    ctx.fill();
    ctx.shadowBlur = 0;
    ctx.fillStyle = "#f4fbff";
    ctx.fillRect(1.5, -1.6, 3, 3.2);
    ctx.restore();
  }

  /**
   * One search's settled stops so far. Dijkstra is drawn as rings and A* as
   * filled dots over them, so where both settled a stop you see both, and the
   * rings with no dot inside are the work A* did not need to do.
   */
  function drawWave(ctx: CanvasRenderingContext2D, nodes: number[], colour: string, s: Scene, filled: boolean) {
    const n = nodes.length;
    for (let i = 0; i < n; i++) {
      const p = s.position.get(nodes[i]);
      if (!p) continue;
      const q = project(p);
      const fresh = n - i <= 1;
      ctx.globalAlpha = 0.55 + 0.45 * Math.max(0, 1 - (n - i) / 12);
      ctx.beginPath();
      ctx.arc(q.x, q.y, filled ? 5 : 9, 0, Math.PI * 2);
      if (filled) {
        ctx.fillStyle = colour;
        ctx.shadowColor = colour;
        ctx.shadowBlur = fresh ? 14 : 6;
        ctx.fill();
        ctx.shadowBlur = 0;
      } else {
        ctx.strokeStyle = colour;
        ctx.lineWidth = 2.2;
        ctx.stroke();
      }
      if (fresh) {
        ring(ctx, q, filled ? 14 : 18, colour, 0.8);
      }
    }
    ctx.globalAlpha = 1;
  }

  function drawBuild(ctx: CanvasRenderingContext2D, steps: BuildStep[], pulse: number,
                     at: (ids: number[]) => Point[]) {
    const zw = Math.min(1.4, zoomWidth());
    const last = steps[steps.length - 1];
    for (const step of steps) {
      if (step.kind !== "extend") continue;
      const fresh = step === last;
      ctx.strokeStyle = lineColour(step.line);
      ctx.lineWidth = (fresh ? 5 : 3.4) * zw;
      ctx.globalAlpha = fresh ? 1 : 0.85;
      if (fresh) {
        ctx.shadowColor = lineColour(step.line);
        ctx.shadowBlur = 16;
      }
      strokePath(ctx, at(step.path));
      ctx.shadowBlur = 0;
    }
    ctx.globalAlpha = 1;
    for (const step of steps) {
      if (step.kind !== "hub" && step.kind !== "exclude") continue;
      const p = latest.current.scene.position.get(step.stop);
      if (!p) continue;
      const colour = step.kind === "hub" ? lineColour(step.line) : "#ff6b73";
      ring(ctx, project(p), 11, colour, 0.9);
    }
    if (last && (last.kind === "hub" || last.kind === "extend")) {
      const p = latest.current.scene.position.get(last.stop);
      if (p) ring(ctx, project(p), 10 + pulse * 26, lineColour(last.line), (1 - pulse) * 0.8);
    }
  }

  function ring(ctx: CanvasRenderingContext2D, p: Point, r: number, colour: string, alpha: number) {
    ctx.globalAlpha = Math.max(0, alpha);
    ctx.strokeStyle = colour;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(p.x, p.y, r, 0, Math.PI * 2);
    ctx.stroke();
    ctx.globalAlpha = 1;
  }

  // ---- pointer: drag to pan, wheel to zoom, click to pick, hover to inspect ----
  const drag = useRef<{ x: number; y: number; moved: boolean; cam: Camera } | null>(null);

  const nearestNode = (sx: number, sy: number, px: number): number => {
    const p = toWorld(camera.current, size.current.w, size.current.h, sx, sy);
    const i = index.nearest(p.x, p.y, px / camera.current.scale);
    return i < 0 ? -1 : latest.current.scene.world.junctions[i].id;
  };

  const local = (e: React.PointerEvent | React.WheelEvent) => {
    const r = dynamic.current!.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  };

  return (
    <div ref={holder} className="stage" aria-hidden="false">
      <canvas ref={base} />
      <canvas
        ref={dynamic}
        className={"dynamic" + (overlay.picking ? " picking" : "")}
        role="img"
        aria-label="Map of the planned bus lines and the current journey"
        onPointerDown={(e) => {
          e.currentTarget.setPointerCapture(e.pointerId);
          const p = local(e);
          drag.current = { x: p.x, y: p.y, moved: false, cam: camera.current };
        }}
        onPointerMove={(e) => {
          const p = local(e);
          const d = drag.current;
          if (d) {
            if (!d.moved && Math.hypot(p.x - d.x, p.y - d.y) > 3) {
              d.moved = true;
              userMoved.current = true;
              flight.current = null;
              e.currentTarget.classList.add("dragging");
            }
            if (d.moved) {
              camera.current = { ...d.cam, cx: d.cam.cx - (p.x - d.x) / d.cam.scale, cy: d.cam.cy + (p.y - d.y) / d.cam.scale };
              baseDirty.current = true;
            }
            return;
          }
          const node = nearestNode(p.x, p.y, 12);
          latest.current.onHover(node < 0 ? null : { x: e.clientX, y: e.clientY, node, stop: stopAt.has(node) });
        }}
        onPointerUp={(e) => {
          const d = drag.current;
          drag.current = null;
          e.currentTarget.classList.remove("dragging");
          if (d && !d.moved && latest.current.overlay.picking) {
            const p = local(e);
            const node = nearestNode(p.x, p.y, 16);
            if (node >= 0) latest.current.onPick(node);
          }
        }}
        onPointerLeave={() => latest.current.onHover(null)}
        onWheel={(e) => {
          const p = local(e);
          flight.current = null;
          userMoved.current = true;
          camera.current = zoomAt(camera.current, Math.exp(-e.deltaY * 0.0015), p.x, p.y, size.current.w, size.current.h);
          baseDirty.current = true;
        }}
      />
    </div>
  );
});

function groupBy<T>(items: T[], key: (item: T) => string): { key: string; items: T[] }[] {
  const groups = new Map<string, T[]>();
  for (const item of items) {
    const k = key(item);
    const list = groups.get(k);
    if (list) list.push(item); else groups.set(k, [item]);
  }
  return [...groups].map(([k, v]) => ({ key: k, items: v }));
}
