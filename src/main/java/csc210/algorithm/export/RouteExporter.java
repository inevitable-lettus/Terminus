package csc210.algorithm.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Edge;
import csc210.algorithm.model.Position;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteNetwork;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the generated lines to files, so they can be looked at and reused
 * without any simulator running.
 *
 * <p><strong>This replaces publishing them back.</strong> Until DSTNS 2.0 the
 * lines were sent to {@code POST /control/transit/route} so they rendered in the
 * simulator's own view. That endpoint is retired (HTTP 410, "no longer
 * supported"), so the output now goes to two files: {@code routes.json}, which is
 * everything the algorithm produced, and {@code routes.geojson}, which any map
 * viewer can draw.
 *
 * <p><strong>What survived from publishing, and why.</strong> The server's old
 * validator required every consecutive pair of nodes to be joined by one directed
 * edge, with no path-filling between stops. That rule is not gone with the
 * endpoint — it is simply a true statement about what a drivable route is — so
 * the same check runs here before anything is written: a file claiming a route
 * that cannot be driven would be worse than no file. The return leg is likewise
 * checked rather than assumed, because a one-way street makes a line unable to
 * retrace its own path, and that is an answer rather than an error.
 *
 * <p>The output is deterministic: no timestamps, fixed key order, so the same
 * network always produces byte-identical files and a change in them means the
 * result changed.
 */
public final class RouteExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Graph roadGraph;
    private final CostModel costModel;

    /**
     * An exporter that trusts every reverse twin to be drivable — correct only
     * on a map with no one-way streets. Prefer {@link #RouteExporter(Graph, CostModel)}
     * against live data.
     */
    public RouteExporter(Graph roadGraph) {
        this(roadGraph, null);
    }

    /**
     * An exporter that checks the return direction against {@code costModel}
     * before claiming a line can be driven back.
     *
     * @param costModel the same model the routes were planned with, so a road
     *        excluded there is excluded here too; {@code null} trusts every
     *        reverse twin
     */
    public RouteExporter(Graph roadGraph, CostModel costModel) {
        this.roadGraph = roadGraph;
        this.costModel = costModel;
    }

    /**
     * What identifies the run the network was built from, written into the file
     * so a result can be traced back to the map and moment it describes.
     * Any field may be {@code null} when it is not known.
     */
    public record Meta(String seed, String virtualTime, String graphHash, String demandSource,
                       int junctions, int roads, int stops) {}

    /** What {@link #write} produced. */
    public record Written(Path json, Path geojson, int routes, List<String> oneWayLines) {}

    /**
     * Writes {@code routes.json} and {@code routes.geojson} into {@code directory},
     * creating it if needed.
     *
     * @throws IllegalStateException if any route is not a continuous traversal;
     *         nothing is written in that case
     */
    public Written write(RouteNetwork network, Meta meta, Path directory) throws IOException {
        // Both documents are built before either file is touched, so a route that
        // fails the continuity check leaves the directory exactly as it was.
        String json = toJson(network, meta);
        String geojson = toGeoJson(network);

        Files.createDirectories(directory);
        Path jsonPath = directory.resolve("routes.json");
        Path geojsonPath = directory.resolve("routes.geojson");
        Files.writeString(jsonPath, json, StandardCharsets.UTF_8);
        Files.writeString(geojsonPath, geojson, StandardCharsets.UTF_8);
        return new Written(jsonPath, geojsonPath, network.routeCount(), oneWayLines(network));
    }

    /** Everything the algorithm produced, as JSON. */
    public String toJson(RouteNetwork network, Meta meta) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode world = root.putObject("world");
        world.put("seed", meta.seed());
        world.put("virtual_time", meta.virtualTime());
        world.put("graph_hash", meta.graphHash());
        world.put("demand_source", meta.demandSource());
        world.put("junctions", meta.junctions());
        world.put("roads", meta.roads());
        world.put("stops", meta.stops());

        ObjectNode summary = root.putObject("network");
        summary.put("routes", network.routeCount());
        summary.put("coverage", round(network.coverage(), 6));
        summary.put("demand_covered", round(network.demandCovered(), 6));
        summary.put("total_demand", round(network.totalDemand(), 6));

        ArrayNode routes = root.putArray("routes");
        for (BusRoute route : network.routes()) {
            requireContinuous(route.nodes(), route.label() + " outbound");
            ObjectNode one = routes.addObject();
            one.put("label", route.label());
            one.set("stops", ints(route.stops()));
            one.set("nodes", ints(route.nodes()));
            one.set("edges", ints(route.edges()));
            one.put("travel_millis", route.travelMillis());
            one.put("demand_covered", round(route.demandCovered(), 6));
            if (canBeDrivenBack(route)) {
                int[] back = returnNodes(route);
                requireContinuous(back, route.label() + " return");
                one.put("both_ways", true);
                one.set("return_nodes", ints(back));
            } else {
                one.put("both_ways", false);
                one.putNull("return_nodes");
            }
        }
        return MAPPER.writeValueAsString(root) + "\n";
    }

    /**
     * The lines as GeoJSON: one {@code LineString} per route and one {@code Point}
     * per stop served, in {@code [longitude, latitude]} order as the format requires.
     */
    public String toGeoJson(RouteNetwork network) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "FeatureCollection");
        ArrayNode features = root.putArray("features");

        Map<Integer, List<String>> linesAtStop = new LinkedHashMap<>();
        for (BusRoute route : network.routes()) {
            requireContinuous(route.nodes(), route.label() + " outbound");
            ObjectNode feature = features.addObject();
            feature.put("type", "Feature");
            ObjectNode properties = feature.putObject("properties");
            properties.put("kind", "route");
            properties.put("label", route.label());
            properties.put("stops", route.stopCount());
            properties.put("travel_minutes", round(route.travelMillis() / 60_000.0, 2));
            properties.put("both_ways", canBeDrivenBack(route));
            ObjectNode geometry = feature.putObject("geometry");
            geometry.put("type", "LineString");
            ArrayNode coordinates = geometry.putArray("coordinates");
            for (int node : route.nodes()) {
                coordinates.add(coordinate(roadGraph.node(node).position()));
            }
            for (int stop : route.stops()) {
                linesAtStop.computeIfAbsent(stop, ignored -> new ArrayList<>()).add(route.label());
            }
        }

        for (Map.Entry<Integer, List<String>> entry : new java.util.TreeMap<>(linesAtStop).entrySet()) {
            ObjectNode feature = features.addObject();
            feature.put("type", "Feature");
            ObjectNode properties = feature.putObject("properties");
            properties.put("kind", "stop");
            properties.put("node", entry.getKey());
            properties.put("lines", String.join(",", entry.getValue()));
            ObjectNode geometry = feature.putObject("geometry");
            geometry.put("type", "Point");
            geometry.set("coordinates", coordinate(roadGraph.node(entry.getKey()).position()));
        }
        return MAPPER.writeValueAsString(root) + "\n";
    }

    /** Labels of the lines in {@code network} that can only be driven one way. */
    public List<String> oneWayLines(RouteNetwork network) {
        List<String> labels = new ArrayList<>();
        for (BusRoute route : network.routes()) {
            if (!canBeDrivenBack(route)) {
                labels.add(route.label());
            }
        }
        return labels;
    }

    private static ArrayNode coordinate(Position position) {
        ArrayNode pair = MAPPER.createArrayNode();
        pair.add(position.lon());
        pair.add(position.lat());
        return pair;
    }

    private static ArrayNode ints(int[] values) {
        ArrayNode array = MAPPER.createArrayNode();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }

    /**
     * Whether every segment of {@code route} has a reverse twin that traffic may
     * actually use.
     *
     * <p>The twin's <em>existence</em> is not enough. DSTNS fabricates a reverse
     * for every one-way street, so the id is always there; only the cost model
     * knows whether it is a road or a fiction.
     */
    public boolean canBeDrivenBack(BusRoute route) {
        for (int edgeId : route.edges()) {
            int twin = roadGraph.edge(edgeId).reverseTwin();
            if (twin < 0 || twin >= roadGraph.edgeCount()) {
                return false;
            }
            if (costModel != null && !costModel.isTraversable(twin)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The same line driven the other way.
     *
     * <p>A bus line runs in both directions; the builder emits one, so the
     * return path is the node list read backwards. Whether that path can be
     * driven at all is a property of the map rather than a law, so it is
     * checked rather than assumed — twice over, because DSTNS gives a one-way
     * street a reverse twin that exists as an id but not as a road. A line that
     * cannot retrace itself is a real answer, not a bug.
     *
     * <p>Use {@link #canBeDrivenBack} to ask without provoking an exception.
     *
     * @throws IllegalStateException if any segment has no usable reverse twin
     */
    public int[] returnNodes(BusRoute route) {
        int[] forward = route.nodes();
        for (int edgeId : route.edges()) {
            int twin = roadGraph.edge(edgeId).reverseTwin();
            if (twin < 0 || twin >= roadGraph.edgeCount()) {
                throw new IllegalStateException("edge " + edgeId + " on " + route.label()
                        + " has no reverse twin, so this line cannot be driven back along its own path");
            }
            if (costModel != null && !costModel.isTraversable(twin)) {
                throw new IllegalStateException("edge " + edgeId + " on " + route.label()
                        + " is one-way — its reverse twin " + twin + " is not a road traffic may use, "
                        + "so this line cannot be driven back along its own path");
            }
        }
        int[] back = new int[forward.length];
        for (int i = 0; i < forward.length; i++) {
            back[i] = forward[forward.length - 1 - i];
        }
        return back;
    }

    /**
     * Whether every consecutive pair of {@code nodes} is joined by a single
     * directed edge that traffic may use.
     */
    public boolean isContinuous(int[] nodes) {
        return firstGap(nodes) < 0;
    }

    /**
     * As {@link #isContinuous}, but throws naming the offending pair.
     *
     * @throws IllegalStateException if the traversal has a gap
     */
    public void requireContinuous(int[] nodes, String what) {
        int gap = firstGap(nodes);
        if (gap >= 0) {
            throw new IllegalStateException(what + " is not a continuous traversal: no directed edge joins node "
                    + nodes[gap] + " to node " + nodes[gap + 1] + " (position " + gap + ")");
        }
    }

    /** Index of the first node with no edge to its successor, or -1 if there is none. */
    private int firstGap(int[] nodes) {
        for (int i = 0; i + 1 < nodes.length; i++) {
            if (!joined(nodes[i], nodes[i + 1])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether traffic may actually get from {@code from} to {@code to} in one
     * hop. An edge the cost model excludes does not count: a fabricated reverse
     * is exactly the case where the graph says "joined" and the road network
     * says otherwise.
     */
    private boolean joined(int from, int to) {
        for (int edgeId : roadGraph.outgoingEdges(from)) {
            if (roadGraph.edge(edgeId).to() != to) {
                continue;
            }
            if (costModel == null || costModel.isTraversable(edgeId)) {
                return true;
            }
        }
        return false;
    }

    /** Every edge on this map that has no reverse twin, for reporting. */
    public List<Edge> edgesWithoutReverseTwin() {
        List<Edge> orphans = new ArrayList<>();
        for (int id = 0; id < roadGraph.edgeCount(); id++) {
            Edge edge = roadGraph.edge(id);
            if (edge.reverseTwin() < 0 || edge.reverseTwin() >= roadGraph.edgeCount()) {
                orphans.add(edge);
            }
        }
        return orphans;
    }
}
