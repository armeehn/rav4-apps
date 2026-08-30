package com.ripostelabs.clock;

/** Immutable-ish alarm model. Persisted as a single line: id|hour|minute|enabled|label */
final class Alarm {
    int id;
    int hour;
    int minute;
    boolean enabled;
    String label;

    Alarm(int id, int hour, int minute, boolean enabled, String label) {
        this.id = id;
        this.hour = hour;
        this.minute = minute;
        this.enabled = enabled;
        this.label = label == null ? "" : label;
    }

    String serialize() {
        // label is last so it may contain anything except newline; strip separators
        String safe = label.replace("\n", " ").replace("|", " ");
        return id + "|" + hour + "|" + minute + "|" + (enabled ? 1 : 0) + "|" + safe;
    }

    static Alarm parse(String line) {
        try {
            String[] p = line.split("\\|", 5);
            if (p.length < 5) return null;
            return new Alarm(
                    Integer.parseInt(p[0]),
                    Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]),
                    "1".equals(p[3]),
                    p[4]);
        } catch (Exception e) {
            return null;
        }
    }

    String timeText() {
        int h12 = hour % 12;
        if (h12 == 0) h12 = 12;
        String ampm = hour < 12 ? "AM" : "PM";
        return h12 + ":" + (minute < 10 ? "0" + minute : String.valueOf(minute)) + " " + ampm;
    }
}
