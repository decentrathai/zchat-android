package android.util;

/**
 * Minimal JVM stand-in for Android's {@code android.util.Base64}, for plain unit tests only.
 *
 * <p>The mockable android.jar (with {@code isReturnDefaultValues = true}) stubs
 * {@code Base64.encodeToString(...)} / {@code decode(...)} to return {@code null}, which NPEs the
 * first time any ViewModel-level test drives a group crypto path (e.g.
 * {@code ZMSGGroupProtocol.encodeGroupKey} inside {@code GroupViewModel.rotateAndNotify}). The repo
 * convention has otherwise pushed every {@code android.util.Base64} path into androidTest; this
 * shadow lets the pure-JVM group ViewModel race tests (RotateAndNotifyRaceTest) exercise the real
 * rotation logic without Robolectric.
 *
 * <p>Test classes precede the mockable android.jar on the unit-test classpath, so this class shadows
 * the stub — the same approach as {@code android.icu.util.Currency} already in this source set. The
 * flag semantics mirror the platform: it delegates to {@link java.util.Base64} with URL-safe /
 * padding / wrapping selected from the flag bits the codebase actually passes (NO_WRAP, NO_PADDING,
 * URL_SAFE).
 */
public final class Base64 {

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    private Base64() {
    }

    private static java.util.Base64.Encoder encoder(int flags) {
        java.util.Base64.Encoder enc;
        if ((flags & URL_SAFE) != 0) {
            enc = java.util.Base64.getUrlEncoder();
        } else if ((flags & NO_WRAP) != 0) {
            enc = java.util.Base64.getEncoder();
        } else {
            // Android DEFAULT wraps at 76 chars; MIME is the closest JVM equivalent.
            enc = java.util.Base64.getMimeEncoder();
        }
        if ((flags & NO_PADDING) != 0) {
            enc = enc.withoutPadding();
        }
        return enc;
    }

    private static java.util.Base64.Decoder decoder(int flags) {
        if ((flags & URL_SAFE) != 0) {
            return java.util.Base64.getUrlDecoder();
        }
        // MIME decoder is lenient about line breaks + missing padding, covering NO_WRAP and DEFAULT.
        return java.util.Base64.getMimeDecoder();
    }

    public static String encodeToString(byte[] input, int flags) {
        if (input == null) {
            return null;
        }
        return new String(encoder(flags).encode(input), java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static byte[] encode(byte[] input, int flags) {
        if (input == null) {
            return null;
        }
        return encoder(flags).encode(input);
    }

    public static byte[] decode(String str, int flags) {
        if (str == null) {
            return null;
        }
        return decoder(flags).decode(str);
    }

    public static byte[] decode(byte[] input, int flags) {
        if (input == null) {
            return null;
        }
        return decoder(flags).decode(input);
    }
}
