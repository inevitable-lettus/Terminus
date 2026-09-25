import { describe, expect, it } from "vitest";
import type { Network, World } from "./api";
import { buildScene, demandColour, lapSeconds, lineColour, LINE_COLOURS } from "./scene";

const world: World = {
  provenance: {} as World["provenance"],
  bounds: { min_x: 0, min_y: 0, max_x: 30, max_y: 10 },
  junctions: [0, 1, 2, 3].map((id) => ({ id, x: id * 10, y: 0, lat: 0, lon: 0, main: true })),
  roads: [],
  stops: [{ node: 0, demand: 4, main: true, lines: ["R0"] }, { node: 3, demand: 1, main: true, lines: ["R0"] }],
};

const network: Network = {
  coverage: 1, demand_covered: 5, total_demand: 5, stop_reason: "", interchanges: [], connected: true,
  lines: [{
    id: 0, label: "R0", demand: 5, share: 1,
    outbound: { nodes: [0, 1, 2, 3], stops: [0, 3], travel_ms: 60000 },
    back: { nodes: [3, 2, 1, 0], stops: [3, 0], travel_ms: 60000 },
  }],
};

describe("scene", () => {
  it("joins outbound and return into one loop without repeating the terminus", () => {
    const scene = buildScene(world, network);
    const line = scene.lines[0];
    expect(line.loop.points).toHaveLength(7);
    expect(line.loop.length).toBe(60);
    expect(scene.maxDemand).toBe(4);
  });

  it("a one-way line's loop is just its outbound path", () => {
    const oneWay = { ...network, lines: [{ ...network.lines[0], back: null }] };
    expect(buildScene(world, oneWay).lines[0].loop.length).toBe(30);
  });

  it("cycles through the palette and keeps lap times watchable", () => {
    expect(lineColour(LINE_COLOURS.length)).toBe(lineColour(0));
    expect(lapSeconds(1000)).toBe(8);
    expect(lapSeconds(10 * 3_600_000)).toBe(40);
  });

  it("ramps demand from pale to red", () => {
    expect(demandColour(0)).toBe("rgb(220 232 242)");
    expect(demandColour(1)).toBe("rgb(255 91 101)");
  });
});
