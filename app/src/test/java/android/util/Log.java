/*
 * Test-only shadow of android.util.Log.
 *
 * Local JVM unit tests run against the "mockable" android.jar whose methods
 * throw RuntimeException("Method ... not mocked"). The calendarcommon2
 * recurrence classes call Log.* on hot paths, which made every recurrence
 * unit test fail before reaching any assertion. This shadow lives in the
 * test source set and provides a real, no-op implementation matching the
 * android.util.Log API so the production code under test can run unchanged.
 *
 * It does NOT modify any production behavior; it only replaces the SDK stub
 * during unit tests.
 */
package android.util;

public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private Log() {
    }

    public static int v(String tag, String msg) {
        return 0;
    }

    public static int v(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int d(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int i(String tag, String msg) {
        return 0;
    }

    public static int i(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, String msg) {
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static int w(String tag, Throwable tr) {
        return 0;
    }

    public static int e(String tag, String msg) {
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static boolean isLoggable(String tag, int level) {
        return false;
    }

    public static String getStackTraceString(Throwable tr) {
        return tr == null ? "" : tr.toString();
    }

    public static int println(int priority, String tag, String msg) {
        return 0;
    }
}
