import { useState } from "react";
import type { BuildStep, Journey, Leg, SampleJourney } from "./api";
import { Bus, Change, Clock, Dice, Pause, Pin, Play, Swap, Walk } from "./Icons";
import { lineColour, minutes, percent, type Scene } from "./scene";

// ---- Algorithm 1: the network ------------------------------------------------

export interface BuildReplay { steps: BuildStep[]; shown: number; playing: boolean }

export function NetworkPanel(props: {
  scene: Scene;
  focusLine: number | null;
  onFocus(line: number | null): void;
  replay: BuildReplay | null;
  onReplay(action: "start" | "toggle" | "stop" | { seek: number }): void;
}) {
  const { scene, focusLine, onFocus, replay, onReplay } = props;
  const { network, world } = scene;
  const p = world.provenance;
  const target = p.budget.target_coverage;

  return (
    <>
      <h2 className="section-title">What Algorithm 1 built</h2>
      <p className="lede">
        Greedy demand covering: start each line at the busiest stop not yet served, and keep extending it to
        whichever stop adds the most demand per extra minute of driving, until the time budget runs out.
      </p>
      <dl className="figures">
        <div className="figure accent"><dt>Demand covered</dt><dd>{percent(network.coverage)}<small>%</small></dd></div>
        <div className="figure"><dt>Lines</dt><dd>{network.lines.length}<small>/{p.budget.max_lines}</small></dd></div>
        <div className="figure"><dt>Stops</dt><dd>{p.stops}</dd></div>
      </dl>
      <div className="coverage-bar" role="img"
           aria-label={`${percent(network.coverage)}% covered against a ${percent(target)}% target`}>
        <span style={{ width: `${Math.min(100, network.coverage * 100)}%` }} />
      </div>
      <p className="note">
        {capitalise(network.stop_reason)}.
        {p.stops_in_pockets > 0 &&
          ` ${p.stops_in_pockets} stop${p.stops_in_pockets === 1 ? " sits" : "s sit"} in one-way pockets at the map's edge, where no line can call and come back; they are drawn hollow.`}
      </p>

      <h2 className="section-title">
        Lines
        {focusLine !== null && <button className="link-btn" onClick={() => onFocus(null)}>Show all</button>}
      </h2>
      <ul className="lines">
        {network.lines.map((line) => (
          <li key={line.id}>
            <button
              className="line-row"
              aria-pressed={focusLine === line.id}
              style={{ "--line-color": lineColour(line.id) } as React.CSSProperties}
              onClick={() => onFocus(focusLine === line.id ? null : line.id)}
            >
              <span className="line-chip">{line.label}</span>
              <span className="line-meta">
                <span>
                  {line.outbound.stops.length} stops · {minutes(line.outbound.travel_ms)} min out
                  {line.back ? ` · ${minutes(line.back.travel_ms)} back` : " · one way"}
                </span>
                <span className="share"><i style={{ width: `${Math.min(100, line.share * 100)}%` }} /></span>
              </span>
              <span className="line-share">{percent(line.share)}%</span>
            </button>
          </li>
        ))}
      </ul>

      <h2 className="section-title">Watch it being built</h2>
      {replay ? (
        <>
          <div className="scrubber">
            <button className="btn" onClick={() => onReplay("toggle")} aria-label={replay.playing ? "Pause" : "Play"}>
              {replay.playing ? <Pause /> : <Play />}
            </button>
            <input type="range" min={0} max={replay.steps.length} value={replay.shown}
                   aria-label="Construction step" onChange={(e) => onReplay({ seek: Number(e.target.value) })} />
            <output className="mono">{replay.shown}/{replay.steps.length}</output>
          </div>
          <p className="step-caption" aria-live="polite">{describeStep(replay.steps[replay.shown - 1], scene)}</p>
          <button className="link-btn" onClick={() => onReplay("stop")}>Back to the finished network</button>
        </>
      ) : (
        <>
          <p className="lede">Replays every choice the builder made — each hub, each extension, each line accepted.</p>
          <button className="btn primary" onClick={() => onReplay("start")}><Play /> Replay the construction</button>
        </>
      )}

      <h2 className="section-title">Where the demand comes from</h2>
      <p className="note">
        {p.demand_source === "CATCHMENT"
          ? `${p.generators_assigned.toLocaleString()} of ${p.generators_active.toLocaleString()} active trip generators — schools, offices, shops — each credited to its nearest stop within its own reach. Stops are sized and coloured by what they draw.`
          : p.demand_source === "BUILDINGS"
            ? "Summed from building junctions near each stop: this simulator sends no trip generators."
            : "No demand data was found, so every stop counts the same and the network is designed for reach alone."}
      </p>
    </>
  );
}

function describeStep(step: BuildStep | undefined, scene: Scene): string {
  if (!step) return "The empty city, before any line.";
  const label = `R${step.line}`;
  switch (step.kind) {
    case "hub":
      return `${label} starts at stop ${step.stop}, the busiest stop nothing covers yet.`;
    case "extend":
      return `${label} extends to stop ${step.stop}, reaching ${step.gain.toFixed(1)} more demand.`;
    case "accept": {
      const share = scene.network.total_demand > 0 ? step.gain / scene.network.total_demand : 0;
      return `${label} is accepted, covering ${percent(share)}% of all demand.`;
    }
    case "exclude":
      return `Nothing could be built from stop ${step.stop} within the budget, so it stays uncovered.`;
  }
}

function capitalise(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

// ---- Algorithm 2: a journey ----------------------------------------------------

export interface SearchReplay { playing: boolean; progress: number; astar: number[]; dijkstra: number[] }

export function JourneyPanel(props: {
  scene: Scene;
  offline: boolean;
  endpoints: { from: number | null; to: number | null };
  onEndpoints(e: { from: number | null; to: number | null }): void;
  picking: "from" | "to" | null;
  onPicking(p: "from" | "to" | null): void;
  journey: Journey | null;
  busy: boolean;
  error: string | null;
  onPlan(boardWaitMinutes: number): void;
  samples: SampleJourney[];
  search: SearchReplay | null;
  onSearch(action: "start" | "toggle" | "stop"): void;
}) {
  const { scene, offline, endpoints, onEndpoints, picking, onPicking, journey, busy, error, onPlan, samples, search, onSearch } = props;
  const [wait, setWait] = useState("0");
  const junctions = scene.world.junctions.length;
  const valid = (n: number | null) => n !== null && n >= 0 && n < junctions;

  const random = () => {
    const main = scene.world.stops.filter((s) => s.main && s.lines.length > 0);
    if (main.length < 2) return;
    const a = main[Math.floor(Math.random() * main.length)].node;
    let b = a;
    while (b === a) b = main[Math.floor(Math.random() * main.length)].node;
    onEndpoints({ from: a, to: b });
  };

  const field = (which: "from" | "to", label: string, colour: string) => (
    <div className="field">
      <label htmlFor={which}><i style={{ background: colour }} />{label}</label>
      <div className="input-wrap">
        <input id={which} inputMode="numeric" placeholder="junction"
               value={endpoints[which] ?? ""} readOnly={offline}
               onChange={(e) => {
                 const n = e.target.value === "" ? null : Number(e.target.value.replace(/\D/g, ""));
                 onEndpoints({ ...endpoints, [which]: n });
               }} />
        {!offline && (
          <button className="pick" aria-pressed={picking === which} title={`Pick the ${label.toLowerCase()} on the map`}
                  onClick={() => onPicking(picking === which ? null : which)}>
            <Pin />
          </button>
        )}
      </div>
    </div>
  );

  return (
    <>
      <h2 className="section-title">Plan a journey with Algorithm 2</h2>
      <p className="lede">
        A* over the passenger network: a vertex is a stop on one line, so changing lines costs the wait it really
        costs. It is guided by straight-line distance at the fastest speed anything on the map moves, which never
        overestimates — so it finds the cheapest journey, and does less work than Dijkstra to prove it.
      </p>

      {offline ? (
        <div className="empty">
          <strong>Offline: saved journeys only</strong>
          Planning a new journey needs <code className="mono">./bus serve</code>. These were saved with the bundle:
          <div className="samples">
            {samples.map((s) => (
              <button key={`${s.journey.from}-${s.journey.to}`} className="sample"
                      onClick={() => onEndpoints({ from: s.journey.from, to: s.journey.to })}>
                {s.journey.from} → {s.journey.to}
              </button>
            ))}
          </div>
        </div>
      ) : (
        <>
          <div className="endpoints">
            {field("from", "From", "var(--mint)")}
            <button className="btn swap" aria-label="Swap from and to"
                    onClick={() => onEndpoints({ from: endpoints.to, to: endpoints.from })}><Swap /></button>
            {field("to", "To", "var(--cyan)")}
          </div>
          {picking && <p className="note">Click a junction on the map for the {picking === "from" ? "start" : "destination"}.</p>}
          <div className="row spread actions">
            <div className="row">
              <button className="btn primary" disabled={busy || !valid(endpoints.from) || !valid(endpoints.to)}
                      onClick={() => onPlan(Number(wait) || 0)}>
                {busy ? "Planning…" : "Plan journey"}
              </button>
              <button className="btn" onClick={random} title="Two random served stops"><Dice /> Random</button>
            </div>
            <label className="wait-field" title="Minutes to wait for the first bus; the simulator publishes no timetable">
              <Clock /> wait
              <input className="mono" value={wait} onChange={(e) => setWait(e.target.value.replace(/[^\d.]/g, ""))}
                     aria-label="Minutes to wait for the first bus" /> min
            </label>
          </div>
        </>
      )}

      {error && <p className="error" role="alert">{error}</p>}

      {journey && (journey.found ? <Itinerary journey={journey} /> : (
        <div className="empty spaced">
          <strong>No journey</strong>
          Nothing joins junction {journey.from} to {journey.to}: one end is too far from any served stop to walk,
          and walking all the way is further than a passenger is assumed to go.
        </div>
      ))}

      {journey?.found && (
        <>
          <h2 className="section-title">A* against Dijkstra, same question</h2>
          <div className="versus">
            <dl className="figure astar"><dt><i style={{ background: "var(--cyan)" }} />A* settled</dt><dd>{journey.a_star_expanded}</dd></dl>
            <dl className="figure dijkstra"><dt><i style={{ background: "var(--amber)" }} />Dijkstra settled</dt><dd>{journey.dijkstra_expanded}</dd></dl>
          </div>
          <p className="note">
            Both find the same {minutes(journey.total_ms)} min.{" "}
            {journey.dijkstra_expanded > 0 &&
              `A* settled ${percent(1 - journey.a_star_expanded / journey.dijkstra_expanded)}% fewer vertices to be sure of it.`}
          </p>
          <div className="row actions">
            {search ? (
              <>
                <button className="btn" onClick={() => onSearch("toggle")}>{search.playing ? <><Pause /> Pause</> : <><Play /> Play</>}</button>
                <button className="link-btn" onClick={() => onSearch("stop")}>Hide the searches</button>
              </>
            ) : (
              <button className="btn" onClick={() => onSearch("start")}><Play /> Watch both searches</button>
            )}
          </div>
        </>
      )}
    </>
  );
}

const LEG_ICON: Record<Leg["kind"], React.ReactNode> = {
  ACCESS: <Walk />, EGRESS: <Walk />, WALK: <Walk />, WALK_DIRECT: <Walk />, WAIT: <Clock />, TRANSFER: <Change />, RIDE: <Bus />,
};

function Itinerary({ journey }: { journey: Journey }) {
  const legs = journey.legs.filter((l) => !(l.ms === 0 && (l.kind === "ACCESS" || l.kind === "EGRESS")));
  const saved = journey.walk_ms - journey.total_ms;
  return (
    <>
      <h2 className="section-title">The journey</h2>
      <dl className="figures">
        <div className="figure accent"><dt>Door to door</dt><dd>{minutes(journey.total_ms)}<small>min</small></dd></div>
        <div className="figure"><dt>Walking</dt><dd>{minutes(journey.walk_ms)}<small>min</small></dd></div>
        <div className="figure"><dt>Changes</dt><dd>{journey.transfers}</dd></div>
      </dl>
      <p className="note">
        {saved > 30_000 ? `${minutes(saved)} min quicker than walking straight there.` : "Walking is as quick, so that is the answer."}
      </p>
      <ol className="legs">
        {legs.map((leg, i) => {
          const id = leg.line ? Number(leg.line.slice(1)) : -1;
          return (
            <li key={i} className={"leg" + (leg.kind === "RIDE" ? " ride" : "")}
                style={id >= 0 ? ({ "--line-color": lineColour(id) } as React.CSSProperties) : undefined}>
              <span className="leg-icon">{LEG_ICON[leg.kind]}</span>
              <span className="leg-text">{leg.text}</span>
              <span className="leg-time">{minutes(leg.ms)}′</span>
            </li>
          );
        })}
      </ol>
    </>
  );
}
