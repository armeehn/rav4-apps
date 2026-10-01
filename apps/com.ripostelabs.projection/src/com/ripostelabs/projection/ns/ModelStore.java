package com.ripostelabs.projection.ns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * The car-tuned models on the unit: the one in use, the one before it, and a newer one waiting.
 *
 * <pre>
 *   updater ──stage(manifest, blob)──▶ pending      (sha256 and size checked first)
 *                                         │
 *   next call or Siri request ──open()────┤ promote: previous ◀── active ◀── pending
 *                                         ▼
 *                                  engine from active ──refused──▶ active marked bad,
 *                                                                  previous back in use
 * </pre>
 *
 * A model only changes in {@link #open}, which MicSource calls as a capture starts, so a
 * running call never switches weights. A version that failed to load is never staged again.
 * Files: {@code v<N>.bin} per kept version and {@code state.properties}, in one directory.
 */
public final class ModelStore {

    /** Opens an engine on a blob; null when the blob is not a usable model. */
    public interface Opener {
        Engine open(byte[] blob);
    }

    /** What {@link #open} gave the capture: an engine and its version, or none (0). */
    public static final class Session {
        public final Engine engine;
        public final int version;
        /** Versions refused while opening, for the log. */
        public final Set<Integer> refused;

        Session(Engine engine, int version, Set<Integer> refused) {
            this.engine = engine;
            this.version = version;
            this.refused = refused;
        }
    }

    private static final String STATE = "state.properties";
    private static final String ACTIVE = "active";
    private static final String PREVIOUS = "previous";
    private static final String PENDING = "pending";
    private static final String BAD = "bad";
    private static final int NONE = 0;
    private static final int COPY_BUFFER = 64 * 1024;

    private final File dir;

    public ModelStore(File dir) {
        this.dir = dir;
    }

    public synchronized int activeVersion() {
        return version(load(), ACTIVE);
    }

    public synchronized int pendingVersion() {
        return version(load(), PENDING);
    }

    /** The active model's file, or null when none is in use. */
    public synchronized File activeFile() {
        int v = version(load(), ACTIVE);
        return v == NONE ? null : blob(v);
    }

    /** True when the manifest names a version newer than anything held and never refused. */
    public synchronized boolean wants(Manifest m) {
        Properties p = load();
        int newest = Math.max(version(p, ACTIVE), version(p, PENDING));
        return m != null && m.version > newest && !bad(p).contains(m.version);
    }

    /** Size and sha256 of a download against its manifest. */
    public static boolean verify(Manifest m, byte[] blob) {
        return m != null && blob != null && blob.length == m.size && sha256(blob).equals(m.sha256);
    }

    /** Keep a verified download as the pending model; false when it does not verify. */
    public synchronized boolean stage(Manifest m, byte[] blob) throws IOException {
        if (!wants(m) || !verify(m, blob)) {
            return false;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }

        // Write then rename, so a crash mid-write never leaves a half model under a real name.
        File tmp = new File(dir, "v" + m.version + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(blob);
        }
        if (!tmp.renameTo(blob(m.version))) {
            throw new IOException("cannot rename " + tmp);
        }

        Properties p = load();
        int old = version(p, PENDING);
        p.setProperty(PENDING, Integer.toString(m.version));
        save(p);
        if (old != NONE) {
            blob(old).delete();
        }
        return true;
    }

    /**
     * The engine for a capture that is starting: the session boundary. A pending model is
     * promoted here; a model that will not open is marked bad and the previous one is used.
     */
    public synchronized Session open(Opener opener) {
        Properties p = load();
        promote(p);

        Set<Integer> refused = new HashSet<>();
        // At most the active model and then the previous one.
        while (version(p, ACTIVE) != NONE) {
            int v = version(p, ACTIVE);
            Engine engine = opener.open(read(v));
            if (engine != null) {
                save(p);
                return new Session(engine, v, refused);
            }
            refused.add(v);
            rollBack(p, v);
        }
        save(p);
        return new Session(null, NONE, refused);
    }

    private void promote(Properties p) {
        int pending = version(p, PENDING);
        if (pending == NONE) {
            return;
        }
        int previous = version(p, PREVIOUS);
        if (previous != NONE) {
            blob(previous).delete();
        }
        p.setProperty(PREVIOUS, Integer.toString(version(p, ACTIVE)));
        p.setProperty(ACTIVE, Integer.toString(pending));
        p.setProperty(PENDING, Integer.toString(NONE));
    }

    private void rollBack(Properties p, int failed) {
        Set<Integer> bad = bad(p);
        bad.add(failed);
        p.setProperty(BAD, join(bad));
        blob(failed).delete();
        p.setProperty(ACTIVE, Integer.toString(version(p, PREVIOUS)));
        p.setProperty(PREVIOUS, Integer.toString(NONE));
    }

    private File blob(int version) {
        return new File(dir, "v" + version + ".bin");
    }

    /** A kept model's bytes; an empty array when the file is gone (the opener refuses it). */
    private byte[] read(int version) {
        File f = blob(version);
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, Math.min(COPY_BUFFER, b.length - off));
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return b;
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private Properties load() {
        Properties p = new Properties();
        File f = new File(dir, STATE);
        if (!f.exists()) {
            return p;
        }
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException e) {
            // An unreadable state is an empty one: the standard model, nothing pending.
            p.clear();
        }
        return p;
    }

    private void save(Properties p) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        File tmp = new File(dir, STATE + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            p.store(out, null);
        } catch (IOException e) {
            return;
        }
        // rename(2) replaces the old state in one step.
        if (!tmp.renameTo(new File(dir, STATE))) {
            tmp.delete();
        }
    }

    private static int version(Properties p, String key) {
        try {
            return Integer.parseInt(p.getProperty(key, "0").trim());
        } catch (NumberFormatException e) {
            return NONE;
        }
    }

    private static Set<Integer> bad(Properties p) {
        Set<Integer> out = new HashSet<>();
        for (String s : p.getProperty(BAD, "").split(",")) {
            if (s.trim().isEmpty()) {
                continue;
            }
            try {
                out.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // A mangled entry only costs a retry of that version.
            }
        }
        return out;
    }

    private static String join(Set<Integer> set) {
        StringBuilder b = new StringBuilder();
        for (int v : set) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(v);
        }
        return b.toString();
    }

    static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder b = new StringBuilder();
            for (byte x : d) {
                b.append(String.format(Locale.ROOT, "%02x", x));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
