package android.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JVM stand-in for Android's {@code android.util.Log}, for plain unit tests only.
 *
 * <p>With {@code isReturnDefaultValues = true} the mockable android.jar turns every log call into a no-op
 * returning 0, so a test cannot tell what the app claims in its logs. This class keeps exactly that
 * behaviour (every method returns the same default value) and, only while a test has called
 * {@link #startCapture()}, also records {@code "TAG: message"} lines. Test classes precede the mockable
 * android.jar on the unit-test classpath, as with {@link Base64} in this source set.
 *
 * <p>P2h: used to pin that a log line never says something was re-sent when nothing was sent.
 */
public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private static final List<String> CAPTURED = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> CAPTURED_WARNINGS = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean capturing = false;

    private Log() {
    }

    /** Starts recording log lines (clears earlier ones). */
    public static void startCapture() {
        CAPTURED.clear();
        CAPTURED_WARNINGS.clear();
        capturing = true;
    }

    /** Stops recording and forgets what was recorded. */
    public static void stopCapture() {
        capturing = false;
        CAPTURED.clear();
        CAPTURED_WARNINGS.clear();
    }

    /** A copy of the lines recorded since {@link #startCapture()}. */
    public static List<String> captured() {
        synchronized (CAPTURED) {
            return new ArrayList<>(CAPTURED);
        }
    }

    /** A copy of the "TAG: message" lines logged at WARN or above (w, e, wtf) since {@link #startCapture()}. */
    public static List<String> capturedWarnings() {
        synchronized (CAPTURED_WARNINGS) {
            return new ArrayList<>(CAPTURED_WARNINGS);
        }
    }

    private static int record(String tag, String msg) {
        if (capturing) {
            CAPTURED.add(tag + ": " + msg);
        }
        return 0;
    }

    private static int record(int level, String tag, String msg) {
        if (capturing && level >= WARN) {
            CAPTURED_WARNINGS.add(tag + ": " + msg);
        }
        return record(tag, msg);
    }

    public static int v(String tag, String msg) {
        return record(tag, msg);
    }

    public static int v(String tag, String msg, Throwable tr) {
        return record(tag, msg);
    }

    public static int d(String tag, String msg) {
        return record(tag, msg);
    }

    public static int d(String tag, String msg, Throwable tr) {
        return record(tag, msg);
    }

    public static int i(String tag, String msg) {
        return record(tag, msg);
    }

    public static int i(String tag, String msg, Throwable tr) {
        return record(tag, msg);
    }

    public static int w(String tag, String msg) {
        return record(WARN, tag, msg);
    }

    public static int w(String tag, String msg, Throwable tr) {
        return record(WARN, tag, msg);
    }

    public static int w(String tag, Throwable tr) {
        return record(WARN, tag, String.valueOf(tr));
    }

    public static int e(String tag, String msg) {
        return record(ERROR, tag, msg);
    }

    public static int e(String tag, String msg, Throwable tr) {
        return record(ERROR, tag, msg);
    }

    public static int wtf(String tag, String msg) {
        return record(ASSERT, tag, msg);
    }

    public static int wtf(String tag, Throwable tr) {
        return record(ASSERT, tag, String.valueOf(tr));
    }

    public static int wtf(String tag, String msg, Throwable tr) {
        return record(ASSERT, tag, msg);
    }

    public static boolean isLoggable(String tag, int level) {
        return false;
    }

    public static String getStackTraceString(Throwable tr) {
        return null;
    }

    public static int println(int priority, String tag, String msg) {
        return record(tag, msg);
    }
}
