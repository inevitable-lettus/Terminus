package csc210.algorithm.cli;

/**
 * Which simulation a result describes, for the header of every printed report.
 *
 * @param runId the simulator's id for the run, or {@code null} if it did not say
 * @param seed the seed the run was started with, as the simulator reports it
 * @param time the virtual time the state was read at
 * @param attached true if an existing run was read, false if this command started one
 */
public record RunInfo(String runId, String seed, String time, boolean attached) {}
