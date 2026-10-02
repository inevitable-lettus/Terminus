package csc210.algorithm.cli;

/**
 * The command line itself was wrong: an unknown command, a missing value, a
 * junction id that is not on the map. Its own type so {@code Main} can tell
 * "you typed it wrong" (exit 2, with a pointer to {@code ./bus help}) from "the
 * simulator sent something broken", which is also an
 * {@link IllegalArgumentException} underneath but no fault of the user's.
 */
public final class UsageException extends IllegalArgumentException {

    public UsageException(String message) {
        super(message);
    }
}
