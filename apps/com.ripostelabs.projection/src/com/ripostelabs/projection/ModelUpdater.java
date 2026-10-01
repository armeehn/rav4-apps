package com.ripostelabs.projection;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import com.ripostelabs.projection.ns.Manifest;
import com.ripostelabs.projection.ns.Model;
import com.ripostelabs.projection.ns.ModelSource;
import com.ripostelabs.projection.ns.ModelStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Fetches newer car-tuned models from the estate (ns-train/ publishes them after its eval
 * gate) into {@link ModelStore}, where the next call or Siri request picks them up.
 *
 *   boot, daily, or Car-tuned picked ──▶ (network up) ──▶ manifest.json ──▶ newer? ──▶ blob
 *                                                                          ──▶ sha256 ok? ──▶ pending
 *
 * Where from: {@link ModelSource} (the ingest service over the uplink, or launcher.hq on the
 * farm). Only runs while Car-tuned is the chosen model, so a car on Standard spends no data.
 */
public final class ModelUpdater extends JobService {

    private static final String TAG = "Projection";
    private static final String DIR = "ns-model";
    private static final int JOB_DAILY = 0x4e5301;
    private static final int JOB_NOW = 0x4e5302;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_MANIFEST = 64 * 1024;
    private static final int COPY_BUFFER = 16 * 1024;
    private static final int MAX_ENDPOINT = 512;

    private static ModelStore store;

    /** The one store per process: its lock is what keeps a download and a capture apart. */
    static synchronized ModelStore store(Context context) {
        if (store == null) {
            store = new ModelStore(new File(context.getApplicationContext().getFilesDir(), DIR));
        }
        return store;
    }

    /** A check as soon as the network is up, and one a day after that. */
    static void schedule(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js == null) {
            return;
        }
        ComponentName me = new ComponentName(context, ModelUpdater.class);
        js.schedule(new JobInfo.Builder(JOB_NOW, me)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
        js.schedule(new JobInfo.Builder(JOB_DAILY, me)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(DAY_MS)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (MicPrefs.model(this) != Model.CAR_TUNED) {
            return false;
        }
        new Thread(() -> {
            try {
                Log.i(TAG, "ns model: " + check(this));
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "ns model: update failed: " + e);
            }
            jobFinished(params, false);
        }, "ns-model").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // The next daily run tries again; a half download was never staged.
        return false;
    }

    /** One manifest check and, when it names a newer model, its download. */
    static String check(Context context) throws IOException {
        ModelSource src = ModelSource.from(endpoint());
        byte[] body = get(src, src.manifestUrl(), MAX_MANIFEST);
        Manifest m = Manifest.parse(new String(body, StandardCharsets.UTF_8));
        if (m == null) {
            return "manifest unreadable at " + src.manifestUrl();
        }
        ModelStore s = store(context);
        if (!s.wants(m)) {
            return "up to date (" + Manifest.label(s.activeVersion()) + ")";
        }

        byte[] blob = get(src, src.blobUrl(m), (int) m.size);
        if (!s.stage(m, blob)) {
            return Manifest.label(m.version) + " failed sha256/size, not kept";
        }
        return Manifest.label(m.version) + " downloaded, in use from the next call";
    }

    /** The uplink's ingest URL, or null when this unit is not enrolled (the farm). */
    private static String endpoint() {
        File f = new File(ModelSource.ENDPOINT_FILE);
        if (!f.canRead() || f.length() > MAX_ENDPOINT) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            return n <= 0 ? null : new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** GET a body of at most limit bytes; more is an error, not a truncation. */
    private static byte[] get(ModelSource src, String url, int limit) throws IOException {
        // The tailnet is only reachable through the uplink's SOCKS5 port (userspace tailscaled).
        Proxy proxy = src.viaUplink
                ? new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(ModelSource.SOCKS_HOST, ModelSource.SOCKS_PORT))
                : Proxy.NO_PROXY;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException(url + ": HTTP " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[COPY_BUFFER];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > limit) {
                        throw new IOException(url + ": longer than " + limit + " bytes");
                    }
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
