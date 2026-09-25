import { useCallback, useEffect, useRef, useState } from "react";
import { connect, type Journey, type SampleJourney, type Source, SourceError } from "./api";
import { Frame, Mark, Minus, Plus, Refresh } from "./Icons";
import { type Hover, type Layers, MapCanvas, type MapHandle } from "./MapCanvas";
import { type BuildReplay, JourneyPanel, NetworkPanel, type SearchReplay } from "./Panels";
import { buildScene, type Scene } from "./scene";

type Tab = "network" | "journey";
type Endpoints = { from: number | null; to: number | null };

const PANEL_INSET = 416;
const BUILD_STEP_MS = 420;
const SEARCH_MS = 5200;

export function App() {
  const [source, setSource] = useState<Source | null>(null);
  const [scene, setScene] = useState<Scene | null>(null);
  const [fatal, setFatal] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("network");
  const [focusLine, setFocusLine] = useState<number | null>(null);
  const [layers, setLayers] = useState<Layers>({ congestion: false, coverage: false, buses: true });
  const [endpoints, setEndpoints] = useState<Endpoints>({ from: null, to: null });
  const [picking, setPicking] = useState<"from" | "to" | null>(null);
  const [journey, setJourney] = useState<Journey | null>(null);
  const [journeyError, setJourneyError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [samples, setSamples] = useState<SampleJourney[]>([]);
  const [build, setBuild] = useState<BuildReplay | null>(null);
  const [search, setSearch] = useState<SearchReplay | null>(null);
  const [hover, setHover] = useState<Hover | null>(null);
  const [time, setTime] = useState("");
  const [reloading, setReloading] = useState(false);
  const map = useRef<MapHandle>(null);

  const load = useCallback(async (from: Source) => {
    const [world, network] = await Promise.all([from.world(), from.network()]);
    setScene(buildScene(world, network));
    setTime(world.provenance.virtual_time ?? "");
    setSamples(await from.samples());
  }, []);

  useEffect(() => {
    connect()
      .then(async (s) => {
        setSource(s);
        await load(s);
      })
      .catch((e) => setFatal(e instanceof SourceError ? e.message : String(e)));
  }, [load]);

  // ---- journeys ----
  const plan = useCallback(async (from: number, to: number, wait: number) => {
    if (!source) return;
    setBusy(true);
    setJourneyError(null);
    setSearch(null);
    try {
      const answer = await source.journey(from, to, wait);
      setJourney(answer);
      setFocusLine(null);
      if (answer.found) map.current?.fitNodes(answer.legs.flatMap((l) => l.path));
    } catch (e) {
      setJourney(null);
      setJourneyError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [source]);

  // Offline, choosing a saved example plans it straight away.
  const chooseEndpoints = (next: Endpoints) => {
    setEndpoints(next);
    setJourney(null);
    setSearch(null);
    if (source?.kind === "bundle" && next.from !== null && next.to !== null) void plan(next.from, next.to, 0);
  };

  const pick = (node: number) => {
    if (!picking) return;
    const next = { ...endpoints, [picking]: node };
    setEndpoints(next);
    setJourney(null);
    setSearch(null);
    setPicking(picking === "from" && next.to === null ? "to" : null);
  };

  // ---- the construction replay ----
  const onReplay = async (action: "start" | "toggle" | "stop" | { seek: number }) => {
    if (action === "start" && source) {
      setJourney(null);
      setSearch(null);
      setFocusLine(null);
      const steps = await source.build();
      setBuild({ steps, shown: 0, playing: true });
      map.current?.fitAll();
    } else if (action === "toggle") {
      setBuild((b) => b && { ...b, playing: !b.playing, shown: b.shown >= b.steps.length ? 0 : b.shown });
    } else if (action === "stop") {
      setBuild(null);
    } else if (typeof action === "object") {
      setBuild((b) => b && { ...b, shown: action.seek, playing: false });
    }
  };

  useEffect(() => {
    if (!build?.playing) return;
    const timer = window.setInterval(() => {
      setBuild((b) => {
        if (!b) return b;
        const shown = b.shown + 1;
        return { ...b, shown: Math.min(shown, b.steps.length), playing: shown < b.steps.length };
      });
    }, BUILD_STEP_MS);
    return () => window.clearInterval(timer);
  }, [build?.playing]);

  // ---- the search replay ----
  const onSearch = async (action: "start" | "toggle" | "stop") => {
    if (action === "stop") {
      setSearch(null);
      return;
    }
    if (action === "toggle") {
      setSearch((s) => s && { ...s, playing: !s.playing, progress: s.progress >= 1 ? 0 : s.progress });
      return;
    }
    if (!source || !journey) return;
    try {
      const [a, d] = await Promise.all([source.trace(journey.from, journey.to, "astar"),
        source.trace(journey.from, journey.to, "dijkstra")]);
      setSearch({ playing: true, progress: 0, astar: a.order, dijkstra: d.order });
      map.current?.fitNodes([...a.order, ...d.order]);
    } catch (e) {
      setJourneyError(e instanceof Error ? e.message : String(e));
    }
  };

  useEffect(() => {
    if (!search?.playing) return;
    let frame = 0;
    let last = performance.now();
    const step = (now: number) => {
      const dt = now - last;
      last = now;
      setSearch((s) => {
        if (!s) return s;
        const progress = Math.min(1, s.progress + dt / SEARCH_MS);
        return { ...s, progress, playing: progress < 1 };
      });
      frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [search?.playing]);

  // ---- re-plan at another time (live only) ----
  const replan = async () => {
    if (!source?.reload || !/^([01]\d|2[0-3]):[0-5]\d:[0-5]\d$/.test(time)) return;
    setReloading(true);
    try {
      await source.reload(time);
      await load(source);
      setJourney(null);
      setSearch(null);
      setBuild(null);
    } catch (e) {
      setJourneyError(e instanceof Error ? e.message : String(e));
    } finally {
      setReloading(false);
    }
  };

  if (fatal) {
    return (
      <main className="loading">
        <div className="skeleton-map" />
        <section className="status-card hud" role="alert">
          <h2>No planned city to show</h2>
          <p>{fatal}</p>
          <p>With the simulator running: <code>./bus serve --time 18:00:00</code>, then reload this page.</p>
          <button className="btn primary" onClick={() => location.reload()}><Refresh /> Try again</button>
        </section>
      </main>
    );
  }

  if (!scene || !source) {
    return (
      <main className="loading" aria-busy="true" aria-label="Loading the planned city">
        <div className="skeleton-map" />
        <div className="skeleton-panel hud">
          <div className="shimmer" style={{ width: "40%" }} />
          <div className="shimmer" style={{ width: "90%" }} />
          <div className="shimmer" style={{ width: "75%" }} />
          <div className="shimmer" style={{ height: 64 }} />
          <div className="shimmer" style={{ width: "60%" }} />
        </div>
      </main>
    );
  }

  const p = scene.world.provenance;
  const hoveredStop = hover ? scene.world.stops.find((s) => s.node === hover.node) : undefined;

  return (
    <>
      <MapCanvas
        ref={map}
        scene={scene}
        rightInset={PANEL_INSET}
        onPick={pick}
        onHover={setHover}
        overlay={{
          focusLine,
          journey: tab === "journey" ? journey : null,
          endpoints: tab === "journey" ? endpoints : { from: null, to: null },
          search: tab === "journey" && search ? search : null,
          build: tab === "network" && build ? { steps: build.steps, shown: build.shown } : null,
          layers,
          picking: tab === "journey" ? picking : null,
        }}
      />
      <div className="grain" aria-hidden="true" />

      <header className="topbar">
        <div className="brand hud">
          <Mark />
          <div>
            <h1>Route planner</h1>
            <p>CSC210 · bus network and journeys</p>
          </div>
        </div>
        <section className="provenance hud" aria-label="Which simulation this shows">
          <span className={"badge " + source.kind}>{source.kind === "live" ? "Live" : "Offline"}</span>
          <dl>
            <div><dt>City</dt><dd>{p.city ?? "—"}{p.country ? `, ${p.country}` : ""}</dd></div>
            <div><dt>Time</dt><dd>{p.virtual_time ?? "—"}</dd></div>
            <div><dt>Seed</dt><dd>{shortSeed(p.seed)}</dd></div>
            <div><dt>Map</dt><dd>{scene.world.junctions.length.toLocaleString()} junctions</dd></div>
            <div><dt>Demand</dt><dd>{p.demand_source.toLowerCase()}</dd></div>
          </dl>
        </section>
        {source.reload && (
          <form className="time-form hud" onSubmit={(e) => { e.preventDefault(); void replan(); }}>
            <label htmlFor="time">Plan for</label>
            <input id="time" value={time} onChange={(e) => setTime(e.target.value)} pattern="([01]\d|2[0-3]):[0-5]\d:[0-5]\d"
                   aria-describedby="time-hint" />
            <button className="btn" type="submit" disabled={reloading}><Refresh />{reloading ? "Re-planning…" : "Re-plan"}</button>
            <span id="time-hint" hidden>Virtual time as HH:MM:SS; the simulator is paused and read at that moment.</span>
          </form>
        )}
      </header>

      <aside id="panel" className="panel hud" aria-label="Controls">
        <div className="tabs" role="tablist">
          <button className="tab" role="tab" aria-selected={tab === "network"} onClick={() => { setTab("network"); setPicking(null); }}>
            <small>Algorithm 1</small><strong>The network</strong>
          </button>
          <button className="tab" role="tab" aria-selected={tab === "journey"} onClick={() => { setTab("journey"); setBuild(null); }}>
            <small>Algorithm 2</small><strong>A journey</strong>
          </button>
        </div>
        <div className="panel-body" role="tabpanel">
          {tab === "network" ? (
            <NetworkPanel
              scene={scene}
              focusLine={focusLine}
              onFocus={(id) => {
                setFocusLine(id);
                if (id !== null) {
                  const line = scene.network.lines.find((l) => l.id === id)!;
                  map.current?.fitNodes([...line.outbound.nodes, ...(line.back?.nodes ?? [])]);
                } else {
                  map.current?.fitAll();
                }
              }}
              replay={build}
              onReplay={onReplay}
            />
          ) : (
            <JourneyPanel
              scene={scene}
              offline={source.kind === "bundle"}
              endpoints={endpoints}
              onEndpoints={chooseEndpoints}
              picking={picking}
              onPicking={setPicking}
              journey={journey}
              busy={busy}
              error={journeyError}
              onPlan={(wait) => endpoints.from !== null && endpoints.to !== null && void plan(endpoints.from, endpoints.to, wait)}
              samples={samples}
              search={search}
              onSearch={onSearch}
            />
          )}
        </div>
      </aside>

      <section className="legend hud" aria-label="Legend and layers">
        <span><svg viewBox="0 0 26 10"><path d="M1 5h24" stroke="#7aa2f7" strokeWidth="3.4" strokeLinecap="round" /></svg>Bus line</span>
        <span><svg viewBox="0 0 26 10"><path d="M1 5h24" stroke="#37d7ff" strokeWidth="4" strokeLinecap="round" /></svg>Your ride</span>
        <span><svg viewBox="0 0 26 10"><circle cx="13" cy="5" r="4" fill="rgb(255 185 56)" /></svg>Stop, by demand</span>
        <span><svg viewBox="0 0 26 10"><path d="M1 5h24" stroke="#3fe0b8" strokeWidth="2.4" strokeDasharray="2 6" strokeLinecap="round" /></svg>Walk</span>
        <label className="toggle"><span><input type="checkbox" checked={layers.buses} onChange={(e) => setLayers({ ...layers, buses: e.target.checked })} />Moving buses</span></label>
        <label className="toggle"><span><input type="checkbox" checked={layers.congestion} onChange={(e) => setLayers({ ...layers, congestion: e.target.checked })} />Live traffic</span></label>
        <label className="toggle"><span><input type="checkbox" checked={layers.coverage} onChange={(e) => setLayers({ ...layers, coverage: e.target.checked })} />Stop reach</span></label>
      </section>

      <nav className="map-controls hud" aria-label="Map">
        <button onClick={() => map.current?.zoomBy(1.6)} aria-label="Zoom in"><Plus /></button>
        <button onClick={() => map.current?.zoomBy(1 / 1.6)} aria-label="Zoom out"><Minus /></button>
        <button onClick={() => map.current?.fitAll()} aria-label="Show the whole city"><Frame /></button>
      </nav>

      {hover && (
        <div className="tooltip hud" style={{ left: hover.x, top: hover.y }}>
          {hoveredStop ? (
            <>Stop <b>{hover.node}</b> · demand {hoveredStop.demand.toFixed(1)}<br />
              {hoveredStop.lines.length ? `Lines ${hoveredStop.lines.join(", ")}` : hoveredStop.main ? "No line calls here" : "In a one-way pocket"}</>
          ) : (
            <>Junction <b>{hover.node}</b>{picking ? ` · click to set ${picking === "from" ? "start" : "destination"}` : ""}</>
          )}
        </div>
      )}
    </>
  );
}

function shortSeed(seed: string | null): string {
  if (!seed) return "—";
  const trimmed = seed.replace(/^0x0*/, "0x");
  return trimmed === "0x" ? "0x0" : trimmed;
}
