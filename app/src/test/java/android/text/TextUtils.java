/*
 * Test-only shadow of android.text.TextUtils.
 *
 * See android/util/Log.java in this test source set for the rationale. The
 * calendarcommon2 classes call TextUtils.isEmpty(...) to guard null/empty
 * recurrence strings (e.g. a null RDATE or EXDATE). Under the mockable
 * android.jar this would throw "not mocked"; worse, enabling
 * returnDefaultValues would make isEmpty() always return false and corrupt
 * parsing. This faithful implementation keeps the production code's behavior
 * intact during unit tests without any production change.
 */
package android.text;

public class TextUtils {
    private TextUtils() {
    }

    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }
}
