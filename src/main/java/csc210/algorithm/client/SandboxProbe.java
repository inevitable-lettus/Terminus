package csc210.algorithm.client;

/**
 * Manual smoke test for step 1.2: hit the live sandbox and print what came
 * back. Not a JUnit test — {@link SandboxClient} has no fixture yet to run
 * against offline (that's step 1.4). Run with the sandbox up:
 * {@code ./gradlew run -PmainClass=csc210.algorithm.client.SandboxProbe}
 */
public final class SandboxProbe {

    private SandboxProbe() {}

    public static void main(String[] args) throws Exception {
        SandboxClient client = new SandboxClient("http://127.0.0.1:8090");

        HealthStatus health = client.health();
        System.out.println("health: " + health);

        TopologyData topology = client.topology();
        System.out.println("nodes: " + topology.nodes().size());
        System.out.println("edges: " + topology.edges().size());
        System.out.println("graphHash: " + topology.graphHash());
        System.out.println("first node: " + topology.nodes().get(0));
        System.out.println("first edge: " + topology.edges().get(0));
    }
}
