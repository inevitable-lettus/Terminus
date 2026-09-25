package csc210.algorithm.client;

/**
 * The simulator is up and answering, but its run is in a state this command
 * cannot work with: nothing running, the run in {@code ERROR}, a start refused
 * because another run is still going. Not a fault on either side — the message
 * says what to change — so it is its own type, and its own exit status.
 */
public final class RunStateException extends IllegalStateException {

    public RunStateException(String message) {
        super(message);
    }
}
