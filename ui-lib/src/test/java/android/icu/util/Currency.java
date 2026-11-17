package android.icu.util;

import java.util.Locale;

/**
 * Minimal JVM stand-in for Android's ICU {@code Currency}, for plain unit tests only.
 *
 * <p>The mockable android.jar (with {@code isReturnDefaultValues = true}) stubs
 * {@code Currency.getInstance(String)} to return {@code null}, which NPEs inside the Zcash SDK's
 * {@code FiatCurrency.getSymbol()} — a call the swap-quote confirm sheet makes on EVERY render.
 * That would make the money-critical ZC-101 confirm-binding VM tests (SwapQuoteVMToctouTest)
 * impossible to run on the JVM without Robolectric.
 *
 * <p>Test classes precede the mockable android.jar on the unit-test runtime classpath, so this
 * class shadows the stub — the same approach as the real {@code org.json} artifact this module
 * already puts on the test classpath (see ui-lib/build.gradle.kts). Only the two members the SDK
 * bytecode links against are provided.
 */
public final class Currency {
    private final String code;

    private Currency(String code) {
        this.code = code;
    }

    public static Currency getInstance(String code) {
        return new Currency(code);
    }

    public String getSymbol(Locale locale) {
        return "USD".equals(code) ? "$" : code;
    }
}
