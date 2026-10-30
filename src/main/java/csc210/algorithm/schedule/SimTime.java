package csc210.algorithm.schedule;

/** Simulated time of day as whole seconds since midnight, read from either form DSTNS and the portal use. */
public final class SimTime {

    public static final int DAY_S = 24 * 3600;

    private SimTime() {}

    /**
     * {@code "21600"} or {@code "06:00:00"} to 21600.
     *
     * @throws IllegalArgumentException for anything else, or a time outside one day
     */
    public static int parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no time given");
        }
        int seconds;
        if (text.matches("\\d{1,5}")) {
            seconds = Integer.parseInt(text);
        } else if (text.matches("\\d{2}:\\d{2}:\\d{2}")) {
            int hours = Integer.parseInt(text.substring(0, 2));
            int minutes = Integer.parseInt(text.substring(3, 5));
            int secs = Integer.parseInt(text.substring(6, 8));
            if (minutes > 59 || secs > 59) {
                throw new IllegalArgumentException("not a time of day: '" + text + "'");
            }
            seconds = hours * 3600 + minutes * 60 + secs;
        } else {
            throw new IllegalArgumentException("a time must be seconds of the day or HH:MM:SS, got '" + text + "'");
        }
        if (seconds >= DAY_S) {
            throw new IllegalArgumentException("a time must be within one day, got '" + text + "'");
        }
        return seconds;
    }
}
