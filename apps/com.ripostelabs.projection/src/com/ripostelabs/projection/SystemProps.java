package com.ripostelabs.projection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Read a system property the way a normal app can. The framework's own reader is not public
 * API but stays reachable by reflection on every Android this app runs on; the getprop binary
 * is the fallback, and a fork on the main thread, so it must stay the exception.
 */
final class SystemProps {

    private static final String GETPROP = "/system/bin/getprop";
    private static final String BENCH_PROP = "ro.riposte.os.bench";
    private static Method reader;
    private static boolean readerLookedUp;

    private SystemProps() {
    }

    /** The property's value, or "" when unset or unreadable. */
    static String get(String name) {
        String value = viaFramework(name);
        if (value != null) {
            return value;
        }
        try {
            Process p = new ProcessBuilder(GETPROP, name).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                p.waitFor();
                return line == null ? "" : line.trim();
            }
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    /** True on a bench build of the OS: the only place the debug hooks may answer. */
    static boolean bench() {
        return "1".equals(get(BENCH_PROP));
    }

    private static synchronized String viaFramework(String name) {
        if (!readerLookedUp) {
            readerLookedUp = true;
            try {
                reader = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            } catch (ReflectiveOperationException e) {
                reader = null;
            }
        }
        if (reader == null) {
            return null;
        }
        try {
            Object v = reader.invoke(null, name);
            return v == null ? "" : v.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
