package com.ripostelabs.projection.ns;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where the car fetches models from (road-noise/CONTRACT.md sections 5 and 8):
 *
 *   enrolled unit   uplink.endpoint "http://100.64.0.1:8797"
 *                   ──▶ http://100.64.0.1:8797/v1/models/rnnoise/  through SOCKS5 127.0.0.1:1055
 *   no endpoint     ──▶ https://launcher.hq.ripostelabs.xyz/ns-model/rnnoise/  direct (the farm)
 *
 * The uplink's tailscaled is userspace: the tailnet is reachable only through its SOCKS5 port,
 * and WireGuard already encrypts it, so the ingest service speaks plain HTTP.
 */
public final class ModelSource {

    /** Written by os/uplink/enroll.sh at enrolment; one line, the ingest base URL. */
    public static final String ENDPOINT_FILE = "/data/misc/riposte/uplink.endpoint";
    static final String MODELS_PATH = "/v1/models/rnnoise/";
    static final String FARM_BASE = "https://launcher.hq.ripostelabs.xyz/ns-model/rnnoise/";
    public static final String SOCKS_HOST = "127.0.0.1";
    public static final int SOCKS_PORT = 1055;
    static final String MANIFEST = "manifest.json";
    private static final Pattern ENDPOINT = Pattern.compile("https?://[A-Za-z0-9.\\[\\]:-]{1,100}");

    /** The folder the manifest and its version folders sit in, ending in "/". */
    public final String base;
    /** True when the base is on the tailnet: connect through the uplink's SOCKS5 port. */
    public final boolean viaUplink;

    private ModelSource(String base, boolean viaUplink) {
        this.base = base;
        this.viaUplink = viaUplink;
    }

    /** From the endpoint file's text (null when the unit is not enrolled or it is unreadable). */
    public static ModelSource from(String endpointFile) {
        if (endpointFile != null) {
            String url = endpointFile.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            if (ENDPOINT.matcher(url).matches()) {
                return new ModelSource(url.toLowerCase(Locale.ROOT) + MODELS_PATH, true);
            }
        }
        return new ModelSource(FARM_BASE, false);
    }

    public String manifestUrl() {
        return base + MANIFEST;
    }

    /** A manifest's weight file; its path was checked by {@link Manifest#parse}. */
    public String blobUrl(Manifest m) {
        return base + m.path;
    }
}
