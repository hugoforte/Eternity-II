package core;

/**
 * Minimal assertion / reporting helper for the test suite.
 *
 * Deliberately dependency-free (no JUnit) so the suite compiles and runs with
 * nothing but a JDK:
 *
 *   javac -d out src/core/*.java test/core/*.java
 *   java -cp out core.AllTests
 */
public final class T {

    public static int checks = 0;
    public static int failures = 0;
    private static String section = "";
    private static long sectionStart = 0;
    public static boolean quiet = false;

    private T() { }

    public static void section(String name) {
        section = name;
        sectionStart = System.nanoTime();
        System.out.println();
        System.out.println("== " + name + " " + dashes(Math.max(2, 58 - name.length())));
    }

    public static void endSection() {
        long ms = (System.nanoTime() - sectionStart) / 1000000L;
        System.out.println("   (" + section + " finished in " + ms + " ms)");
    }

    private static String dashes(int k) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < k; i++) sb.append('=');
        return sb.toString();
    }

    public static void ok(String name) {
        checks++;
        if (!quiet) System.out.println("  [PASS] " + name);
    }

    public static void fail(String name, String detail) {
        checks++;
        failures++;
        System.out.println("  [FAIL] " + name);
        if (detail != null) System.out.println("         " + detail);
    }

    public static void check(String name, boolean cond) {
        if (cond) ok(name); else fail(name, "condition was false");
    }

    /** Count a check without printing a PASS line (for tight inner loops). */
    public static void checkSilent(boolean cond) {
        checks++;
        if (!cond) {
            failures++;
            System.out.println("  [FAIL] (silent check failed)");
        }
    }

    public static void check(String name, boolean cond, String detail) {
        if (cond) ok(name); else fail(name, detail);
    }

    public static void eq(String name, long expected, long actual) {
        if (expected == actual) ok(name);
        else fail(name, "expected " + expected + " but got " + actual);
    }

    public static void eq(String name, String expected, String actual) {
        boolean same = (expected == null) ? (actual == null) : expected.equals(actual);
        if (same) ok(name);
        else fail(name, "expected [" + expected + "] but got [" + actual + "]");
    }

    public static void isNull(String name, Object o) {
        if (o == null) ok(name);
        else fail(name, "expected null but got [" + o + "]");
    }

    public static void notNull(String name, Object o) {
        if (o != null) ok(name);
        else fail(name, "expected non-null");
    }

    public static void eqIntArray(String name, int[] expected, int[] actual) {
        if (expected == null || actual == null) {
            check(name, expected == actual, "one array was null");
            return;
        }
        if (expected.length != actual.length) {
            fail(name, "length " + expected.length + " != " + actual.length);
            return;
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail(name, "index " + i + ": expected " + expected[i] + " got " + actual[i]);
                return;
            }
        }
        ok(name);
    }

    public static void eqLongArray(String name, long[] expected, long[] actual) {
        if (expected == null || actual == null) {
            check(name, expected == actual, "one array was null");
            return;
        }
        if (expected.length != actual.length) {
            fail(name, "length " + expected.length + " != " + actual.length);
            return;
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail(name, "index " + i + ": expected 0x" + Long.toHexString(expected[i])
                         + " got 0x" + Long.toHexString(actual[i]));
                return;
            }
        }
        ok(name);
    }

    public static void eqBoolArray(String name, boolean[] expected, boolean[] actual) {
        if (expected == null || actual == null) {
            check(name, expected == actual, "one array was null");
            return;
        }
        if (expected.length != actual.length) {
            fail(name, "length " + expected.length + " != " + actual.length);
            return;
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail(name, "index " + i + ": expected " + expected[i] + " got " + actual[i]);
                return;
            }
        }
        ok(name);
    }

    /**
     * Record the outcome of a "this should throw" test.  Callers wrap the
     * offending call in their own try/catch and pass the result here, which
     * keeps the helper free of anonymous classes / lambdas.
     */
    public static void threw(String name, boolean didThrow) {
        if (didThrow) ok(name);
        else fail(name, "expected an exception but none was thrown");
    }

    public static void summary() {
        System.out.println();
        System.out.println("================================================================");
        System.out.println(" checks run : " + checks);
        System.out.println(" failures   : " + failures);
        System.out.println(" result     : " + (failures == 0 ? "ALL TESTS PASSED" : "FAILED"));
        System.out.println("================================================================");
    }
}
