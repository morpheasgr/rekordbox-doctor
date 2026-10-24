package org.morpheas.rbdoctor.drive;

import java.util.ArrayDeque;

/** What the app did with the drive, for the report the user can copy when something fails. */
public final class DriveLog {
    private static final int MAX_LINES = 400;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final long start = System.currentTimeMillis();

    private DriveLog() {
    }

    public static synchronized void log(String message) {
        if (lines.size() == MAX_LINES) {
            lines.removeFirst();
        }
        lines.addLast(String.format(java.util.Locale.US, "%7.2f %s", (System.currentTimeMillis() - start) / 1000.0, message));
    }

    public static synchronized String text() {
        return String.join("\n", lines);
    }
}
