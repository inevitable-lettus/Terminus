// What the map draws, derived once from the API's world and network: positions
// by junction id, the roads as segments, and each line as a drawable path plus
// a looping track for its buses to run on.

import type { Line, Network, World } from "./api";
import { type Point, track, type Track } from "./geometry";

/**
 * Line colours. Desaturated so eight of them sit together on navy without
 * shouting, and kept clear of the cyan the journey owns, so a journey never
 * looks like a line.
 */
export const LINE_COLOURS = ["#7aa2f7", "#f2b25e", "#8fd694", "#e98aa8", "#b9a3e3", "#e8d270", "#f0906c", "#79d0c3"];

export function lineColour(id: number): string {
  return LINE_COLOURS[id % LINE_COLOURS.length];
}

export const JOURNEY = "#37d7ff";
export const WALK = "#3fe0b8";
export const DIJKSTRA = "#ffb938";

/** Road-state colours, the same ramp the DSTNS map uses. */
export function congestionColour(congestion: number): string {
  if (congestion >= 0.66) return "#ff5b65";
  if (congestion >= 0.33) return "#ffb938";
  return "#39e6a2";
}

/** Demand as a colour, pale to amber to red, like DSTNS's place glyphs. */
export function demandColour(fraction: number): string {
  const f = Math.max(0, Math.min(1, fraction));
  const stops = [[220, 232, 242], [255, 185, 56], [255, 91, 101]];
  const [a, b, k] = f < 0.5 ? [stops[0], stops[1], f / 0.5] : [stops[1], stops[2], (f - 0.5) / 0.5];
  const c = a.map((v, i) => Math.round(v + (b[i] - v) * k));
  return `rgb(${c[0]} ${c[1]} ${c[2]})`;
}

export interface SceneLine {
  line: Line;
  colour: string;
  outbound: Point[];
  back: Point[] | null;
  /** Outbound then back, joined at the termini: where the buses run. */
  loop: Track;
  /** Seconds for one bus to go round, scaled down from the real round trip so it is watchable. */
  lapSeconds: number;
}

export interface Scene {
  world: World;
  network: Network;
  position: Map<number, Point>;
  lines: SceneLine[];
  maxDemand: number;
}

/**
 * The real round trip compressed about sixty-fold, and clamped so a very short
 * line does not flicker and a very long one still visibly moves.
 */
export function lapSeconds(roundTripMs: number): number {
  return Math.min(40, Math.max(8, roundTripMs / 1000 / 60 * 1.1));
}

export function buildScene(world: World, network: Network): Scene {
  const position = new Map<number, Point>();
  for (const j of world.junctions) position.set(j.id, { x: j.x, y: j.y });
  const at = (ids: number[]) => ids.map((id) => position.get(id)).filter((p): p is Point => p !== undefined);

  const lines = network.lines.map((line) => {
    const outbound = at(line.outbound.nodes);
    const back = line.back ? at(line.back.nodes) : null;
    const loop = track(back ? [...outbound, ...back.slice(1)] : outbound);
    const roundTrip = line.outbound.travel_ms + (line.back?.travel_ms ?? 0);
    return { line, colour: lineColour(line.id), outbound, back, loop, lapSeconds: lapSeconds(roundTrip) };
  });

  const maxDemand = world.stops.reduce((m, s) => Math.max(m, s.demand), 0);
  return { world, network, position, lines, maxDemand };
}

/** Minutes, one decimal, the way every figure in this module is printed. */
export function minutes(ms: number): string {
  return (ms / 60000).toFixed(1);
}

export function percent(fraction: number): string {
  return (fraction * 100).toFixed(1);
}
