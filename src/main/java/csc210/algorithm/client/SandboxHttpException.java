package csc210.algorithm.client;

import java.io.IOException;

/**
 * The simulator answered, but not with success. Unlike a refused connection
 * this means DSTNS is up; the status and its error code say what it objected
 * to — {@code 409 LIFECYCLE_CONFLICT} when a run is in the wrong state for the
 * request, {@code 410 TRANSIT_API_RETIRED} for an endpoint that no longer exists.
 */
public final class SandboxHttpException extends IOException {

    private final int status;
    private final String code;

    public SandboxHttpException(String method, String path, int status, String code, String detail) {
        super(method + " " + path + " returned HTTP " + status
                + (code == null ? "" : " " + code) + (detail == null || detail.isBlank() ? "" : ": " + detail));
        this.status = status;
        this.code = code;
    }

    /** The HTTP status the simulator answered with. */
    public int status() {
        return status;
    }

    /** DSTNS's own {@code error.code}, e.g. {@code "LIFECYCLE_CONFLICT"}, or {@code null} if the body had none. */
    public String code() {
        return code;
    }

    /** Whether the simulator refused because its run was in the wrong state. */
    public boolean isLifecycleConflict() {
        return status == 409;
    }
}
