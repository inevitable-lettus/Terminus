// The shapes `./bus serve` sends (and `./bus export --bundle` writes), and the
// two places they can come from. The dashboard asks the live server first and
// falls back to the saved bundle, so it opens with or without a simulator.

export interface Budget {
  max_line_minutes: number;
  max_lines: number;
  target_coverage: number;
  walk_metres: number;
}

export interface Provenance {
  source: "live" | "bundle";
  run_id: string | null;
  seed: string | null;
  virtual_time: string | null;
  city: string | null;
  country: string | null;
  graph_hash: string | null;
  demand_source: string;
  generators_assigned: number;
  generators_active: number;
  stops_flagged: number;
  stops: number;
  stops_in_pockets: number;
  budget: Budget;
}

export interface Junction { id: number; x: number; y: number; lat: number; lon: number; main: boolean }

export interface Road {
  id: number;
  from: number;
  to: number;
  road_class: string | null;
  one_way: boolean;
  closed: boolean;
  cost_ms: number | null;
  congestion: number;
}

export interface Stop { node: number; demand: number; main: boolean; lines: string[] }

export interface World {
  provenance: Provenance;
  bounds: { min_x: number; min_y: number; max_x: number; max_y: number };
  junctions: Junction[];
  roads: Road[];
  stops: Stop[];
}

export interface Direction { nodes: number[]; stops: number[]; travel_ms: number }

export interface Line {
  id: number;
  label: string;
  outbound: Direction;
  back: Direction | null;
  demand: number;
  share: number;
}

export interface Network {
  coverage: number;
  demand_covered: number;
  total_demand: number;
  stop_reason: string;
  interchanges: number[];
  connected: boolean;
  lines: Line[];
}

export type LegKind = "ACCESS" | "WAIT" | "RIDE" | "TRANSFER" | "WALK" | "EGRESS" | "WALK_DIRECT";

export interface Leg {
  kind: LegKind;
  line: string | null;
  from_node: number;
  to_node: number;
  stops_ridden: number;
  ms: number;
  path: number[];
  text: string;
}

export interface Journey {
  from: number;
  to: number;
  found: boolean;
  total_ms: number;
  transfers: number;
  walk_ms: number;
  legs: Leg[];
  a_star_expanded: number;
  dijkstra_expanded: number;
}

export type Algorithm = "astar" | "dijkstra";

export interface SearchTrace {
  algorithm: Algorithm;
  from: number;
  to: number;
  order: number[];
  cost_ms: number[];
  settled: number;
  truncated: boolean;
}

export interface BuildStep {
  kind: "hub" | "extend" | "accept" | "exclude";
  line: number;
  stop: number;
  path: number[];
  gain: number;
}

export interface SampleJourney { journey: Journey; a_star: SearchTrace; dijkstra: SearchTrace }

/** Where the data comes from. Live can answer any question; the bundle only what it saved. */
export interface Source {
  kind: "live" | "bundle";
  world(): Promise<World>;
  network(): Promise<Network>;
  build(): Promise<BuildStep[]>;
  journey(from: number, to: number, boardWaitMinutes: number): Promise<Journey>;
  trace(from: number, to: number, algorithm: Algorithm): Promise<SearchTrace>;
  samples(): Promise<SampleJourney[]>;
  reload?(time: string): Promise<void>;
}

/** A failure worth showing as-is: the server's own error message, or ours. */
export class SourceError extends Error {}

async function json<T>(response: Response): Promise<T> {
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    const message = body?.error?.message ?? `HTTP ${response.status}`;
    throw new SourceError(message);
  }
  return body as T;
}

export function liveSource(base = ""): Source {
  const get = <T,>(path: string) => fetch(base + path).then((r) => json<T>(r));
  return {
    kind: "live",
    world: () => get<World>("/api/world"),
    network: () => get<Network>("/api/network"),
    build: () => get<{ steps: BuildStep[] }>("/api/trace/build").then((b) => b.steps),
    journey: (from, to, wait) => get<Journey>(`/api/journey?from=${from}&to=${to}&board_wait=${wait}`),
    trace: (from, to, algorithm) => get<SearchTrace>(`/api/trace/search?from=${from}&to=${to}&algo=${algorithm}`),
    samples: async () => [],
    reload: async (time) => {
      await fetch(base + "/api/reload", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ time }),
      }).then((r) => json(r));
    },
  };
}

/**
 * The saved bundle. It holds the city and a handful of worked journeys; a
 * journey it did not save cannot be planned without the server, and says so.
 */
export function bundleSource(base = "bundle/"): Source {
  const get = <T,>(file: string) => fetch(base + file).then((r) => json<T>(r));
  let samples: Promise<SampleJourney[]> | null = null;
  const loadSamples = () => (samples ??= get<SampleJourney[]>("journeys.json"));
  const find = async (from: number, to: number) => {
    const all = await loadSamples();
    const hit = all.find((s) => s.journey.from === from && s.journey.to === to);
    if (!hit) {
      throw new SourceError(
        "Offline, only the saved example journeys can be shown. Run `./bus serve` to plan any journey.");
    }
    return hit;
  };
  return {
    kind: "bundle",
    world: () => get<World>("world.json"),
    network: () => get<Network>("network.json"),
    build: () => get<{ steps: BuildStep[] }>("build.json").then((b) => b.steps),
    journey: async (from, to) => (await find(from, to)).journey,
    trace: async (from, to, algorithm) => {
      const hit = await find(from, to);
      return algorithm === "astar" ? hit.a_star : hit.dijkstra;
    },
    samples: loadSamples,
  };
}

/** Live if `./bus serve` answers, else the bundle, else a clear failure. */
export async function connect(): Promise<Source> {
  try {
    const health = await fetch("/api/health", { signal: AbortSignal.timeout(2500) });
    if (health.ok && (await health.json())?.ok) {
      return liveSource();
    }
  } catch {
    // no server; try the bundle
  }
  // A HEAD alone is not enough: dev servers answer a missing file with their
  // index page and a 200, so the type has to say JSON.
  const bundled = await fetch("bundle/world.json", { method: "HEAD" }).catch(() => null);
  if (bundled?.ok && (bundled.headers.get("content-type") ?? "").includes("json")) {
    return bundleSource();
  }
  throw new SourceError(
    "Nothing to show yet. Start `./bus serve` next to a running simulator, or write an offline bundle with " +
      "`./bus export --bundle --out dashboard/public`.");
}
