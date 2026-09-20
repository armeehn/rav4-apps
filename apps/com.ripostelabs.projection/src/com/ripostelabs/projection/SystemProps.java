package com.ripostelabs.projection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** Read a system property the way a normal app can: through the getprop binary. */
final class SystemProps {

    private static final String GETPROP = "/system/bin/getprop";

    private SystemProps() {
    }

    /** The property's value, or "" when unset or unreadable. */
    static String get(String name) {
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
}
