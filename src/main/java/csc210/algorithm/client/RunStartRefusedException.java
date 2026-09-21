package csc210.algorithm.client;

import java.io.IOException;

/**
 * DSTNS 2.0 answers {@code 403 CLI_START_REQUIRED} to a start or prepare that
 * does not carry the operator's credential. That is a policy, not a fault: the
 * server is telling us runs are started by the operator, not by API clients.
 * It is its own type so callers can say so plainly instead of reporting the
 * sandbox as unreachable.
 */
public final class RunStartRefusedException extends IOException {

    public RunStartRefusedException(String message) {
        super(message);
    }
}
